package io.github.elpideus.diffusicraft.client.compat.voxy;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.client.lod.RemoteLods;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terrain of one of Voxy's worlds: fills the sections Voxy's store does not hold from Diffusicraft's terrain, as
 * {@link LodGrid} cuts them, so that Voxy draws the far terrain without the chunks having been generated. In
 * singleplayer the grids come from the level's own generator; on a server that runs Diffusicraft, from the far terrain
 * it sends ({@link RemoteLods}). Elsewhere, and beyond the server's radius, Voxy shows the chunks it has seen, as it
 * does without Diffusicraft. A section whose grid the server has not sent yet is left empty, and filled and drawn again
 * when the grid comes, round the chunks Voxy has written into it meanwhile, as Voxy does with the chunks it is sent:
 * Voxy's threads, some of them Sodium's, never wait on the network.
 *
 * <p>In singleplayer the sections filled are not saved: Voxy saves a section only once the chunks it loads change it,
 * and they then replace what was filled where they lie. From a server they are, as Voxy saves the chunks a server
 * sends, so that Voxy reads them back from its store rather than asking the server again each time it lets go of one.
 * Voxy finds the sections to draw by walking down from its coarsest ones, through the children each says hold blocks;
 * a section claims every child under its blocks and one voxel round them, and the parents of a section that turns out
 * empty, or not, are put right as it is filled.
 */
public final class VoxyTerrain {
	/** Grids kept for the sections above and below them, which share their columns. */
	private static final int CACHED_GRIDS = 128;
	private static final int SIDE = 32;
	/** A {@code markDirty} mask naming the six sections beside one: below, above, west, east, north and south. */
	private static final int ALL_NEIGHBOURS = 0b111111;

	private final ResourceKey<Level> level;
	/** Voxy's ids of the blocks in each biome, without their light. */
	private final Map<Holder<Biome>, Map<BlockState, Long>> ids = new ConcurrentHashMap<>();
	private final AtomicBoolean warned = new AtomicBoolean();
	private volatile @Nullable Source source;

	VoxyTerrain(ResourceKey<Level> level) {
		this.level = level;
	}

	/** Called when Voxy's store did not hold a section. The status Voxy's store reports for it. */
	int load(WorldEngine engine, WorldSection section) {
		int extent = SIDE << section.lvl;
		int minY = section.y * extent;
		if (minY > VoxyHeights.MAX_Y || minY + extent <= VoxyHeights.MIN_Y) {
			return VoxyLods.MISSING;
		}
		Source source = source();
		if (source == null) {
			return VoxyLods.MISSING;
		}
		boolean filled;
		int saving = source.saves() ? 0 : WorldEngine.UPDATE_TYPE_DONT_SAVE;
		try {
			// Nothing to fetch for a section under the level's lowest block.
			LodGrid grid = minY + extent <= Math.max(source.minY(), VoxyHeights.MIN_Y) ? null
				: source.grid(engine, section);
			filled = grid != null && fill(engine, grid, section, minY, null);
			if (filled && saving == 0 && engine.isLive()) {
				// Saved as Voxy lets go of it. Nothing else changes: it has not been drawn yet.
				engine.markDirty(section, 0, 0);
			}
		} catch (RuntimeException e) {
			// Most often the world closing under Voxy's threads.
			failed(e);
			return VoxyLods.MISSING;
		}
		repairParents(engine, section, saving);
		if (filled) {
			VoxyLods.FILLED.incrementAndGet();
		}
		return filled ? VoxyLods.LOADED : VoxyLods.MISSING;
	}

	/** Called as Voxy writes the chunk section at {@code (x, y, z)}, counted in chunk sections, into its world. */
	void writing(int x, int y, int z) {
		if (this.source instanceof Remote remote) {
			remote.writing(x, y, z);
		}
	}

	/**
	 * Fills a section whose grid came from a server after Voxy had asked for it, saves it, and has Voxy draw it again,
	 * and the six sections beside it, which were drawn with it empty: their faces towards it would show through its
	 * water. Voxy loads the section anew if it has let go of it meanwhile, and it is filled as it loads. The chunks the
	 * server sent that lie in the section, which Voxy wrote into it meanwhile ({@code written}, by {@link #cell}), are
	 * kept and only the rest filled: left empty, it would stand open beside them, a pit round the chunks.
	 */
	private void fillLate(WorldEngine engine, LodGrid grid, int lvl, int x, int y, int z, BitSet written) {
		if (!engine.isLive()) {
			return;
		}
		try {
			WorldSection section = engine.acquire(lvl, x, y, z);
			try {
				boolean held = !empty(section);
				boolean filled = false;
				// A section that holds blocks no chunk wrote was loaded anew and filled as it loaded.
				if (!held || !written.isEmpty()) {
					filled = fill(engine, grid, section, y * (SIDE << lvl), written);
				}
				if (filled) {
					repairParents(engine, section, 0);
					VoxyLods.FILLED.incrementAndGet();
				}
				if ((filled || held) && engine.isLive()) {
					int change = WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT;
					engine.markDirty(section, change, ALL_NEIGHBOURS);
				}
			} finally {
				section.release();
			}
		} catch (RuntimeException e) {
			if (engine.isLive()) {
				failed(e);
			}
		}
	}

