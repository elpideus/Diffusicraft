package io.github.elpideus.diffusicraft.worldgen.lod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The columns of the places players have been in one level, as they stood when their chunks were last saved (see
 * {@link ChunkColumns}), so that the far terrain shows what was built there.
 *
 * <p>Each level of detail keeps its own columns: the one at block {@code (x, z)} of the level whose columns are
 * {@code step} blocks apart, where {@code x} and {@code z} are {@code step / 2} past a multiple of the step, as
 * {@link LodSampler} takes them. The columns of a level come in pages of {@value #PAGE}² columns, which a grid of the
 * same step and alignment overlays on the one it samples ({@link #overlay}). A chunk adds a column to every level
 * whose columns it holds, about 341 in all, most of them to the finest.
 *
 * <p>The pages are kept in region files, through Minecraft's own {@link IOWorker}, a folder for each level of detail:
 * under {@code diffusicraft/lod} in the world's folder on a server, and on a client that keeps the chunks it has seen
 * of a server that does not share what players built, in {@code diffusicraft/seen} in the game's folder. Everything
 * runs on a thread of the store's own: the server thread hands over the copy of a chunk that vanilla takes to save it,
 * the client the sections it copied of a chunk it let go of, and readers get a future. Vanilla saves a chunk again
 * whenever something in it changed, as grass spreads or a crop grows, so a page whose columns come out as they were
 * is neither written nor heard of again. A coarser level keeps the caves of a chunk only where it draws caves at all,
 * and those as high as its step at least, as {@link LodSampler} does.
 *
 * <p>A server's store that a build of the mod keeping other columns left, or that a world played without the mod has
 * none of, reads every chunk saved in the level again in the background as it opens, and notes in its folder that it
 * did ({@link #VERSION_FILE}); a page another build wrote reads as missing until then.
 *
 * <p>The store also keeps the pages of the terrain as generated, whatever players did to it, so that the far terrain
 * need not run the models again after the world is closed and opened ({@link #generated(int, int, int)}). They are
 * kept in a folder of their own for each level of detail, and only as long as the build of the mod that generated them
 * runs: another build may generate other terrain.
 */
public final class LodStore implements AutoCloseable {
	/** Columns along each side of a page. */
	public static final int PAGE = 64;
	/** Levels of detail kept: steps of 1 up to 512 blocks between columns. */
	public static final int LEVELS = 10;
	/** What the stored columns hold; a page of another version is dropped. 2: the caves under the ground. */
	private static final int VERSION = 2;
	/** The file in a server's store that names the {@link #VERSION} of the columns it has read every saved chunk to. */
	private static final String VERSION_FILE = "version";
	/** Places of saved chunks looked up before the pages they touch are written, when the store reads them again. */
	private static final int CATCH_UP_SLICE = 256;
	/** Pages kept in memory, of every level together. */
	private static final int CACHED_PAGES = 192;
	/** Pages known to be missing kept in memory, so that asking for them again reads nothing. */
	private static final int CACHED_MISSING = 4096;
	/** Chunks read before the pages they touch are written, so that a page asked for meanwhile waits for no more. */
	private static final int DRAIN_SLICE = 32;
	private static final Logger LOGGER = LogUtils.getLogger();
	/**
	 * A digest of the code and data that shape the generated terrain, from {@code diffusicraft-build.properties}, which
	 * a kept page of it must match; empty where the build has none, which keeps none.
	 */
	private static final String GENERATION = generation();

	/** The stored columns of a page: those whose bit is set in {@code present}, out of a grid {@value #PAGE} wide. */
	public record Page(long[] present, LodGrid grid) {
		public boolean has(int column) {
			return (present[column >> 6] >>> column & 1) != 0;
		}

		/** Whether every column of the page is stored. */
		public boolean full() {
			for (long bits : present) {
				if (bits != -1L) {
					return false;
				}
			}
			return true;
		}
	}

	/** Hears when the pages of a level of detail change. */
	@FunctionalInterface
	public interface Listener {
		/** The page at {@code (pageX, pageZ)} of level {@code level} changed. Runs on the store's thread. */
		void changed(int level, int pageX, int pageZ);
	}

	private static final Map<ResourceKey<Level>, Optional<LodStore>> STORES = new ConcurrentHashMap<>();
	private static volatile @Nullable MinecraftServer server;

	private final Path folder;
	private final IOWorker[] workers = new IOWorker[LEVELS];
	private final IOWorker[] generated = new IOWorker[LEVELS];
	/** Held to read or write the generated pages, and to stop that as the store closes. */
	private final ReadWriteLock open = new ReentrantReadWriteLock();
	/** Guarded by {@link #open}. */
	private boolean closed;
	private final ChunkColumns reader = new ChunkColumns();
	private final HolderLookup<Biome> biomes;
	private final Holder<Biome> fallback;
	private final ExecutorService thread;
	/** The chunks handed over and not read yet, each once, as it was saved last. */
	private final Map<ChunkPos, Saved> saved = new LinkedHashMap<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Map<Long, Page> cache;
	private final Set<Long> missing = Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
			return size() > CACHED_MISSING;
		}
	});
	private boolean draining;
	/** How many chunks were handed over and not read yet, for the debug screen. */
	private volatile int backlog;
	/** The places of the saved chunks still to read again (see {@link #catchUp}). Read on the store's thread only. */
	private final ArrayDeque<ChunkPos> behind = new ArrayDeque<>();

	public LodStore(Path folder, ResourceKey<Level> dimension, HolderLookup<Biome> biomes, Holder<Biome> fallback) {
		this.folder = folder;
		for (int level = 0; level < LEVELS; level++) {
			RegionStorageInfo info = new RegionStorageInfo(dimension.identifier().toString(), dimension,
				"diffusicraft_lod_" + level);
			workers[level] = new IOWorker(info, folder.resolve("L" + level), false) {
			};
			RegionStorageInfo generatedInfo = new RegionStorageInfo(dimension.identifier().toString(), dimension,
				"diffusicraft_lod_generated_" + level);
			generated[level] = new IOWorker(generatedInfo, folder.resolve("G" + level), false) {
			};
		}
		this.biomes = biomes;
		this.fallback = fallback;
		this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform()
			.name("Diffusicraft LOD store " + dimension.identifier()).daemon().priority(Thread.MIN_PRIORITY).factory());
		this.cache = new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
				return size() > CACHED_PAGES;
			}
		};
	}

	/** Opens the stores of a server's levels as it starts, and closes them once it has saved its chunks for good. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			server = null;
			for (Optional<LodStore> store : STORES.values()) {
				store.ifPresent(LodStore::closeQuietly);
			}
			STORES.clear();
		});
	}

	/** The store of a level of the running server, or {@code null} if Diffusicraft does not generate it. */
	public static @Nullable LodStore of(ServerLevel level) {
		MinecraftServer current = server;
		if (current == null || level.getServer() != current) {
			return null;
		}
		return STORES.computeIfAbsent(level.dimension(), key -> {
			if (!LodSampler.generates(level)) {
				return Optional.empty();
			}
			HolderLookup.RegistryLookup<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
			LodStore store = new LodStore(folder(current.getWorldPath(LevelResource.ROOT), key), key, biomes,
				biomes.getOrThrow(Biomes.PLAINS));
			store.catchUp(level);
			return Optional.of(store);
		}).orElse(null);
	}

	/**
	 * Reads every chunk saved in {@code level} again, in the background, unless the store has done so since
	 * {@link #VERSION} last changed.
	 */
	private void catchUp(ServerLevel level) {
		Path marker = folder.resolve(VERSION_FILE);
		try {
			if (Files.isRegularFile(marker) && Files.readString(marker).strip().equals(Integer.toString(VERSION))) {
				return;
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read {}", marker, e);
		}
		Path regions = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(
			LevelResource.ROOT)).resolve("region");
		thread.execute(() -> {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(regions, "r.*.*.mca")) {
				for (Path file : files) {
					String[] parts = file.getFileName().toString().split("\\.");
					int regionX = Integer.parseInt(parts[1]);
					int regionZ = Integer.parseInt(parts[2]);
					for (int z = 0; z < 32; z++) {
						for (int x = 0; x < 32; x++) {
							behind.add(new ChunkPos(regionX * 32 + x, regionZ * 32 + z));
						}
					}
				}
			} catch (NoSuchFileException e) {
				// Nothing saved yet.
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not list the chunks saved in {} for the far terrain", regions, e);
				behind.clear();
				return;
			}
			if (!behind.isEmpty()) {
				LOGGER.info("Reading the chunks saved in {} again for the far terrain", level.dimension().identifier());
			}
			catchUpSlice(level, marker);
		});
	}

	/**
	 * Reads the next of the saved chunks {@link #catchUp} found and updates their pages, then the rest after the pages
	 * asked for meanwhile. Notes in the store's folder that it is done once there are none left, unless the server
	 * stops first: then it starts over the next time.
	 */
	private void catchUpSlice(ServerLevel level, Path marker) {
		Map<Long, Map<Integer, LodColumn>>[] changes = newChanges();
		int current = SharedConstants.getCurrentVersion().dataVersion().version();
		int found = 0;
		for (int n = 0; n < CATCH_UP_SLICE && found < DRAIN_SLICE && !behind.isEmpty(); n++) {
			if (!level.getServer().isRunning()) {
				behind.clear();
				return;
			}
			ChunkPos pos = behind.poll();
			try {
				Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(pos).join();
				// A chunk an older game saved is read once the server has brought it up to date and saved it again.
				if (tag.isEmpty() || NbtUtils.getDataVersion(tag.get(), 0) < current) {
					continue;
				}
				SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(),
					tag.get());
				if (data.chunkStatus().getChunkType() == ChunkType.LEVELCHUNK) {
					read(new Saved(pos, sink -> reader.read(data, sink)), changes);
					found++;
				}
			} catch (RuntimeException e) {
				LOGGER.warn("Could not read chunk {} again for the far terrain", pos, e);
			}
		}
		update(changes);
		if (!behind.isEmpty()) {
			try {
				thread.execute(() -> catchUpSlice(level, marker));
			} catch (RejectedExecutionException e) {
				// Closing: the rest is read the next time.
				behind.clear();
			}
			return;
		}
		try {
			Files.createDirectories(folder);
			Files.writeString(marker, Integer.toString(VERSION));
		} catch (IOException e) {
			LOGGER.warn("Could not write {}", marker, e);
		}
	}

	/** The folder a level's store keeps its pages in, under the world's. */
	public static Path folder(Path world, ResourceKey<Level> dimension) {
		Identifier id = dimension.identifier();
		return world.resolve(Diffusicraft.MOD_ID).resolve("lod").resolve(id.getNamespace()).resolve(id.getPath());
	}

	public void listen(Listener listener) {
		listeners.add(listener);
	}

	public void unlisten(Listener listener) {
		listeners.remove(listener);
	}

	/**
	 * Takes in a chunk as vanilla saves it. Call it on the thread that took the copy; the copy is read later, on the
	 * store's thread. Chunks that are not yet whole are left out.
	 */
	public void saved(SerializableChunkData data) {
		if (data.chunkStatus().getChunkType() == ChunkType.LEVELCHUNK) {
			saved(new Saved(data.chunkPos(), sink -> reader.read(data, sink)));
		}
	}

	/**
	 * Takes in a chunk from its sections, bottom first from section Y {@code minSectionY}, those below left out: the
	 * deepest run of each column reaches on down to block Y {@code bottom}. The sections and the light are read later,
	 * on the store's thread, so they must be copies that nothing else changes.
	 */
	public void saved(ChunkPos pos, LevelChunkSection[] sections, @Nullable DataLayer[] sky, int minSectionY,
					  int bottom) {
		saved(new Saved(pos, sink -> reader.read(sections, sky, minSectionY, bottom, sink)));
	}

	/** Takes in the columns of a chunk, {@code columns[z * 16 + x]}, as if it had been saved so. */
	void saved(ChunkPos pos, LodColumn[] columns) {
		saved(new Saved(pos, sink -> {
			for (int i = 0; i < columns.length; i++) {
				sink.accept(i & 15, i >> 4, columns[i]);
			}
		}));
	}

	private void saved(Saved chunk) {
		synchronized (saved) {
			// Saved again before it was read: read once, as it is now.
			saved.put(chunk.pos(), chunk);
			backlog = saved.size();
			if (draining) {
				return;
			}
			draining = true;
		}
		thread.execute(this::drain);
	}

	/** How many chunks were handed over and are still to be read, without waiting for them. */
	public int backlog() {
		return backlog;
	}

	/**
	 * Reads the chunks handed over, {@value #DRAIN_SLICE} at a time, and updates each page they touch once. The rest
	 * waits behind the pages asked for meanwhile, unless the store is closing.
	 */
	private void drain() {
		while (drainSlice()) {
			try {
				thread.execute(this::drain);
				return;
			} catch (RejectedExecutionException e) {
				// Closing: the rest is read now.
			}
		}
	}

	/** Reads the next {@value #DRAIN_SLICE} chunks handed over and updates their pages; whether more are left. */
	private boolean drainSlice() {
		Map<Long, Map<Integer, LodColumn>>[] changes = newChanges();
		for (int n = 0; n < DRAIN_SLICE; n++) {
			Saved chunk;
			synchronized (saved) {
				Iterator<Saved> first = saved.values().iterator();
				if (!first.hasNext()) {
					break;
				}
				chunk = first.next();
				first.remove();
				backlog = saved.size();
			}
			try {
				read(chunk, changes);
			} catch (RuntimeException e) {
				LOGGER.warn("Could not read chunk {} for the far terrain", chunk.pos(), e);
			}
		}
		update(changes);
		synchronized (saved) {
			draining = !saved.isEmpty();
			return draining;
		}
	}

	/** Updates each page of each level that the chunks read changed. */
	private void update(Map<Long, Map<Integer, LodColumn>>[] changes) {
		for (int level = 0; level < LEVELS; level++) {
			for (Map.Entry<Long, Map<Integer, LodColumn>> entry : changes[level].entrySet()) {
				long key = entry.getKey();
				try {
					update(level, key, entry.getValue());
				} catch (RuntimeException e) {
					LOGGER.warn("Could not update page {} of level {} of the far terrain", ChunkPos.unpack(key), level, e);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, Map<Integer, LodColumn>>[] newChanges() {
		Map<Long, Map<Integer, LodColumn>>[] changes = new Map[LEVELS];
		for (int level = 0; level < LEVELS; level++) {
			changes[level] = new HashMap<>();
		}
		return changes;
	}

	private static void read(Saved chunk, Map<Long, Map<Integer, LodColumn>>[] changes) {
		int minX = chunk.pos().getMinBlockX();
		int minZ = chunk.pos().getMinBlockZ();
		chunk.columns().accept((x, z, column) -> {
			int blockX = minX + x;
			int blockZ = minZ + z;
			for (int level = 0; level < LEVELS; level++) {
				int step = 1 << level;
				if (Math.floorMod(blockX, step) != step / 2 || Math.floorMod(blockZ, step) != step / 2) {
					continue;
				}
				int span = PAGE * step;
				int pageX = Math.floorDiv(blockX, span);
				int pageZ = Math.floorDiv(blockZ, span);
				int index = (blockZ - pageZ * span) / step * PAGE + (blockX - pageX * span) / step;
				// Caves narrower than the columns are apart, or any where the far terrain draws none, are filled in.
				int thinnest = step > LodSampler.CAVE_STEP ? Integer.MAX_VALUE : step;
				changes[level].computeIfAbsent(ChunkPos.pack(pageX, pageZ), k -> new HashMap<>())
					.put(index, column.closed(thinnest));
			}
		});
	}

	/** Writes the columns of a page that changed, keeping the others: nothing if none did. */
	private void update(int level, long key, Map<Integer, LodColumn> columns) {
		Page old = load(level, key);
		if (old != null && unchanged(old, columns)) {
			return;
		}
		long[] present = old == null ? new long[PAGE * PAGE / 64] : old.present().clone();
		LodGrid grid = new LodGrid(PAGE);
		Holder<Biome> filler = columns.values().iterator().next().biome();
		for (int column = 0; column < PAGE * PAGE; column++) {
			LodColumn replaced = columns.get(column);
			if (replaced != null) {
				grid.add(replaced, Integer.MAX_VALUE);
				present[column >> 6] |= 1L << column;
			} else if (old != null && old.has(column)) {
				grid.add(old.grid(), column, Integer.MAX_VALUE);
			} else {
				grid.addEmpty(filler);
			}
		}
		Page page = new Page(present, grid);
		cache.put(cacheKey(level, key), page);
		missing.remove(cacheKey(level, key));
		workers[level].store(ChunkPos.unpack(key), write(page));
		for (Listener listener : listeners) {
			listener.changed(level, ChunkPos.getX(key), ChunkPos.getZ(key));
		}
	}

	/** Whether every column in {@code columns} is stored in {@code page} as it is. */
	private static boolean unchanged(Page page, Map<Integer, LodColumn> columns) {
		for (Map.Entry<Integer, LodColumn> entry : columns.entrySet()) {
			int column = entry.getKey();
			if (!page.has(column) || !page.grid().same(column, entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	/** The stored columns of the page at {@code (pageX, pageZ)} of a level, or {@code null} if it has none. */
	public CompletableFuture<@Nullable Page> page(int level, int pageX, int pageZ) {
		return CompletableFuture.supplyAsync(() -> load(level, ChunkPos.pack(pageX, pageZ)), thread);
	}

	private @Nullable Page load(int level, long key) {
		long cached = cacheKey(level, key);
		Page page = cache.get(cached);
		if (page != null || missing.contains(cached)) {
			return page;
		}
		Optional<CompoundTag> tag = workers[level].loadAsync(ChunkPos.unpack(key)).join();
		if (tag.isEmpty()) {
			missing.add(cached);
			return null;
		}
		try {
			page = read(tag.get());
		} catch (RuntimeException e) {
			LOGGER.warn("Dropping unreadable page {} of level {} of the far terrain", ChunkPos.unpack(key), level, e);
			return null;
		}
		if (page != null) {
			cache.put(cached, page);
		}
		return page;
	}

	/**
	 * The page at {@code (pageX, pageZ)} of a level as it was generated, if this build of the mod generated it before,
	 * or null. Any thread; it waits for the page to be read.
	 */
	public @Nullable LodGrid generated(int level, int pageX, int pageZ) {
		if (GENERATION.isEmpty()) {
			return null;
		}
		Optional<CompoundTag> tag;
		open.readLock().lock();
		try {
			if (closed) {
				return null;
			}
			tag = generated[level].loadAsync(new ChunkPos(pageX, pageZ)).join();
		} finally {
			open.readLock().unlock();
		}
		if (tag.isEmpty() || !GENERATION.equals(tag.get().getStringOr("generation", ""))) {
			return null;
		}
		try {
			return readGrid(tag.get());
		} catch (RuntimeException e) {
			LOGGER.warn("Dropping unreadable generated page {}, {} of level {} of the far terrain", pageX, pageZ, level,
				e);
			return null;
		}
	}

	/**
	 * Keeps the page at {@code (pageX, pageZ)} of a level as it was generated, {@code grid}, with no column sunk under
	 * a ceiling, for {@link #generated(int, int, int)}. Any thread; it is written later.
	 */
	public void generated(int level, int pageX, int pageZ, LodGrid grid) {
		if (GENERATION.isEmpty()) {
			return;
		}
		CompoundTag tag = write(grid);
		tag.putString("generation", GENERATION);
		open.readLock().lock();
		try {
			if (!closed) {
				generated[level].store(new ChunkPos(pageX, pageZ), tag);
			}
		} finally {
			open.readLock().unlock();
		}
	}

	/**
	 * Lays the stored columns over a grid of {@code width}² columns {@code step} blocks apart from block
	 * {@code (minX, minZ)}, sinking them as {@link LodGrid#sample} does, and samples the grid with {@code sampled}
	 * only if some column is not stored. A grid whose step is not a level's or whose corner is not a multiple of its
	 * step is sampled as it is. Null if {@code sampled} is needed and gives null; {@link LodGrid#rough} if what it
	 * gives is.
	 */
	public @Nullable LodGrid overlay(int minX, int minZ, int width, int step, int ceiling,
									 Supplier<? extends @Nullable LodGrid> sampled) {
		int level = Integer.numberOfTrailingZeros(step);
		if (Integer.bitCount(step) != 1 || level >= LEVELS || Math.floorMod(minX, step) != 0
			|| Math.floorMod(minZ, step) != 0) {
			return sampled.get();
		}
		int span = PAGE * step;
		int firstX = Math.floorDiv(minX, span);
		int firstZ = Math.floorDiv(minZ, span);
		int lastX = Math.floorDiv(minX + (width - 1) * step, span);
		int lastZ = Math.floorDiv(minZ + (width - 1) * step, span);
		List<CompletableFuture<@Nullable Page>> futures = new ArrayList<>();
		for (int pz = firstZ; pz <= lastZ; pz++) {
			for (int px = firstX; px <= lastX; px++) {
				futures.add(page(level, px, pz));
			}
		}
		int across = lastX - firstX + 1;
		Page[] found = new Page[futures.size()];
		boolean any = false;
		for (int i = 0; i < found.length; i++) {
			found[i] = futures.get(i).join();
			any |= found[i] != null;
		}
		if (!any) {
			return sampled.get();
		}
		int[] page = new int[width * width];
		int[] index = new int[width * width];
		boolean whole = true;
		for (int b = 0; b < width; b++) {
			int z = minZ + b * step;
			int pz = Math.floorDiv(z, span);
			for (int a = 0; a < width; a++) {
				int x = minX + a * step;
				int px = Math.floorDiv(x, span);
				int k = b * width + a;
				page[k] = (pz - firstZ) * across + px - firstX;
				index[k] = (z - pz * span) / step * PAGE + (x - px * span) / step;
				whole &= found[page[k]] != null && found[page[k]].has(index[k]);
			}
		}
		LodGrid base = whole ? null : sampled.get();
		if (!whole && base == null) {
			return null;
		}
		LodGrid grid = new LodGrid(width);
		grid.rough(base != null && base.rough());
		for (int k = 0; k < width * width; k++) {
			Page from = found[page[k]];
			if (from != null && from.has(index[k])) {
				grid.add(from.grid(), index[k], ceiling);
			} else {
				grid.add(base, k, Integer.MAX_VALUE);
			}
		}
		return grid;
	}

	/**
	 * A page on disk: its grid with every block and biome as a number into the page's own list of their names, so that
	 * it reads the same after the game or its mods number them anew. A block or biome that is gone reads as air or
	 * {@code fallback}.
	 */
	private CompoundTag write(Page page) {
		CompoundTag tag = write(page.grid());
		tag.putLongArray("present", page.present());
		return tag;
	}

	/** A grid on disk, as {@link #write(Page)} writes a page's. */
	private CompoundTag write(LodGrid grid) {
		Palette<BlockState> states = new Palette<>();
		Palette<Holder<Biome>> biomes = new Palette<>();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		LodGrid.codec(states.codec(), biomes.codec()).encode(buf, grid);
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		ListTag stateNames = new ListTag();
		for (BlockState state : states.values) {
			stateNames.add(StringTag.valueOf(BlockStateParser.serialize(state)));
		}
		ListTag biomeNames = new ListTag();
		for (Holder<Biome> biome : biomes.values) {
			biomeNames.add(StringTag.valueOf(biome.unwrapKey().orElseThrow().identifier().toString()));
		}
		CompoundTag tag = new CompoundTag();
		tag.putInt("version", VERSION);
		tag.put("states", stateNames);
		tag.put("biomes", biomeNames);
		tag.putByteArray("grid", bytes);
		return tag;
	}

	private @Nullable Page read(CompoundTag tag) {
		long[] present = tag.getLongArray("present").orElse(null);
		if (present == null || present.length != PAGE * PAGE / 64) {
			return null;
		}
		LodGrid grid = readGrid(tag);
		return grid == null ? null : new Page(present, grid);
	}

	/** The grid {@link #write(LodGrid)} wrote, or null if it is of another version or not a page's. */
	private @Nullable LodGrid readGrid(CompoundTag tag) {
		byte[] bytes = tag.getByteArray("grid").orElse(null);
		if (tag.getIntOr("version", 0) != VERSION || bytes == null) {
			return null;
		}
		Palette<BlockState> states = new Palette<>();
		for (Tag name : tag.getListOrEmpty("states")) {
			states.values.add(state(name.asString().orElse("")));
		}
		Palette<Holder<Biome>> biomes = new Palette<>();
		for (Tag name : tag.getListOrEmpty("biomes")) {
			biomes.values.add(biome(name.asString().orElse("")));
		}
		LodGrid grid = LodGrid.codec(states.codec(), biomes.codec())
			.decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)));
		return grid.width() == PAGE ? grid : null;
	}

	private static BlockState state(String name) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState();
		} catch (CommandSyntaxException e) {
			return Blocks.AIR.defaultBlockState();
		}
	}

	private Holder<Biome> biome(String name) {
		Identifier id = Identifier.tryParse(name);
		return id == null ? fallback : biomes.get(ResourceKey.create(Registries.BIOME, id))
			.<Holder<Biome>>map(holder -> holder).orElse(fallback);
	}

	private static String generation() {
		Properties properties = new Properties();
		try (InputStream in = LodStore.class.getResourceAsStream("/diffusicraft-build.properties")) {
			if (in != null) {
				properties.load(in);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read the build's properties; the far terrain will keep no generated terrain", e);
		}
		String generation = properties.getProperty("generation", "");
		// Unexpanded where the resources were not processed.
		return generation.startsWith("$") ? "" : generation;
	}

	private static long cacheKey(int level, long key) {
		return key * LEVELS + level;
	}

	/** A chunk handed over, whose columns are read on the store's thread. */
	private record Saved(ChunkPos pos, Consumer<LodSampler.Sink> columns) {
	}

	/** The values a page names, each written as its place in the list. */
	private static final class Palette<T> {
		final List<T> values = new ArrayList<>();
		private final Map<T, Integer> ids = new HashMap<>();

		StreamCodec<FriendlyByteBuf, T> codec() {
			return StreamCodec.of((buf, value) -> buf.writeVarInt(ids.computeIfAbsent(value, v -> {
				values.add(v);
				return values.size() - 1;
			})), buf -> values.get(buf.readVarInt()));
		}
	}

	private void closeQuietly() {
		try {
			close();
		} catch (IOException e) {
			LOGGER.warn("Could not close the far terrain store", e);
		}
	}

	/** Writes what is left and closes the files. */
	@Override
	public void close() throws IOException {
		open.writeLock().lock();
		try {
			closed = true;
		} finally {
			open.writeLock().unlock();
		}
		thread.shutdown();
		try {
			if (!thread.awaitTermination(1, TimeUnit.MINUTES)) {
				LOGGER.warn("The far terrain store took over a minute to finish");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		for (IOWorker[] kind : List.of(workers, generated)) {
			for (IOWorker worker : kind) {
				worker.synchronize(true).join();
				worker.close();
			}
		}
	}
}
