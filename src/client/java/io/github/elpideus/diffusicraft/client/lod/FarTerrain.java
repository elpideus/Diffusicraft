package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.client.compat.iris.IrisFarTerrain;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import io.github.elpideus.diffusicraft.worldgen.lod.LodTiles;
import io.github.elpideus.diffusicraft.worldgen.lod.LodView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The far terrain Diffusicraft draws itself past the chunks of its worlds, as Distant Horizons and Voxy draw theirs:
 * tiles of {@link LodTiles}, coarser the further they lie, meshed by {@link LodMesher} on worker threads and drawn in
 * the world's own solid pass, so that it shares the depth buffer, the fog and the light of the chunks, and shader packs
 * can take it over. The grids come from the integrated server's generator, with the chunks players changed laid over
 * them ({@link LodGrid#sample}), or from the server's feed ({@link RemoteLods}); tiles whose stored chunks change are
 * built again. Where the world draws a chunk itself, {@link ChunkMask} leaves the far terrain out. Under a shader pack,
 * {@link IrisFarTerrain} draws the tiles with the pack's programs for Distant Horizons instead, and the pack leaves out
 * the chunks.
 *
 * <p>Off where Distant Horizons or Voxy is installed, which draw their own. Driven from the render thread.
 *
 * @see io.github.elpideus.diffusicraft.worldgen.lod.LodMesher
 */
public final class FarTerrain {
	/** How far the camera moves before the tiles are chosen again, in blocks. */
	private static final double MOVE = 16;
	/** How often the tiles are chosen again while the camera stays, so that those that failed are tried again. */
	private static final long RECHOOSE_MILLIS = 2000;
	/** Columns of a tile's side that lie within a block of the tile closer than this many steps split. */
	private static final int DETAIL = 128;
	private static final int BUILDS_IN_FLIGHT = 6;
	/** Most bytes of vertices uploaded in a frame, so that a burst of tiles does not stall it. */
	private static final int UPLOAD_BUDGET = 4 << 20;
	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, Thread.ofPlatform()
		.name("Diffusicraft far terrain ", 0).daemon().priority(Thread.MIN_PRIORITY).factory());
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	/** Whether the far terrain can be drawn at all: Distant Horizons and Voxy draw their own. */
	static final boolean ENABLED = !FabricLoader.getInstance().isModLoaded("distanthorizons")
		&& !FabricLoader.getInstance().isModLoaded("voxy");
	private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

	private static @Nullable FarTerrain current;
	/** Set once a pipeline of the far terrain fails to compile: it stays off until the game restarts. */
	private static boolean broken;
	private static volatile long uploads;

	private final ClientLevel level;
	private final ResourceKey<Level> dimension;
	/** How far the far terrain reaches, in blocks: the configuration's, taken in as the tiles are chosen again. */
	private int far;
	private final int bottom;
	private final int ceiling;
	private final LodColors colors = LodColors.current();
	private final FarMaterials materials = new FarMaterials();
	private final LodView<FarMesh> view = new LodView<>(FarMesh::close);
	private final LodTiles.Chooser chooser = new LodTiles.Chooser();
	private final ChunkMask mask = new ChunkMask();
	private final Queue<Built> built = new ConcurrentLinkedQueue<>();
	private final Queue<LodTiles.Tile> changed = new ConcurrentLinkedQueue<>();
	private final Queue<Opened> opened = new ConcurrentLinkedQueue<>();
	private final Set<LodTiles.Tile> building = new HashSet<>();
	/** Tiles being built whose terrain changed meanwhile, to be built again once they are. */
	private final Set<LodTiles.Tile> stale = new HashSet<>();
	private final Set<LodTiles.Tile> rebuild = new LinkedHashSet<>();
	private final ArrayDeque<LodTiles.Tile> missing = new ArrayDeque<>();
	/** The tiles drawn in this frame, nearest first. */
	private final List<FarMesh> drawn = new ArrayList<>();
	private @Nullable GpuBufferSlice terrainTransform;
	private int maxQuads;
	private @Nullable Vec3 camera;
	/** Whether a shader pack draws the far terrain in this frame, rather than the world's solid pass. */
	private boolean shaders;
	private @Nullable Source source;
	private @Nullable Object opening;
	private int generation;
	private double chosenX = Double.NaN;
	private double chosenZ = Double.NaN;
	private int chosenNear = -1;
	private long chosen;
	private volatile boolean closed;

	private FarTerrain(ClientLevel level) {
		this.level = level;
		this.dimension = level.dimension();
		this.far = Resources.get().config().farTerrainDistance;
		this.bottom = level.getMinY();
		this.ceiling = level.getMaxY() + 1;
	}

	/**
	 * Lets go of the far terrain as the player leaves a world, and builds it again after the resource packs change, which
	 * changes its colours.
	 */
	public static void init() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(FarTerrain::reset));
		Identifier reloader = Diffusicraft.id("far_terrain");
		ResourceLoader resources = ResourceLoader.get(PackType.CLIENT_RESOURCES);
		resources.registerReloadListener(reloader, (ResourceManagerReloadListener) manager -> reset());
		resources.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, reloader);
	}

	private static void reset() {
		if (current != null) {
			current.close();
			current = null;
		}
	}

	/**
	 * How far the far terrain reaches now, in blocks, or 0 while there is none to draw, so that the fog and the depth
	 * range of the camera reach out as far as it does. On the render thread.
	 */
	public static int reach() {
		FarTerrain terrain = current;
		return terrain != null && terrain.source != null && terrain.source.serves() ? terrain.far : 0;
	}

	/** Whether the far terrain is to be drawn: it is turned on, and nothing turned it off. */
	public static boolean configured() {
		return ENABLED && !broken && Resources.get().config().farTerrain;
	}

	/** The lowest block of the level the far terrain is drawn for, which its vertices start from. */
	public static int bottom() {
		FarTerrain terrain = current;
		return terrain != null ? terrain.bottom : 0;
	}

	/** The most quads of land or of water a tile drawn in this frame has, which the index buffer holds. */
	public static int drawnQuads() {
		FarTerrain terrain = current;
		return terrain != null && !terrain.drawn.isEmpty() ? terrain.maxQuads : 0;
	}

	/** A tile of far terrain to draw. */
	public interface Tiles {
		/**
		 * Draws {@code quads} quads of {@code vertices} from vertex {@code firstVertex}, each vertex
		 * {@link FarTerrainPipelines#VERTEX_SIZE} bytes, whose positions lie {@code (x, y, z)} from the camera.
		 */
		void tile(GpuBuffer vertices, int firstVertex, int quads, float x, float y, float z);
	}

	/**
	 * Hands {@code tiles} the tiles in view in this frame that have land, nearest first, or, if {@code water}, water,
	 * furthest first, for a shader pack to draw. On the render thread.
	 */
	public static void forEachDrawn(boolean water, Tiles tiles) {
		FarTerrain terrain = current;
		if (terrain == null || terrain.camera == null) {
			return;
		}
		Vec3 camera = terrain.camera;
		for (FarMesh mesh : water ? terrain.drawn.reversed() : terrain.drawn) {
			int quads = water ? mesh.waterQuads : mesh.solidQuads;
			if (quads > 0 && mesh.buffer != null) {
				LodTiles.Tile tile = mesh.tile;
				tiles.tile(mesh.buffer, water ? mesh.solidQuads * 4 : 0, quads, (float) (tile.minBlockX() - camera.x),
					(float) (terrain.bottom - camera.y), (float) (tile.minBlockZ() - camera.z));
			}
		}
	}

	/** How many tiles of far terrain have been built and uploaded since the game started. */
	public static long uploads() {
		return uploads;
	}

	/**
	 * Whether the far terrain has built every tile it wants for where the camera is, and none waits to be built again.
	 * On the render thread.
	 */
	public static boolean settled() {
		FarTerrain terrain = current;
		return terrain != null && terrain.source != null && terrain.building.isEmpty() && terrain.missing.isEmpty()
			&& terrain.rebuild.isEmpty() && terrain.built.isEmpty();
	}

	/**
	 * Gets the far terrain ready to draw in this frame: takes in the tiles built, chooses those to build, and writes
	 * the uniforms. Called as the world's frame is put together, before any of its passes runs.
	 *
	 * @param viewArea the grid of vanilla's chunk meshes, or null where another mod builds them
	 */
	public static void prepare(CameraRenderState camera, @Nullable ViewArea viewArea, int viewDistance) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || !configured()) {
			reset();
			return;
		}
		if (current == null || current.level != level) {
			reset();
			current = new FarTerrain(level);
		}
		current.frame(camera, viewArea, viewDistance);
	}

	/** Draws the far terrain into the world's solid pass, after the chunks. */
	public static void draw(RenderPass pass) {
		if (current != null && !broken) {
			current.render(pass);
		}
	}

	private void frame(CameraRenderState camera, @Nullable ViewArea viewArea, int viewDistance) {
		drawn.clear();
		updateSource();
		Source source = this.source;
		if (source == null || !source.serves()) {
			return;
		}
		takeChanges();
		takeBuilt();
		Vec3 at = camera.pos;
		this.camera = at;
		shaders = IRIS && IrisFarTerrain.drawsInstead();
		// Tiles all the way in, for whatever the world does not draw near the camera: sections still waiting for their
		// mesh, and those above and below the rows it draws. A shader pack leaves out the chunks' reach itself.
		int near = shaders ? Math.max(0, (viewDistance - 1) * 16 - 16) : 0;
		int far = Resources.get().config().farTerrainDistance;
		long now = Util.getMillis();
		if (Double.isNaN(chosenX) || Math.abs(at.x - chosenX) > MOVE || Math.abs(at.z - chosenZ) > MOVE
			|| near != chosenNear || far != this.far || now - chosen > RECHOOSE_MILLIS) {
			this.far = far;
			choose(at.x, at.z, near);
			chosen = now;
		}
		dispatch(source);
		maxQuads = 0;
		for (FarMesh mesh : view.drawn()) {
			LodTiles.Tile tile = mesh.tile;
			if (mesh.buffer != null && camera.cullFrustum.isVisible(new AABB(tile.minBlockX(), mesh.minY, tile.minBlockZ(),
				tile.minBlockX() + tile.size(), mesh.maxY, tile.minBlockZ() + tile.size()))) {
				drawn.add(mesh);
				maxQuads = Math.max(maxQuads, Math.max(mesh.solidQuads, mesh.waterQuads));
			}
		}
		if (drawn.isEmpty()) {
			return;
		}
		// Near land hides what lies behind it before it is shaded; water blends over what is drawn behind it.
		drawn.sort(Comparator.comparingDouble(mesh -> LodTiles.distance(mesh.tile, at.x, at.z)));
		RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxQuads * 6);
		if (shaders) {
			// The pack's programs read neither, and leave out the chunks themselves.
			return;
		}
		// Not DynamicGpuData.writeChunkSections, which would close the instanced sections vanilla wrote this frame.
		terrainTransform = RenderSystem.getDynamicUniforms().writeTerrainTransform(new Matrix4f(camera.viewRotationMatrix),
			1, 1);
		mask.update(level, viewArea, viewDistance, camera.blockPos);
	}

	private void render(RenderPass pass) {
		if (shaders || drawn.isEmpty() || terrainTransform == null || mask.view() == null) {
			return;
		}
		CompiledRenderPipeline solid = pipeline(FarTerrainPipelines.SOLID);
		CompiledRenderPipeline water = pipeline(FarTerrainPipelines.WATER);
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		// The index buffer grows to what was asked for as the pass begins, so it is short only on the first frame.
		if (solid == null || water == null || !indices.hasStorage(maxQuads * 6)) {
			return;
		}
		pass.pushDebugGroup(() -> "Diffusicraft far terrain");
		pass.setUniform("TerrainUniform", terrainTransform);
		pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.setUniform("FarTerrainInfo", mask.info());
		pass.setUniform("ChunkMask", mask.view(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		List<RenderPass.Draw<List<FarMesh>>> solidDraws = new ArrayList<>(drawn.size());
		List<RenderPass.Draw<List<FarMesh>>> waterDraws = new ArrayList<>();
		for (int i = 0; i < drawn.size(); i++) {
			FarMesh mesh = drawn.get(i);
			int index = i;
			if (mesh.solidQuads > 0) {
				solidDraws.add(new RenderPass.Draw<>(0, mesh.buffer, null, null, 0, mesh.solidQuads * 6, 0,
					(meshes, uploader) -> uploader.setUniform("ChunkSection", meshes.get(index).section.slice())));
			}
		}
		for (int i = drawn.size() - 1; i >= 0; i--) {
			FarMesh mesh = drawn.get(i);
			int index = i;
			if (mesh.waterQuads > 0) {
				waterDraws.add(new RenderPass.Draw<>(0, mesh.buffer, null, null, 0, mesh.waterQuads * 6,
					mesh.solidQuads * 4,
					(meshes, uploader) -> uploader.setUniform("ChunkSection", meshes.get(index).section.slice())));
			}
		}
		List<String> dynamic = List.of("ChunkSection");
		if (!solidDraws.isEmpty()) {
			pass.setPipeline(solid);
			pass.drawMultipleIndexed(solidDraws, indices.getBuffer(), indices.type(), dynamic, drawn);
		}
		if (!waterDraws.isEmpty()) {
			pass.setPipeline(water);
			pass.drawMultipleIndexed(waterDraws, indices.getBuffer(), indices.type(), dynamic, drawn);
		}
		pass.popDebugGroup();
	}

	private static @Nullable CompiledRenderPipeline pipeline(RenderPipeline pipeline) {
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
		if (compiled == null) {
			Diffusicraft.LOGGER.error("The far terrain's pipeline {} did not compile; turning the far terrain off",
				pipeline.getLocation());
			broken = true;
		}
		return compiled;
	}

	/** Follows the integrated server, or the server's feed, as they come and go. */
	private void updateSource() {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		Object owner = server != null ? server.isRunning() ? server : null : RemoteLods.feed();
		for (Opened open; (open = opened.poll()) != null; ) {
			if (open.server == owner && open.server == opening) {
				opening = null;
				setSource(new Local(open.server, open.sampler));
			}
		}
		if (owner == null) {
			opening = null;
			setSource(null);
			return;
		}
		if (source != null && source.owner == owner || owner == opening) {
			return;
		}
		if (owner instanceof MinecraftServer local) {
			// Opening the sampler reads the world's generator, which may take a moment: not on the render thread.
			opening = local;
			setSource(null);
			WORKERS.execute(() -> {
				LodSampler sampler = null;
				try {
					ServerLevel serverLevel = local.getLevel(dimension);
					sampler = serverLevel == null ? null : LodSampler.of(serverLevel);
				} catch (RuntimeException e) {
					failed(e);
				}
				opened.add(new Opened(local, sampler));
			});
		} else {
			setSource(new Remote((RemoteLods.Feed) owner, dimension));
		}
	}

	private void setSource(@Nullable Source next) {
		if (source == next) {
			return;
		}
		if (source != null) {
			source.close();
		}
		source = next;
		generation++;
		view.clear();
		building.clear();
		stale.clear();
		rebuild.clear();
		missing.clear();
		chooser.reset();
		chosenX = Double.NaN;
	}

	/** Builds again the tiles whose stored chunks changed. */
	private void takeChanges() {
		for (LodTiles.Tile tile; (tile = changed.poll()) != null; ) {
			if (building.contains(tile)) {
				stale.add(tile);
			} else if (view.wants(tile)) {
				rebuild.add(tile);
			}
		}
	}

	/** Uploads the tiles built, as far as the budget of the frame goes. */
	private void takeBuilt() {
		int uploaded = 0;
		for (Built done; uploaded < UPLOAD_BUDGET && (done = built.poll()) != null; ) {
			if (done.generation != generation) {
				free(done);
				continue;
			}
			building.remove(done.tile);
			if (stale.remove(done.tile)) {
				rebuild.add(done.tile);
			}
			if (done.data == null) {
				continue;
			}
			if (!view.wants(done.tile)) {
				done.data.free();
				continue;
			}
			uploaded += done.data.bytes();
			view.built(done.tile, done.data.upload(bottom));
			uploads++;
		}
	}

	private void choose(double x, double z, int near) {
		chosenX = x;
		chosenZ = z;
		chosenNear = near;
		List<LodTiles.Tile> tiles = chooser.around(x, z, near, far, DETAIL);
		tiles.sort(Comparator.comparingDouble(tile -> LodTiles.distance(tile, x, z)));
		missing.clear();
		missing.addAll(view.want(tiles));
	}

	private void dispatch(Source source) {
		while (building.size() < BUILDS_IN_FLIGHT) {
			LodTiles.Tile tile = next();
			if (tile == null) {
				return;
			}
			building.add(tile);
			int generation = this.generation;
			source.grid(tile, ceiling)
				.thenApplyAsync(grid -> grid == null ? null : FarMeshWriter.write(tile, grid, bottom, colors, materials),
					WORKERS)
				.whenComplete((data, error) -> {
					if (error != null) {
						failed(error);
					}
					finished(new Built(generation, tile, error == null ? data : null));
				});
		}
	}

	/** The next tile to build: those whose terrain changed first, then those missing, nearest first. */
	private LodTiles.@Nullable Tile next() {
		while (!rebuild.isEmpty()) {
			LodTiles.Tile tile = rebuild.iterator().next();
			rebuild.remove(tile);
			if (view.wants(tile) && !building.contains(tile)) {
				return tile;
			}
		}
		for (LodTiles.Tile tile; (tile = missing.poll()) != null; ) {
			if (view.wants(tile) && !building.contains(tile)) {
				return tile;
			}
		}
		return null;
	}

	private void finished(Built done) {
		built.add(done);
		// Closed meanwhile: nobody takes it any more.
		if (closed) {
			for (Built left; (left = built.poll()) != null; ) {
				free(left);
			}
		}
	}

	private static void free(Built done) {
		if (done.data != null) {
			done.data.free();
		}
	}

	private static void failed(Throwable error) {
		if (WARNED.compareAndSet(false, true)) {
			Diffusicraft.LOGGER.warn("Could not build the far terrain", error);
		} else {
			Diffusicraft.LOGGER.debug("Could not build the far terrain", error);
		}
	}

	private void close() {
		closed = true;
		if (source != null) {
			source.close();
			source = null;
		}
		view.clear();
		mask.close();
		for (Built left; (left = built.poll()) != null; ) {
			free(left);
		}
	}

	private record Built(int generation, LodTiles.Tile tile, FarMesh.@Nullable Data data) {
	}

	private record Opened(MinecraftServer server, @Nullable LodSampler sampler) {
	}

	/** Where the grids come from. */
	private abstract static class Source {
		final Object owner;

		Source(Object owner) {
			this.owner = owner;
		}

		abstract boolean serves();

		/** The grid of a tile, or null if there is none to be had. */
		abstract CompletableFuture<@Nullable LodGrid> grid(LodTiles.Tile tile, int ceiling);

		void close() {
		}
	}

	/** The integrated server's world: sampled on the workers, and built again as players change it. */
	private final class Local extends Source {
		private final @Nullable LodSampler sampler;
		private final @Nullable LodStore store;
		private final LodStore.Listener listener = (level, x, z) -> changed.add(new LodTiles.Tile(level, x, z));

		Local(MinecraftServer server, @Nullable LodSampler sampler) {
			super(server);
			this.sampler = sampler;
			this.store = sampler == null ? null : sampler.store();
			if (store != null) {
				store.listen(listener);
			}
		}

		@Override
		boolean serves() {
			return sampler != null;
		}

		@Override
		CompletableFuture<@Nullable LodGrid> grid(LodTiles.Tile tile, int ceiling) {
			MinecraftServer server = (MinecraftServer) owner;
			return CompletableFuture.supplyAsync(() -> {
				try {
					return LodGrid.sample(sampler, tile.minBlockX(), tile.minBlockZ(), LodStore.PAGE, tile.step(),
						ceiling);
				} catch (IllegalStateException e) {
					// The world closed under the tile, and its terrain with it: nobody wants the tile any more.
					if (!server.isRunning()) {
						return null;
					}
					throw e;
				}
			}, WORKERS);
		}

		@Override
		void close() {
			if (store != null) {
				store.unlisten(listener);
			}
		}
	}

	/**
	 * The far terrain the server sends. Where the server does not share what players built, it is the terrain as
	 * generated, and the chunks this client saw itself are laid over it ({@link SeenChunks}).
	 */
	private final class Remote extends Source {
		/** Grids the server sent that are kept to lay the seen chunks over again as they change. */
		private static final int KEPT_GRIDS = 16;

		private final RemoteLods.Feed feed;
		private final ResourceKey<Level> dimension;
		private final @Nullable LodStore seen;
		private final LodStore.Listener listener = (level, x, z) -> changed.add(new LodTiles.Tile(level, x, z));
		/** Guarded by itself. */
		private final Map<Sent, LodGrid> sent = new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Sent, LodGrid> eldest) {
				return size() > KEPT_GRIDS;
			}
		};

		Remote(RemoteLods.Feed feed, ResourceKey<Level> dimension) {
			super(feed);
			this.feed = feed;
			this.dimension = dimension;
			this.seen = feed.sharesBuilds() ? null : SeenChunks.store(feed, dimension);
			if (seen != null) {
				seen.listen(listener);
			} else {
				feed.listen(dimension, listener);
			}
		}

		@Override
		boolean serves() {
			return feed.serves(dimension);
		}

		@Override
		CompletableFuture<@Nullable LodGrid> grid(LodTiles.Tile tile, int ceiling) {
			LodStore seen = this.seen;
			if (seen == null) {
				return feed.fetch(dimension, tile.minBlockX(), tile.minBlockZ(), LodStore.PAGE, tile.step(), ceiling);
			}
			Sent key = new Sent(tile, ceiling);
			LodGrid kept;
			synchronized (sent) {
				kept = sent.get(key);
			}
			CompletableFuture<@Nullable LodGrid> fetched = kept != null ? CompletableFuture.completedFuture(kept)
				: feed.fetch(dimension, tile.minBlockX(), tile.minBlockZ(), LodStore.PAGE, tile.step(), ceiling)
					.thenApply(grid -> {
						if (grid != null) {
							synchronized (sent) {
								sent.put(key, grid);
							}
						}
						return grid;
					});
			return fetched.thenApplyAsync(grid -> {
				if (grid == null) {
					return null;
				}
				try {
					return seen.overlay(tile.minBlockX(), tile.minBlockZ(), LodStore.PAGE, tile.step(), ceiling,
						() -> grid);
				} catch (RejectedExecutionException e) {
					// The client left the server, and the store closed under the tile: nobody wants it any more.
					return null;
				}
			}, WORKERS);
		}

		@Override
		void close() {
			if (seen != null) {
				seen.unlisten(listener);
			} else {
				feed.unlisten(listener);
			}
		}
	}

	/** A grid the server sent: of a tile, with its ceiling. */
	private record Sent(LodTiles.Tile tile, int ceiling) {
	}
}