	private void failed(RuntimeException e) {
		VoxyLods.FAILED.incrementAndGet();
		if (warned.compareAndSet(false, true)) {
			Diffusicraft.LOGGER.warn("Could not fill Voxy's far terrain", e);
		} else {
			Diffusicraft.LOGGER.debug("Could not fill Voxy's far terrain", e);
		}
	}

	/** Whether every voxel of a section is air. */
	private static boolean empty(WorldSection section) {
		for (long id : section._unsafeGetRawDataArray()) {
			if (!Mapper.isAir(id)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Where the grids come from now: the integrated server while one runs, else the server's feed, or null if neither
	 * has the level's far terrain.
	 */
	private @Nullable Source source() {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		Object owner;
		if (server != null) {
			if (!server.isRunning()) {
				return null;
			}
			owner = server;
		} else {
			owner = RemoteLods.feed();
			if (owner == null) {
				return null;
			}
		}
		Source source = this.source;
		if (source == null || source.owner != owner) {
			synchronized (this) {
				source = this.source;
				if (source == null || source.owner != owner) {
					source = owner instanceof MinecraftServer local ? Local.of(local, level)
						: new Remote((RemoteLods.Feed) owner);
					this.source = source;
				}
			}
		}
		return source.serves() ? source : null;
	}

	/**
	 * Writes the section's voxels from its grid, but for the cells of {@code written}, by {@link #cell}, which hold the
	 * chunks: with none, all of them. Whether any written holds a block.
	 */
	private boolean fill(WorldEngine engine, LodGrid grid, WorldSection section, int minY, @Nullable BitSet written) {
		int size = 1 << section.lvl;
		if (minY >= grid.highest()) {
			return false;
		}
		Mapper mapper = engine.getMapper();
		long[] data = section._unsafeGetRawDataArray();
		if (written != null && written.isEmpty()) {
			written = null;
		}
		BlockState[] states = new BlockState[SIDE];
		byte[] sky = new byte[SIDE];
		int blocks = 0;
		int claims = 0;
		for (int z = 0; z < SIDE; z++) {
			for (int x = 0; x < SIDE; x++) {
				int column = z * SIDE + x;
				grid.voxels(column, minY, size, states, sky);
				Holder<Biome> biome = grid.biome(column);
				Map<BlockState, Long> biomeIds = ids.computeIfAbsent(biome, b -> new ConcurrentHashMap<>());
				for (int y = 0; y < SIDE; y++) {
					if (written != null && written.get(cell(section.lvl, x, y, z))) {
						continue;
					}
					BlockState state = states[y];
					long id;
					if (state == null) {
						id = Mapper.airWithLight(sky[y]);
					} else {
						Long base = biomeIds.get(state);
						if (base == null) {
							// Two threads may both ask Voxy; they get the same id.
							base = mapper.getBaseId((byte) 0, state, biome);
							biomeIds.put(state, base);
						}
						id = Mapper.withLight(base, sky[y] | state.getLightEmission() << 4);
						blocks++;
						if (claims != 0xFF) {
							claims |= claims(x, y, z);
						}
					}
					data[WorldSection.getIndex(x, y, z)] = id;
				}
			}
		}
		if (blocks == 0) {
			return false;
		}
		if (section.lvl == 0) {
			section.addNonEmptyBlockCount(blocks);
			section.updateLvl0State();
		} else {
			section._unsafeSetNonEmptyChildren((byte) (section.getNonEmptyChildren() | claims));
		}
		return true;
	}

	/**
	 * The cell of voxel {@code (x, y, z)} in a section at level {@code lvl}. Voxy writes a chunk section into a section
	 * as one cell of its voxels, 16 wide at level 0 and half as wide at each level up, down to one voxel at level 4.
	 */
	static int cell(int lvl, int x, int y, int z) {
		int shift = 4 - lvl;
		return chunkCell(lvl, x >> shift, y >> shift, z >> shift);
	}

	/** The cell of the chunk section {@code (x, y, z)} chunk sections from a section's corner, at level {@code lvl}. */
	static int chunkCell(int lvl, int x, int y, int z) {
		int cells = 2 << lvl;
		return (y * cells + z) * cells + x;
	}

	/**
	 * The children a block at voxel {@code (x, y, z)} claims: its own, and those of the voxels above it and beside it,
	 * where the finer columns of a child may stand a little higher or wider than the coarse ones.
	 */
	static int claims(int x, int y, int z) {
		int claims = child(x, y, z) | child(x, Math.min(SIDE - 1, y + 1), z);
		claims |= child(Math.max(0, x - 1), y, z) | child(Math.min(SIDE - 1, x + 1), y, z);
		claims |= child(x, y, Math.max(0, z - 1)) | child(x, y, Math.min(SIDE - 1, z + 1));
		return claims;
	}

	/** The bit, among a section's children, of the child holding voxel {@code (x, y, z)}. */
	private static int child(int x, int y, int z) {
		// The same order as WorldSection.getChildIndex.
		return 1 << ((x >> 4) | (y >> 4) << 2 | (z >> 4) << 1);
	}

	/**
	 * Tells the parents of a section the store did not hold whether it holds blocks, as far up as that changes
	 * whether they do. Without this, Voxy's renderer would go down into the children a parent claimed that are empty,
	 * and never into one it did not claim. The change is saved as the section is, unless {@code saving} is
	 * {@link WorldEngine#UPDATE_TYPE_DONT_SAVE}, but never in a parent that holds no blocks: its own grid may not have
	 * come, and Voxy would read it back empty from its store instead of filling it.
	 */
	private static void repairParents(WorldEngine engine, WorldSection section, int saving) {
		WorldSection child = section;
		boolean acquired = false;
		while (child.lvl < WorldEngine.MAX_LOD_LAYER) {
			WorldSection parent = engine.acquire(child.lvl + 1, child.x >> 1, child.y >> 1, child.z >> 1);
			int change = parent.updateEmptyChildState(child);
			if (change != 0 && engine.isLive()) {
				int parentSaving = saving == 0 && empty(parent) ? WorldEngine.UPDATE_TYPE_DONT_SAVE : saving;
				engine.markDirty(parent, WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT | parentSaving, 0);
			}
			if (acquired) {
				child.release();
			}
			child = parent;
			acquired = true;
			// Only a parent that went from holding blocks to not, or back, changes its own parent.
			if (change != 2) {
				break;
			}
		}
		if (acquired) {
			child.release();
		}
	}

	/** Diffusicraft's additions to Voxy's {@code WorldEngine}. */
	public interface Carrier {
		@Nullable VoxyTerrain diffusicraft$terrain();

		void diffusicraft$setTerrain(VoxyTerrain terrain);
	}

	/** Where the grids of a level come from. */
	private abstract static class Source {
		/** The integrated server or the connection this source belongs to. */
		final Object owner;

		Source(Object owner) {
			this.owner = owner;
		}

		/** Whether the level's far terrain can be had from here. */
		abstract boolean serves();

		/** Lowest block Y of the level's grids. */
		abstract int minY();

		/** Whether the sections filled from here are saved in Voxy's store. */
		abstract boolean saves();

		/**
		 * The columns under a section, sampled once for the sections above and below it, or null if they cannot be
		 * had, or not yet: a section whose grid comes later is filled then.
		 */
		abstract @Nullable LodGrid grid(WorldEngine engine, WorldSection section);
	}

	/** The level's sampler in one run of the integrated server, and the grids last sampled. */
	private static final class Local extends Source {
		private final @Nullable LodSampler sampler;
		private final Map<GridKey, CompletableFuture<@Nullable LodGrid>> grids = new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<GridKey, CompletableFuture<@Nullable LodGrid>> eldest) {
				return size() > CACHED_GRIDS;
			}
		};

		private Local(MinecraftServer server, @Nullable LodSampler sampler) {
			super(server);
			this.sampler = sampler;
		}

		static Local of(MinecraftServer server, ResourceKey<Level> level) {
			ServerLevel serverLevel = server.getLevel(level);
			return new Local(server, serverLevel == null ? null : LodSampler.of(serverLevel));
		}

		@Override
		boolean serves() {
			return sampler != null;
		}

		@Override
		int minY() {
			return sampler.minY();
		}

		/** Not saved: the world cuts them again at will, and a newer Diffusicraft may cut them otherwise. */
		@Override
		boolean saves() {
			return false;
		}

		/** Samples the grid on the calling thread. On the render thread, only a grid already at hand: it can take long. */
		@Override
		@Nullable LodGrid grid(WorldEngine engine, WorldSection section) {
			GridKey key = new GridKey(section.lvl, section.x, section.z);
			boolean renderThread = Minecraft.getInstance().isSameThread();
			CompletableFuture<@Nullable LodGrid> future;
			boolean mine = false;
			synchronized (grids) {
				future = grids.get(key);
				if (future == null) {
					if (renderThread) {
						return null;
					}
					future = new CompletableFuture<>();
					grids.put(key, future);
					mine = true;
				}
			}
			if (!mine) {
				return renderThread ? future.getNow(null) : future.join();
			}
			LodGrid grid;
			try {
				int extent = SIDE << key.lvl;
				grid = LodGrid.sample(sampler, key.x * extent, key.z * extent, SIDE, 1 << key.lvl,
					VoxyHeights.MAX_Y + 1);
			} catch (RuntimeException | Error e) {
				forget(key, future);
				future.completeExceptionally(e);
				throw e;
			}
			future.complete(grid);
			return grid;
		}

		private void forget(GridKey key, CompletableFuture<@Nullable LodGrid> future) {
			synchronized (grids) {
				grids.remove(key, future);
			}
		}
	}

	/**
	 * The far terrain a server sends for the level, over one connection, and the grids last sent. A section whose
	 * grid has not come is filled when it comes, on a thread of Diffusicraft's.
	 */
	private final class Remote extends Source {
		private final RemoteLods.Feed feed;
		/** Guards {@link #asked} too. */
		private final Map<GridKey, LodGrid> grids = new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<GridKey, LodGrid> eldest) {
				return size() > CACHED_GRIDS;
			}
		};
		/**
		 * The grids asked for that have not come, with the sections waiting for each, by their Y, and the cells of each
		 * that Voxy has written the chunks into while it waited.
		 */
		private final Map<GridKey, Map<Integer, BitSet>> asked = new HashMap<>();

