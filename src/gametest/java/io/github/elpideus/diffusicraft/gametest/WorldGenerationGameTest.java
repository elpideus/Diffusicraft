package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.lod.LodColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.mixin.client.gametest.ClientLevelAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Creates a Diffusicraft world with the default settings and checks that the overworld really uses Diffusicraft.
 * Saves to {@code build/run/clientGameTest/screenshots}: a biome map and a height map around the spawn, sampled from
 * the generator without building chunks, in-game views from above the spawn, over the largest river nearby and of each
 * of Diffusicraft's biomes on the map, and views inside the nearest Grand Hollow and over the nearest lava sea of a Deep
 * Grand Hollow. It also checks the levels of detail Distant Horizons draws against the chunks generated around the
 * spawn, and saves maps of them near and far.
 *
 * <p>Needs the Terrain Diffusion models; without them the test logs a warning and does nothing. Generation runs on the
 * CPU, so the test leaves the GPU alone; pass {@code -Pdiffusicraft.device=CUDA} (or {@code AUTO}) to change that.
 */
public class WorldGenerationGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft world test");
	private static final String SEED = "20261018";
	/** Map size in pixels, and blocks per pixel. */
	private static final int MAP_SIZE = 256;
	private static final int MAP_STRIDE = 8;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 20;
	private static final int VIEW_TIMEOUT_TICKS = 5 * TICKS_PER_MINUTE;
	/** Columns per side of the LOD grids, and blocks between their columns near and far. */
	private static final int LOD_WIDTH = 64;
	private static final int LOD_NEAR_STEP = 8;
	/** Spacing of the LOD columns whose tree crowns are counted by biome, which spans several biomes. */
	private static final int LOD_CANOPY_STEP = 16;
	private static final int LOD_FAR_STEP = 256;
	/** How far the share of the LOD's columns under a tree crown may be from the chunks'. */
	private static final double LOD_CROWN_TOLERANCE = 0.2;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the world generation test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);
		LOGGER.info("Generating on {}", device);

		// A fresh Create World screen selects Diffusicraft.
		context.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		context.waitForScreen(CreateWorldScreen.class);
		String defaultType = context.computeOnClient(mc -> describe(((CreateWorldScreen) mc.gui.screen()).getUiState()));
		if (!defaultType.equals(DiffusicraftWorldgen.STANDARD_PRESET.identifier().toString())) {
			throw new AssertionError("The default world type is " + defaultType);
		}
		context.setScreen(TitleScreen::new);

		long start = System.nanoTime();
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			LOGGER.info("World loaded in {} s", (System.nanoTime() - start) / 1_000_000_000L);

			Probe probe = world.getServer().computeOnServer(server -> Probe.of(server.overworld()));
			LOGGER.info("Spawn {} at surface y={}, biome {}", probe.spawn, probe.spawnSurface, probe.spawnBiome);
			Map<String, BlockPos> diffusicraftBiomes = saveMaps(probe);
			findFarBiomes(probe, diffusicraftBiomes);

			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			BlockPos above = probe.spawn.atY(probe.spawnSurface + 40);
			view(context, world, above, 0f, 20f, "view-south");
			// Distant Horizons' LODs, against the chunks just generated around the spawn. A failure waits for the
			// views to be taken.
			String lodProblem = checkLods(world, probe, probe.spawn);
			view(context, world, above, 180f, 20f, "view-north");
			view(context, world, above.above(120), 90f, 40f, "view-high-west");

			// The largest river nearby, from its bank and from high above.
			BlockPos river = findRiver(probe);
			view(context, world, river.offset(0, 24, 36), 180f, 30f, "river");
			view(context, world, river.above(140), 180f, 75f, "river-above");

			// Each of Diffusicraft's biomes, from 45 blocks north of it.
			for (Map.Entry<String, BlockPos> biome : diffusicraftBiomes.entrySet()) {
				BlockPos at = biome.getValue(), camera = at.north(45);
				int y = Math.max(surface(probe, at), surface(probe, camera)) + 30;
				view(context, world, camera.atY(y), 0f, 30f, "biome-" + biome.getKey().substring(biome.getKey().indexOf(':') + 1));
			}

			// The camera in the middle of the tallest Grand Hollow nearby, first by its own light, then lit up.
			BlockPos grand = findHollow(probe, EnumSet.of(Hollows.Kind.GRAND_HOLLOW, Hollows.Kind.ANCIENT_GRAND_HOLLOW),
				false);
			view(context, world, grand, 0f, 0f, "hollow-grand");
			world.getServer().runCommand("effect give @a minecraft:night_vision infinite 0 true");
			view(context, world, grand, 0f, 0f, "hollow-grand-lit");
			view(context, world, grand, 180f, 0f, "hollow-grand-lit-north");
			BlockPos sea = findHollow(probe, EnumSet.of(Hollows.Kind.DEEP_GRAND_HOLLOW), true);
			view(context, world, sea, 0f, 25f, "hollow-lava-sea");
			if (lodProblem != null) {
				throw new AssertionError(lodProblem);
			}
		}
	}

	private static String describe(WorldCreationUiState ui) {
		Holder<?> preset = ui.getWorldType().preset();
		return preset == null ? "custom" : preset.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
	}

	/** Teleports the player, waits for the chunks and takes a screenshot. */
	private static void view(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at, float yaw,
							 float pitch, String name) {
		world.getServer().runCommand(String.format("tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(), at.getZ(), yaw, pitch));
		world.getConnection().waitForClientboundPackets();
		long start = System.nanoTime();
		try {
			context.waitFor(mc -> pendingWork(mc).isEmpty(), VIEW_TIMEOUT_TICKS);
			LOGGER.info("Chunks for {} ready in {} s", name, (System.nanoTime() - start) / 1_000_000_000L);
		} catch (AssertionError e) {
			// On the CPU next to a busy desktop the far chunks can take longer than any sensible timeout. A view with
			// a few missing chunks still shows the terrain, so take it anyway.
			LOGGER.warn("Chunks for {} not all rendered after {} s ({}); taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L, context.computeOnClient(WorldGenerationGameTest::pendingWork));
		}
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-world-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}

	/**
	 * What the client still has to do before the view is complete, or an empty string. Like Fabric's
	 * {@code waitForChunksRender}, but it only waits for the chunks the server sends: those in a circle around the
	 * player. Fabric's waits for a square, whose corners never arrive at a render distance above 6.
	 */
	private static String pendingWork(Minecraft mc) {
		int distance = mc.options.getEffectiveRenderDistance();
		ChunkPos center = mc.player.chunkPosition();
		List<String> missing = new ArrayList<>();
		for (int dz = -distance; dz <= distance; dz++) {
			for (int dx = -distance; dx <= distance; dx++) {
				if (ChunkTrackingView.isInViewDistance(0, 0, distance, dx, dz)
					&& mc.level.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false) == null) {
					missing.add(dx + "," + dz);
				}
			}
		}
		List<String> pending = new ArrayList<>();
		if (!missing.isEmpty()) {
			pending.add(missing.size() + " chunks missing, at " + missing.subList(0, Math.min(8, missing.size())));
		}
		if (!((ClientLevelAccessor) mc.level).getLightUpdateQueue().isEmpty()) {
			pending.add("light updates queued");
		}
		if (!mc.levelRenderer.hasRenderedAllSections()) {
			pending.add("sections still compiling");
		}
		return String.join("; ", pending);
	}

	/**
	 * Finds the column within 2 km of the spawn with the longest open run of the given kinds of hollow, and returns
	 * the middle of that run, or with {@code overLava} a point 12 blocks above the lava level.
	 */
	private static BlockPos findHollow(Probe probe, Set<Hollows.Kind> kinds, boolean overLava) {
		Hollows hollows = Hollows.of(probe.hollowsSeed, probe.minY);
		Hollows.Column column = new Hollows.Column();
		int lava = probe.minY + 10;
		int bestRun = 0;
		BlockPos best = null;
		for (int dx = -2048; dx <= 2048; dx += 48) {
			for (int dz = -2048; dz <= 2048; dz += 48) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				hollows.column(x, z, (px, pz) -> (int) Math.floor(probe.state.sampleBlockValueUncached(
					probe.router.chunkSurfaceLevel(), px, 0, pz)), column);
				if (column.solid()) {
					continue;
				}
				int run = 0;
				for (int y = (int) column.bottom(); y <= (int) column.top() + 1; y++) {
					Hollows.Kind kind = hollows.openKind(column, y);
					if (kind != null && kinds.contains(kind) && (!overLava || run > 0 || y <= lava)) {
						run++;
						continue;
					}
					if (run > bestRun) {
						bestRun = run;
						best = new BlockPos(x, overLava ? lava + 12 : y - run / 2, z);
					}
					run = 0;
				}
			}
		}
		if (best == null) {
			throw new AssertionError("No hollow of " + kinds + " within 2 km of the spawn");
		}
		LOGGER.info("The tallest {} near the spawn is {} blocks high at {}", kinds, bestRun, best);
		return best;
	}

	/** Finds the river channel within 2 km of the spawn that carries the most water, and returns its water surface. */
	private static BlockPos findRiver(Probe probe) {
		Rivers rivers = Rivers.of(probe.field);
		float most = 0f;
		BlockPos best = null;
		for (int dx = -2048; dx <= 2048; dx += 16) {
			for (int dz = -2048; dz <= 2048; dz += 16) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				RiverColumn column = rivers.column(WorldScale.STANDARD, x, z);
				if (column.channel && column.discharge > most) {
					most = column.discharge;
					best = new BlockPos(x, column.waterTop, z);
				}
			}
		}
		if (best == null) {
			throw new AssertionError("No river within 2 km of the spawn");
		}
		LOGGER.info("The largest river near the spawn carries {} m³/s at {}", Math.round(most), best);
		return best;
	}

	/** What the test reads from the server: the spawn, and what it needs to sample the generator off-thread. */
	private record Probe(BlockPos spawn, int spawnSurface, String spawnBiome, RandomState state, NoiseRouter router,
						 BiomeSource biomes, long hollowsSeed, TerrainField field, int minY) {
		static Probe of(ServerLevel level) {
			ChunkGenerator generator = level.getChunkSource().getGenerator();
			if (!DiffusicraftWorldgen.usesModels(generator)) {
				throw new AssertionError("The overworld does not use Diffusicraft: " + generator);
			}
			NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) generator;
			BlockPos spawn = level.getRespawnData().pos();
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
			String biome = level.getBiome(spawn.atY(surface)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			RandomState state = level.getChunkSource().randomState();
			PositionalRandomFactory random = ((RandomStateAccessor) (Object) state).diffusicraft$random();
			long hollowsSeed = random.fromHashOf(HollowsFunction.SEED_ID).nextLong();
			TerrainField field = TerrainFields.get(random.fromHashOf(TerrainFields.SEED_ID).nextLong());
			return new Probe(spawn, surface, biome, state, noise.generatorSettings().value().noiseRouter(),
				generator.getBiomeSource(), hollowsSeed, field, level.getMinY());
		}
	}

	private static int surface(Probe probe, BlockPos at) {
		return (int) Math.floor(probe.state.sampleBlockValueUncached(probe.router.chunkSurfaceLevel(), at.getX(), 0,
			at.getZ()));
	}

	/**
	 * Writes a biome map and a height map of {@value #MAP_SIZE}² pixels centred on the spawn, and returns, for each of
	 * Diffusicraft's biomes on the map, the point nearest the spawn well inside it.
	 */
	private static Map<String, BlockPos> saveMaps(Probe probe) {
		long start = System.nanoTime();
		int half = MAP_SIZE * MAP_STRIDE / 2;
		int x0 = probe.spawn.getX() - half, z0 = probe.spawn.getZ() - half;
		float[] height = new float[MAP_SIZE * MAP_SIZE];
		String[] biome = new String[MAP_SIZE * MAP_SIZE];
		Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
		IntStream.range(0, MAP_SIZE).parallel().forEach(row -> {
			BiomeResolver resolver = probe.biomes.createResolver(probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			for (int col = 0; col < MAP_SIZE; col++) {
				int x = x0 + col * MAP_STRIDE, z = z0 + row * MAP_STRIDE;
				float surface = probe.state.sampleBlockValueUncached(probe.router.chunkSurfaceLevel(), x, 0, z);
				int px = row * MAP_SIZE + col;
				height[px] = surface;
				Holder<Biome> b = resolver.getNoiseBiome(QuartPos.fromBlock(x),
					QuartPos.fromBlock(Math.max((int) surface, 63)), QuartPos.fromBlock(z));
				biome[px] = b.unwrapKey().map(ResourceKey::identifier).map(Object::toString).orElse("?");
				counts.computeIfAbsent(biome[px], k -> new AtomicInteger()).incrementAndGet();
			}
		});
		LOGGER.info("Sampled a {} x {} block map in {} s", MAP_SIZE * MAP_STRIDE, MAP_SIZE * MAP_STRIDE,
			(System.nanoTime() - start) / 1_000_000_000L);

		Map<Integer, String> byCount = new TreeMap<>((a, b) -> Integer.compare(b, a) != 0 ? Integer.compare(b, a) : 1);
		counts.forEach((k, v) -> byCount.put(v.get(), k));
		StringBuilder summary = new StringBuilder();
		byCount.forEach((n, k) -> summary.append(String.format("%n  %5.1f%% %s", 100.0 * n / height.length, k)));
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (float h : height) {
			min = Math.min(min, h);
			max = Math.max(max, h);
		}
		LOGGER.info("Surface from y={} to y={}. Biomes:{}", (int) min, (int) max, summary);

		BufferedImage biomes = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
		BufferedImage heights = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
		for (int row = 0; row < MAP_SIZE; row++) {
			for (int col = 0; col < MAP_SIZE; col++) {
				int px = row * MAP_SIZE + col;
				float h = height[px];
				// Light from the north-west.
				float west = height[row * MAP_SIZE + Math.max(col - 1, 0)], north = height[Math.max(row - 1, 0) * MAP_SIZE + col];
				float shade = clamp(1f + 0.08f * ((h - west) + (h - north)), 0.55f, 1.3f);
				int color = h < 63f ? water(63f - h) : shadeColor(BiomeColors.of(biome[px]), shade);
				biomes.setRGB(col, row, color);
				int g = (int) clamp((h - min) / Math.max(1f, max - min) * 255f, 0f, 255f);
				heights.setRGB(col, row, h < 63f ? 0x1d3f8f : g << 16 | g << 8 | g);
			}
		}
		// The spawn, in red.
		for (int d = -3; d <= 3; d++) {
			biomes.setRGB(MAP_SIZE / 2 + d, MAP_SIZE / 2, 0xff0000);
			biomes.setRGB(MAP_SIZE / 2, MAP_SIZE / 2 + d, 0xff0000);
		}
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			Files.createDirectories(dir);
			ImageIO.write(biomes, "png", dir.resolve("diffusicraft-map-biomes.png").toFile());
			ImageIO.write(heights, "png", dir.resolve("diffusicraft-map-height.png").toFile());
			LOGGER.info("Maps: {}", dir.toAbsolutePath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		Map<String, BlockPos> nearest = new TreeMap<>();
		Map<String, Integer> distance = new TreeMap<>();
		for (int row = 2; row < MAP_SIZE - 2; row++) {
			for (int col = 2; col < MAP_SIZE - 2; col++) {
				String b = biome[row * MAP_SIZE + col];
				int d = Math.abs(row - MAP_SIZE / 2) + Math.abs(col - MAP_SIZE / 2);
				if (!b.startsWith("diffusicraft:") || d >= distance.getOrDefault(b, Integer.MAX_VALUE)
					|| !surrounded(biome, row, col)) {
					continue;
				}
				distance.put(b, d);
				nearest.put(b, new BlockPos(x0 + col * MAP_STRIDE, 0, z0 + row * MAP_STRIDE));
			}
		}
		LOGGER.info("Diffusicraft biomes near the spawn: {}", nearest);
		return nearest;
	}

	/**
	 * Adds the Diffusicraft biomes missing from the map: the nearest spot within 24 km on the coarse map, confirmed on
	 * the full terrain within 512 blocks of it. Biomes the climate of this seed has nowhere near are left out.
	 */
	private static void findFarBiomes(Probe probe, Map<String, BlockPos> found) {
		long start = System.nanoTime();
		int reach = 24 * 1024, step = 256;
		Map<String, BlockPos> coarse = new TreeMap<>();
		Map<String, Long> distance = new TreeMap<>();
		CoarseClimate.call(() -> {
			BiomeResolver resolver = probe.biomes.createResolver(
				probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			for (int dx = -reach; dx <= reach; dx += step) {
				for (int dz = -reach; dz <= reach; dz += step) {
					int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
					String b = biomeAt(resolver, x, 160, z);
					long d = (long) dx * dx + (long) dz * dz;
					if (BiomeRefiner.TARGETS.contains(b) && b.startsWith("diffusicraft:") && !found.containsKey(b)
						&& d < distance.getOrDefault(b, Long.MAX_VALUE)) {
						distance.put(b, d);
						coarse.put(b, new BlockPos(x, 0, z));
					}
				}
			}
			return null;
		});
		BiomeResolver resolver = probe.biomes.createResolver(probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		for (Map.Entry<String, BlockPos> entry : coarse.entrySet()) {
			BlockPos at = entry.getValue(), best = null;
			long bestDistance = Long.MAX_VALUE;
			for (int dx = -512; dx <= 512; dx += 32) {
				for (int dz = -512; dz <= 512; dz += 32) {
					BlockPos p = at.offset(dx, 0, dz);
					long d = (long) dx * dx + (long) dz * dz;
					if (d < bestDistance && fineBiome(probe, resolver, p).equals(entry.getKey())
						&& fineBiome(probe, resolver, p.offset(24, 0, 0)).equals(entry.getKey())
						&& fineBiome(probe, resolver, p.offset(-24, 0, 0)).equals(entry.getKey())
						&& fineBiome(probe, resolver, p.offset(0, 0, 24)).equals(entry.getKey())
						&& fineBiome(probe, resolver, p.offset(0, 0, -24)).equals(entry.getKey())) {
						bestDistance = d;
						best = p;
					}
				}
			}
			if (best != null) {
				found.put(entry.getKey(), best);
			} else {
				LOGGER.info("{} is on the coarse map at {} but not on the full terrain around it", entry.getKey(), at);
			}
		}
		LOGGER.info("Diffusicraft biomes within 24 km, found in {} s: {}", (System.nanoTime() - start) / 1_000_000_000L,
			found);
	}

	/**
	 * Checks the columns that Distant Horizons draws: every block of a {@value #LOD_WIDTH}² square around {@code at},
	 * whose chunks are generated, against the blocks there, ground and tree crowns, and a grid
	 * {@value #LOD_FAR_STEP} blocks apart, which must read the coarse map alone. Saves both as maps.
	 *
	 * @return what is wrong with them, or {@code null}
	 */
	private static @Nullable String checkLods(TestSingleplayerContext world, Probe probe, BlockPos at) {
		LodSampler sampler = world.getServer().computeOnServer(server -> LodSampler.of(server.overworld()));
		if (sampler == null) {
			throw new AssertionError("No LOD sampler for the Diffusicraft overworld");
		}
		int x0 = at.getX() - LOD_WIDTH / 2, z0 = at.getZ() - LOD_WIDTH / 2;
		int[] tops = new int[LOD_WIDTH * LOD_WIDTH];
		int[] crowns = new int[LOD_WIDTH * LOD_WIDTH];
		Block[] blocks = new Block[LOD_WIDTH * LOD_WIDTH];
		long start = System.nanoTime();
		sampler.sample(x0, z0, LOD_WIDTH, 1, (x, z, column) -> {
			int k = z * LOD_WIDTH + x;
			tops[k] = column.top(column.ground()) - 1;
			blocks[k] = column.state(column.ground()).getBlock();
			crowns[k] = crownTop(column);
		});
		long nearMs = (System.nanoTime() - start) / 1_000_000;
		int[] matches = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int compared = 0, heights = 0, same = 0, lodCrowns = 0, chunkCrowns = 0, lodHeight = 0, chunkHeight = 0;
			for (int k = 0; k < tops.length; k++) {
				int x = x0 + k % LOD_WIDTH, z = z0 + k / LOD_WIDTH;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					continue;
				}
				compared++;
				pos.set(x, 0, z);
				int ground = groundAt(level, pos);
				heights += Math.abs(ground - tops[k]) <= 1 ? 1 : 0;
				same += level.getBlockState(pos.setY(tops[k])).is(blocks[k]) ? 1 : 0;
				if (crowns[k] != Integer.MIN_VALUE) {
					lodCrowns++;
					lodHeight += crowns[k] - tops[k];
				}
				int highest = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
				if (level.getBlockState(pos.setY(highest)).is(BlockTags.LEAVES)) {
					chunkCrowns++;
					chunkHeight += highest - ground;
				}
			}
			return new int[]{compared, heights, same, lodCrowns, chunkCrowns, lodHeight, chunkHeight};
		});
		LOGGER.info("LOD of {} columns in {} ms: {} of {} within a block of the ground, {} with the same top block",
			tops.length, nearMs, matches[1], matches[0], matches[2]);
		LOGGER.info("Tree crowns over {} columns of the LOD, {} average height, and {} of the chunks, {} average height",
			matches[3], matches[5] / Math.max(1, matches[3]), matches[4], matches[6] / Math.max(1, matches[4]));

		long computed = probe.field.computedTiles();
		start = System.nanoTime();
		BufferedImage far = lodMap(sampler, at.getX() - LOD_WIDTH * LOD_FAR_STEP / 2,
			at.getZ() - LOD_WIDTH * LOD_FAR_STEP / 2, LOD_FAR_STEP);
		long farMs = (System.nanoTime() - start) / 1_000_000;
		long farTiles = probe.field.computedTiles() - computed;
		LOGGER.info("Far LOD, {} blocks across, in {} ms; terrain tiles computed meanwhile: {}",
			LOD_WIDTH * LOD_FAR_STEP, farMs, farTiles);
		BufferedImage near = lodMap(sampler, at.getX() - LOD_WIDTH * LOD_NEAR_STEP / 2,
			at.getZ() - LOD_WIDTH * LOD_NEAR_STEP / 2, LOD_NEAR_STEP);
		logCanopy(sampler, at);
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			ImageIO.write(near, "png", dir.resolve("diffusicraft-lod-near.png").toFile());
			ImageIO.write(far, "png", dir.resolve("diffusicraft-lod-far.png").toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (!sampler.coarse(LOD_WIDTH, LOD_FAR_STEP) || farTiles != 0) {
			return "The far LOD ran the full pipeline on " + farTiles + " tiles";
		}
		if (matches[0] == 0 || matches[1] < 0.8 * matches[0] || matches[2] < 0.6 * matches[0]) {
			return "The LOD does not match the generated chunks: " + matches[1] + " heights and " + matches[2]
				+ " blocks of " + matches[0];
		}
		// Crowns are placed by chance, so only their share of the ground has to come close.
		if (Math.abs(matches[3] - matches[4]) > LOD_CROWN_TOLERANCE * matches[0]) {
			return "The LOD has tree crowns over " + matches[3] + " columns, the chunks over " + matches[4];
		}
		return null;
	}

	/**
	 * Logs the share of each biome's LOD columns under a tree crown, which shows whether the trees of biome mods are
	 * drawn: a forest of theirs with no crowns is not.
	 */
	private static void logCanopy(LodSampler sampler, BlockPos at) {
		Map<String, int[]> canopy = new TreeMap<>();
		int half = LOD_WIDTH * LOD_CANOPY_STEP / 2;
		sampler.sample(at.getX() - half, at.getZ() - half, LOD_WIDTH, LOD_CANOPY_STEP, (x, z, column) -> {
			int[] counts = canopy.computeIfAbsent(column.biome().getRegisteredName(), biome -> new int[2]);
			counts[0]++;
			counts[1] += crownTop(column) != Integer.MIN_VALUE ? 1 : 0;
		});
		StringBuilder summary = new StringBuilder();
		canopy.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(12).forEach(entry ->
			summary.append(String.format("%n  %3d%% of %4d columns  %s", 100 * entry.getValue()[1] / entry.getValue()[0],
				entry.getValue()[0], entry.getKey())));
		LOGGER.info("Tree crowns in the LOD {} blocks across, by biome:{}", 2 * half, summary);
	}

	/** Top block of the tree crown over a column of a LOD, or {@link Integer#MIN_VALUE} under the open sky. */
	private static int crownTop(LodColumn column) {
		for (int run = column.size() - 1; run > column.ground(); run--) {
			if (column.state(run).is(BlockTags.LEAVES)) {
				return column.top(run) - 1;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** The highest block of the ground, under the trees, the ice and the water. */
	private static int groundAt(ServerLevel level, BlockPos.MutableBlockPos pos) {
		int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ()) - 1;
		for (; y > level.getMinY(); y--) {
			BlockState state = level.getBlockState(pos.setY(y));
			if (state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP) && !state.is(BlockTags.LEAVES)
				&& !state.is(BlockTags.LOGS) && !state.is(Blocks.ICE)) {
				break;
			}
		}
		return y;
	}

	/** A map of the LOD columns {@code step} blocks apart, in the blocks' map colours, lit from the north-west. */
	private static BufferedImage lodMap(LodSampler sampler, int x0, int z0, int step) {
		int[] top = new int[LOD_WIDTH * LOD_WIDTH];
		int[] color = new int[LOD_WIDTH * LOD_WIDTH];
		sampler.sample(x0, z0, LOD_WIDTH, step, (x, z, column) -> {
			BlockState state = column.state(column.size() - 1);
			top[z * LOD_WIDTH + x] = column.top();
			color[z * LOD_WIDTH + x] = state.is(Blocks.WATER) ? water(column.waterDepth())
				: state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		});
		BufferedImage image = new BufferedImage(LOD_WIDTH, LOD_WIDTH, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < LOD_WIDTH; z++) {
			for (int x = 0; x < LOD_WIDTH; x++) {
				int k = z * LOD_WIDTH + x;
				int west = top[z * LOD_WIDTH + Math.max(x - 1, 0)], north = top[Math.max(z - 1, 0) * LOD_WIDTH + x];
				float shade = clamp(1f + 0.08f * ((top[k] - west) + (top[k] - north)) / Math.max(1f, step / 8f), 0.55f, 1.3f);
				image.setRGB(x, z, shadeColor(color[k], shade));
			}
		}
		return image;
	}

	private static String fineBiome(Probe probe, BiomeResolver resolver, BlockPos at) {
		return biomeAt(resolver, at.getX(), Math.max(surface(probe, at), 63), at.getZ());
	}

	private static String biomeAt(BiomeResolver resolver, int x, int y, int z) {
		return resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).unwrapKey()
			.map(ResourceKey::identifier).map(Object::toString).orElse("?");
	}

	/** Whether the pixels two around this one all have its biome. */
	private static boolean surrounded(String[] biome, int row, int col) {
		String b = biome[row * MAP_SIZE + col];
		for (int dr = -2; dr <= 2; dr++) {
			for (int dc = -2; dc <= 2; dc++) {
				if (!b.equals(biome[(row + dr) * MAP_SIZE + col + dc])) {
					return false;
				}
			}
		}
		return true;
	}

	private static int water(float depth) {
		float t = clamp(depth / 40f, 0f, 1f);
		int r = (int) (60 - 40 * t), g = (int) (120 - 70 * t), b = (int) (220 - 90 * t);
		return r << 16 | g << 8 | b;
	}

	private static int shadeColor(int rgb, float shade) {
		int r = (int) clamp(((rgb >> 16) & 0xff) * shade, 0f, 255f);
		int g = (int) clamp(((rgb >> 8) & 0xff) * shade, 0f, 255f);
		int b = (int) clamp((rgb & 0xff) * shade, 0f, 255f);
		return r << 16 | g << 8 | b;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