		private Remote(RemoteLods.Feed feed) {
			super(feed);
			this.feed = feed;
		}

		@Override
		boolean serves() {
			return feed.serves(level);
		}

		@Override
		int minY() {
			return feed.minY(level);
		}

		@Override
		boolean saves() {
			return true;
		}

		@Override
		@Nullable LodGrid grid(WorldEngine engine, WorldSection section) {
			GridKey key = new GridKey(section.lvl, section.x, section.z);
			synchronized (grids) {
				LodGrid grid = grids.get(key);
				if (grid != null) {
					return grid;
				}
				// Loaded anew, it holds no chunk: Voxy saves a section once it writes one into it.
				Map<Integer, BitSet> waiting = asked.get(key);
				if (waiting != null) {
					waiting.put(section.y, new BitSet());
					return null;
				}
				waiting = new HashMap<>();
				waiting.put(section.y, new BitSet());
				asked.put(key, waiting);
			}
			int extent = SIDE << key.lvl;
			feed.fetch(level, key.x * extent, key.z * extent, SIDE, 1 << key.lvl, VoxyHeights.MAX_Y + 1)
				.whenCompleteAsync((grid, error) -> arrived(engine, key, error == null ? grid : null),
					LateFills.EXECUTOR);
			return null;
		}

		/**
		 * Fills the sections that were waiting for a grid. With no grid, they stay as they are, empty but for the chunks,
		 * until Voxy loads them again. They wait until they are filled, so that the chunks Voxy writes into them
		 * meanwhile are kept.
		 */
		private void arrived(WorldEngine engine, GridKey key, @Nullable LodGrid grid) {
			Map<Integer, BitSet> waiting;
			List<Integer> ys;
			synchronized (grids) {
				waiting = grid == null ? asked.remove(key) : asked.get(key);
				if (grid == null || waiting == null) {
					return;
				}
				grids.put(key, grid);
				ys = List.copyOf(waiting.keySet());
			}
			try {
				for (int y : ys) {
					BitSet written;
					synchronized (grids) {
						written = (BitSet) waiting.get(y).clone();
					}
					fillLate(engine, grid, key.lvl, key.x, y, key.z, written);
				}
			} finally {
				synchronized (grids) {
					asked.remove(key);
				}
			}
		}

		/** Marks the cell of the chunk section at {@code (x, y, z)} written in every section waiting that holds it. */
		void writing(int x, int y, int z) {
			synchronized (grids) {
				if (asked.isEmpty()) {
					return;
				}
				for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
					int shift = lvl + 1;
					Map<Integer, BitSet> waiting = asked.get(new GridKey(lvl, x >> shift, z >> shift));
					BitSet written = waiting == null ? null : waiting.get(y >> shift);
					if (written != null) {
						int mask = (1 << shift) - 1;
						written.set(chunkCell(lvl, x & mask, y & mask, z & mask));
					}
				}
			}
		}
	}

	/** The thread filling the sections whose grids came from a server after Voxy had asked for them. */
	private static final class LateFills {
		static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "Diffusicraft far terrain");
			thread.setDaemon(true);
			return thread;
		});
	}

	private record GridKey(int lvl, int x, int z) {
	}
}
