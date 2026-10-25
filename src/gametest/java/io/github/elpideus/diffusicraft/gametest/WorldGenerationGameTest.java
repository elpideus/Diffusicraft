package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.terrain.geography.Geography;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeClimate;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.biome.HollowBiomes;
import io.github.elpideus.diffusicraft.worldgen.biome.SubBiomes;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.geology.Ore;
import io.github.elpideus.diffusicraft.worldgen.lod.LodColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.github.elpideus.diffusicraft.worldgen.material.ModOres;
import io.github.elpideus.diffusicraft.worldgen.material.StrataRule;
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
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Creates a Diffusicraft world with the default settings and checks that the overworld really uses Diffusicraft. Saves
 * to {@code build/run/clientGameTest/screenshots}: a biome map and a height map around the spawn, sampled from the
 * generator without building chunks, in-game views from above the spawn, over the largest river nearby if any, of the
 * tallest sea cliff nearby and of each of Diffusicraft's biomes within 24 km or along the meridian towards the equator,
 * and views inside the nearest Grand Hollow, inside the tallest Grand Hollow of each of their biomes, over the nearest
 * lake in a Grand Hollow and over the nearest lava sea of a Deep Grand Hollow. It also checks that each Grand Hollow
 * biome grows what it should, that the lake holds its water, and the levels of detail Distant Horizons draws against
 * the chunks generated around the spawn, and saves maps of them near and far.
 *
 * <p>Needs the Terrain Diffusion models; without them the test logs a warning and does nothing. Generation runs on the
 * CPU, so the test leaves the GPU alone; pass {@code -Pdiffusicraft.device=CUDA} (or {@code AUTO}) to change that.
 */
public class WorldGenerationGameTest implements FabricClientGameTest {
	/**
	 * The size of the world, 1:16: the checks below were made for features about this size, and the size the
	 * default (1:1) is tested at is {@link UltraWorldGameTest}'s.
	 */
	static final WorldScale SIZE = WorldScale.MEDIUM;
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft world test");
	/**
	 * The world's seed: a coastal spawn below the wall of a fjord, with cliffs and Grand Hollows near it but no river
	 * within 4 km. {@code -Pdiffusicraft.seed=...} picks another, to see the biomes of other climates.
	 */
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	/** Map size in pixels, and blocks per pixel. */
	private static final int MAP_SIZE = 256;
	private static final int MAP_STRIDE = 8;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 20;
	private static final int VIEW_TIMEOUT_TICKS = 5 * TICKS_PER_MINUTE;
	/** Columns per side of the LOD grids, and blocks between their columns near and far. */
	private static final int LOD_WIDTH = 64;
	private static final int LOD_NEAR_STEP = 8;
	/** The steepest ground, in blocks per block, whose soil must go deeper than vanilla's, and how much is needed. */
	private static final double GENTLE_SLOPE = 0.1;
	private static final int MIN_GENTLE_COLUMNS = 25;
	/**
	 * How far under the top of the ground the solid rock lies at most, under the deepest soil and its weathered rock,
	 * and how far down the rock is searched for dirt and gravel.
	 */
	private static final int ROCK_DEPTH = 24;
	private static final int ROCK_REACH = 80;
	/** Spacing of the LOD columns whose tree crowns are counted by biome, which spans several biomes. */
	private static final int LOD_CANOPY_STEP = 16;
	private static final int LOD_FAR_STEP = 256;
	/**
	 * Blocks to each side of the meridian through the spawn that the search for the biomes of other latitudes covers:
	 * about 500 km, wide enough to cross a tropical mountain range.
	 */
	private static final int MERIDIAN_STRIP = 32768;
	/** How far the share of the LOD's columns under a tree crown may be from the chunks'. */
	private static final double LOD_CROWN_TOLERANCE = 0.2;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the world generation test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);
		LOGGER.info("Generating on {}, seed {}", device, SEED);

		// A fresh Create World screen selects Diffusicraft.
		context.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		context.waitForScreen(CreateWorldScreen.class);
		String defaultType = context.computeOnClient(mc -> describe(((CreateWorldScreen) mc.gui.screen()).getUiState()));
		if (!defaultType.equals(DiffusicraftWorldgen.DEFAULT_PRESET.identifier().toString())) {
			throw new AssertionError("The default world type is " + defaultType);
		}
		context.setScreen(TitleScreen::new);

		long start = System.nanoTime();
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, SIZE);
			})
			.create()) {
			LOGGER.info("World loaded in {} s", (System.nanoTime() - start) / 1_000_000_000L);

			Probe probe = world.getServer().computeOnServer(server -> Probe.of(server.overworld()));
			LOGGER.info("Spawn {} ({}) at surface y={}, biome {}", probe.spawn, probe.field.spawnKind(), probe.spawnSurface,
				probe.spawnBiome);
			String spawnProblem = world.getServer().computeOnServer(server -> checkSpawn(server.overworld(), probe.spawn));
			String blocksProblem = world.getServer().computeOnServer(server -> checkSolidBlocks());
			String oresProblem = world.getServer().computeOnServer(server -> checkModOres());
			blocksProblem = spawnProblem != null ? spawnProblem : blocksProblem != null ? blocksProblem : oresProblem;
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
			// Distant Horizons' LODs, against the chunks just generated around the spawn. A failure, here or at the
			// cliff, waits for the views to be taken.
			String problem = checkLods(world, probe, probe.spawn);
			problem = problem != null ? problem : blocksProblem;
			view(context, world, above, 180f, 20f, "view-north");
			view(context, world, above.above(120), 90f, 40f, "view-high-west");

			// The soil under the grass around the spawn, and a pit dug beside its deepest column to show it.
			SoilSurvey soils = world.getServer().computeOnServer(server -> checkSoils(server.overworld(),
				probe.field.surface(SIZE), probe.spawn));
			problem = problem != null ? problem : soils.problem;
			if (soils.deepest != null) {
				BlockPos at = soils.deepest;
				world.getServer().runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", at.getX() - 12,
					at.getY() - soils.depth - 6, at.getZ() + 1, at.getX() + 12, at.getY() + 3, at.getZ() + 14));
				view(context, world, at.offset(0, -soils.depth / 2, 13), 180f, 0f, "soil");
			}

			// The largest river nearby, if there is one, from its bank and from high above.
			BlockPos river = findRiver(probe);
			if (river != null) {
				view(context, world, river.offset(0, 24, 36), 180f, 30f, "river");
				view(context, world, river.above(140), 180f, 75f, "river-above");
			}

			// The deepest lake nearby, if there is one, from its shore and from high above.
			BlockPos lake = findLake(probe);
			if (lake != null) {
				view(context, world, lake.offset(0, 24, 36), 180f, 30f, "lake");
				view(context, world, lake.above(140), 180f, 75f, "lake-above");
			}

			// The steepest cliff nearby, from the foot of it, where its scree lies. Its face must be bare rock.
			Cliff cliff = findCliff(probe);
			BlockPos foot = cliff.at.offset((int) Math.round(-48 * cliff.uphillX), 0,
				(int) Math.round(-48 * cliff.uphillZ));
			float yaw = (float) Math.toDegrees(Math.atan2(-cliff.uphillX, cliff.uphillZ));
			view(context, world, foot.atY(Math.max(surface(probe, foot), cliff.at.getY()) + 12), yaw, 0f, "cliff");
			String cliffProblem = world.getServer().computeOnServer(server -> checkCliff(server.overworld(), cliff.at));
			problem = problem != null ? problem : cliffProblem;

			// The tallest sea cliff nearby, if there is one, from out at sea and from above. Its face must be sheer.
			SeaCliff seaCliff = findSeaCliff(probe);
			if (seaCliff != null) {
				float inland = (float) Math.toDegrees(Math.atan2(-seaCliff.inlandX, seaCliff.inlandZ));
				BlockPos camera = seaCliff.top.offset((int) Math.round(-40 * seaCliff.inlandX), 0,
					(int) Math.round(-40 * seaCliff.inlandZ));
				view(context, world, camera.atY(seaCliff.top.getY() + 6), inland, 10f, "sea-cliff");
				view(context, world, seaCliff.top.above(100), inland, 70f, "sea-cliff-above");
				String seaProblem = world.getServer().computeOnServer(server -> checkSeaCliff(server.overworld(),
					probe.field.surface(SIZE), seaCliff));
				problem = problem != null ? problem : seaProblem;
			}

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

			// The tallest Grand Hollow of each biome nearby, lit up and looking up at the ceiling. The glowworm
			// grotto by its own light too. Each must grow its own blocks.
			for (Map.Entry<String, HollowRun> biome : findHollowBiomes(probe).entrySet()) {
				String name = "hollow-" + biome.getKey().substring(biome.getKey().indexOf(':') + 1);
				BlockPos at = biome.getValue().middle();
				view(context, world, at, 0f, 0f, name);
				view(context, world, at, 0f, -40f, name + "-up");
				if (biome.getKey().equals(HollowBiomes.GLOWWORM_GROTTO)) {
					world.getServer().runCommand("effect clear @a minecraft:night_vision");
					view(context, world, at, 0f, -40f, name + "-dark");
					world.getServer().runCommand("effect give @a minecraft:night_vision infinite 0 true");
				}
				String lifeProblem = world.getServer().computeOnServer(server ->
					checkHollowLife(server.overworld(), biome.getKey(), biome.getValue()));
				problem = problem != null ? problem : lifeProblem;
			}

			// The lake of the nearest Grand Hollow that holds one, from just over its water.
			BlockPos hollowLake = findHollowLake(probe);
			if (hollowLake != null) {
				view(context, world, hollowLake, 0f, 25f, "hollow-lake");
				String lakeProblem = world.getServer().computeOnServer(server -> checkHollowLake(server.overworld(),
					hollowLake));
				problem = problem != null ? problem : lakeProblem;
			}
			if (problem != null) {
				throw new AssertionError(problem);
			}
		}
	}

	private static String describe(WorldCreationUiState ui) {
		Holder<?> preset = ui.getWorldType().preset();
		return preset == null ? "custom" : preset.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
	}

	/**
	 * Teleports the player, waits for the chunks and takes a screenshot at noon: a test that runs for an hour sees the
	 * days go by, and the views would otherwise fall at night.
	 */
	static void view(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at, float yaw,
							 float pitch, String name) {
		world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(),
			at.getZ(), yaw, pitch));
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
		world.getServer().runCommand("time set noon");
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-world-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}

	/**
	 * What the client still has to do before the view is complete, or an empty string. Like Fabric's
	 * {@code waitForChunksRender}, but it only waits for the chunks the server sends: those in a circle around the
	 * player. Fabric's waits for a square, whose corners never arrive at a render distance above 6.
	 */
	static String pendingWork(Minecraft mc) {
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

	/** The biomes of the Grand Hollows, and a block each grows that no other cave does. */
	private static final Map<String, Block> HOLLOW_LIFE = Map.of(
		HollowBiomes.GLOWWORM_GROTTO, DiffusicraftBlocks.GLOWWORM_SILK,
		HollowBiomes.SELENITE_CAVERNS, DiffusicraftBlocks.SELENITE,
		HollowBiomes.MUSHROOM_FOREST, Blocks.MUSHROOM_STEM,
		HollowBiomes.SULFUR_CAVES, Blocks.SULFUR_SPIKE,
		HollowBiomes.SALT_CAVERNS, DiffusicraftBlocks.SALT_CRUST,
		HollowBiomes.ICE_CAVES, Blocks.BLUE_ICE,
		HollowBiomes.LUSH_CAVES, Blocks.MOSS_BLOCK,
		HollowBiomes.DRIPSTONE_CAVES, Blocks.POINTED_DRIPSTONE);

	/**
	 * How far out from the tallest run of a Grand Hollow its biome's own block is looked for, in blocks. That run lies
	 * over the deepest part of the cavern, where its lake gathers: in the world test's seed the mushroom forest's lake
	 * leaves no mushrooms within 32 blocks of it, and hundreds of stems within 96.
	 */
	private static final int LIFE_REACH = 96;

	/** The open run of a Grand Hollow in a column: its middle, and how many blocks tall it is. */
	private record HollowRun(BlockPos middle, int height) {
	}

	/**
	 * Finds, for each biome of the Grand Hollows within 4 km of the spawn, the column with the tallest open run of a
	 * Grand Hollow of that biome, and returns that run.
	 */
	private static Map<String, HollowRun> findHollowBiomes(Probe probe) {
		long start = System.nanoTime();
		Hollows hollows = Hollows.of(probe.hollowsSeed, probe.minY);
		Hollows.Column column = new Hollows.Column();
		Map<String, HollowRun> best = new TreeMap<>();
		// On the coarse map, which is enough to place the hollows under the ground and spares the model 64 km² of land.
		CoarseClimate.call(() -> {
			BiomeResolver resolver = probe.biomes.createResolver(
				probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			for (int dx = -4096; dx <= 4096; dx += 64) {
				for (int dz = -4096; dz <= 4096; dz += 64) {
					int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
					hollows.column(x, z, (px, pz) -> (int) Math.floor(probe.state.sampleBlockValueUncached(
						probe.router.chunkSurfaceLevel(), px, 0, pz)), column);
					int run = 0;
					for (int y = column.grandBottom(); y <= column.grandTop() + 1; y++) {
						Hollows.Kind kind = hollows.openKind(column, y);
						if (kind == Hollows.Kind.GRAND_HOLLOW || kind == Hollows.Kind.ANCIENT_GRAND_HOLLOW) {
							run++;
							continue;
						}
						if (run >= 24) {
							int middle = y - run / 2;
							String biome = biomeAt(resolver, x, middle, z);
							HollowRun was = best.get(biome);
							if (HOLLOW_LIFE.containsKey(biome) && (was == null || run > was.height())) {
								best.put(biome, new HollowRun(new BlockPos(x, middle, z), run));
							}
						}
						run = 0;
					}
				}
			}
			return null;
		});
		LOGGER.info("Grand Hollow biomes within 4 km in {} ms: {}", (System.nanoTime() - start) / 1_000_000L, best);
		return best;
	}

	/**
	 * A problem if a Grand Hollow grows none of its biome's own block within {@link #LIFE_REACH} blocks of its run,
	 * from below its floor to above its ceiling, else null. Its floor may lie a hundred blocks and more below the
	 * middle of the run.
	 */
	private static @Nullable String checkHollowLife(ServerLevel level, String biome, HollowRun run) {
		Block life = HOLLOW_LIFE.get(biome);
		BlockPos at = run.middle();
		int reach = run.height() / 2 + 32;
		int bottom = Math.max(at.getY() - reach, level.getMinY()), top = Math.min(at.getY() + reach, level.getMaxY());
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = bottom; y <= top; y++) {
			for (int z = at.getZ() - LIFE_REACH; z <= at.getZ() + LIFE_REACH; z++) {
				for (int x = at.getX() - LIFE_REACH; x <= at.getX() + LIFE_REACH; x++) {
					if (level.getBlockState(pos.set(x, y, z)).is(life)) {
						count++;
					}
				}
			}
		}
		LOGGER.info("The {} at {}, {} blocks tall, has {} blocks of {} within {} blocks", biome, at, run.height(), count,
			BuiltInRegistries.BLOCK.getKey(life), LIFE_REACH);
		return count > 0 ? null : "The " + biome + " at " + at + " grows no " + BuiltInRegistries.BLOCK.getKey(life);
	}

	/**
	 * Finds the nearest Grand Hollow within 4 km of the spawn whose basin holds a lake, and returns a point six blocks
	 * over its water near the middle, or {@code null} if no cavern nearby holds one.
	 */
	private static @Nullable BlockPos findHollowLake(Probe probe) {
		BiomeWorld biomeWorld = ((BiomeWorld.Carrier) (Object) probe.state).diffusicraft$biomeWorld();
		Hollows hollows = Hollows.of(probe.hollowsSeed, probe.minY);
		Hollows.Column column = new Hollows.Column();
		TerrainField.Column scratch = new TerrainField.Column();
		BiomeClimate climate = new BiomeClimate();
		Map<Long, Boolean> holds = new HashMap<>();
		// On the coarse map, like findHollowBiomes.
		return CoarseClimate.call(() -> {
			Hollows.Chamber nearest = null;
			double best = Double.POSITIVE_INFINITY;
			for (int dx = -4096; dx <= 4096; dx += 64) {
				for (int dz = -4096; dz <= 4096; dz += 64) {
					int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
					hollows.column(x, z, (px, pz) -> surface(probe, new BlockPos(px, 0, pz)), column);
					for (int y = column.grandBottom(); y < hollows.highestLake(); y++) {
						Hollows.Chamber chamber = hollows.lakeAt(column, y);
						if (chamber == null) {
							continue;
						}
						boolean lake = holds.computeIfAbsent(chamber.id(), id -> HollowBiomes.holdsLake(
							HollowBiomes.pick(chamber.kind(), id, HollowBiomes.climate(biomeWorld, chamber, scratch,
								climate)), climate));
						double distance = Math.hypot(chamber.x() - probe.spawn.getX(), chamber.z() - probe.spawn.getZ());
						if (lake && distance < best) {
							best = distance;
							nearest = chamber;
						}
						break;
					}
				}
			}
			if (nearest == null) {
				LOGGER.info("No Grand Hollow within 4 km holds a lake: {} caverns with basins", holds.size());
				return null;
			}
			// Over open water near the middle, clear of the pillars.
			int y = nearest.lakeLevel() + 6;
			for (int r = 0; r <= 32; r += 4) {
				for (int a = 0; a < 8; a++) {
					int x = (int) Math.floor(nearest.x() + r * Math.cos(a * Math.PI / 4));
					int z = (int) Math.floor(nearest.z() + r * Math.sin(a * Math.PI / 4));
					hollows.column(x, z, (px, pz) -> surface(probe, new BlockPos(px, 0, pz)), column);
					if (hollows.density(column, y) < 0 && hollows.density(column, nearest.lakeLevel() - 1) < 0) {
						LOGGER.info("The nearest Grand Hollow lake is at {}, level {}, of {} caverns with basins",
							new BlockPos(x, y, z), nearest.lakeLevel(), holds.size());
						return new BlockPos(x, y, z);
					}
				}
			}
			LOGGER.info("The lake at {}, {} is all pillars near the middle", nearest.x(), nearest.z());
			return null;
		});
	}

	/** A problem if a lake's water does not lie in a sheet under the camera six blocks up, else null. */
	private static @Nullable String checkHollowLake(ServerLevel level, BlockPos camera) {
		int surface = camera.getY() - 7;
		int water = 0;
		int above = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = camera.getZ() - 16; z <= camera.getZ() + 16; z++) {
			for (int x = camera.getX() - 16; x <= camera.getX() + 16; x++) {
				water += level.getFluidState(pos.set(x, surface, z)).is(FluidTags.WATER) ? 1 : 0;
				above += level.getFluidState(pos.set(x, surface + 1, z)).is(FluidTags.WATER) ? 1 : 0;
			}
		}
		LOGGER.info("The lake at {} has {} blocks of water at its surface, {} over it", camera, water, above);
		if (water < 33 * 33 / 4) {
			return "The lake at " + camera + " has only " + water + " blocks of water at its surface";
		}
		return above > water / 10 ? "The lake at " + camera + " has " + above + " blocks of water over its surface"
			: null;
	}

	/**
	 * Finds the river channel within 2 km of the spawn that carries the most water, if any, and returns its water
	 * surface. A coast below steep land may have none: its streams run into the sea before they grow into rivers.
	 */
	private static @Nullable BlockPos findRiver(Probe probe) {
		Rivers rivers = Rivers.of(probe.field);
		float most = 0f;
		BlockPos best = null;
		for (int dx = -2048; dx <= 2048; dx += 16) {
			for (int dz = -2048; dz <= 2048; dz += 16) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				RiverColumn column = rivers.column(SIZE, x, z);
				if (column.channel && column.discharge > most) {
					most = column.discharge;
					best = new BlockPos(x, column.waterTop, z);
				}
			}
		}
		if (best == null) {
			LOGGER.info("No river within 2 km of the spawn");
		} else {
			LOGGER.info("The largest river near the spawn carries {} m³/s at {}", Math.round(most), best);
		}
		return best;
	}

	/** Whether a player can stand at the world spawn: on a full block, with two blocks free of solids and water. */
	private static @Nullable String checkSpawn(ServerLevel level, BlockPos spawn) {
		BlockState ground = level.getBlockState(spawn.below());
		if (!Block.isFaceFull(ground.getCollisionShape(level, spawn.below()), Direction.UP)) {
			return "The world spawn " + spawn + " stands on " + ground;
		}
		for (BlockPos pos : new BlockPos[]{spawn, spawn.above()}) {
			BlockState state = level.getBlockState(pos);
			if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
				return "The world spawn " + spawn + " is blocked by " + state + " at " + pos;
			}
		}
		return null;
	}

	/**
	 * Whether every full block of Diffusicraft's is in {@code #blocks_motion}, which lists vanilla's solid blocks one by
	 * one. The heightmaps count only the blocks there, so features and snow would look through a missing one.
	 */
	private static @Nullable String checkSolidBlocks() {
		List<String> missing = BuiltInRegistries.BLOCK.stream()
			.filter(block -> BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("diffusicraft"))
			.map(Block::defaultBlockState)
			.filter(state -> state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
			.filter(state -> !state.is(BlockTags.BLOCKS_MOTION))
			.map(BlockState::getBlock)
			.map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
			.toList();
		return missing.isEmpty() ? null : "Full blocks missing from #minecraft:blocks_motion: " + missing;
	}

	/**
	 * With Tech Reborn (run with {@code -PdevMods=techreborn}), whether the geology places its ores: those it generates
	 * in stone and deepslate, but not its sphalerite, which it generates in the Nether.
	 */
	private static @Nullable String checkModOres() {
		if (!FabricLoader.getInstance().isModLoaded("techreborn")) {
			return null;
		}
		ModOres ores = ModOres.current();
		for (Ore ore : List.of(Ore.TIN, Ore.LEAD, Ore.SILVER, Ore.URANIUM, Ore.RUBY, Ore.SAPPHIRE, Ore.ALUMINUM)) {
			if (!ores.available().contains(ore)) {
				return "The geology does not place Tech Reborn's " + ore;
			}
		}
		// Tin by the ground its ore features put it in, lead by the metal's tag rather than galena's.
		for (Ore ore : List.of(Ore.TIN, Ore.LEAD)) {
			String name = ore.tags().getFirst();
			String blocks = BuiltInRegistries.BLOCK.getKey(ores.block(ore, false).getBlock()) + " and "
				+ BuiltInRegistries.BLOCK.getKey(ores.block(ore, true).getBlock());
			if (!blocks.equals("techreborn:" + name + "_ore and techreborn:deepslate_" + name + "_ore")) {
				return "The geology places " + blocks + " for " + name;
			}
		}
		if (ores.available().contains(Ore.ZINC)
			&& BuiltInRegistries.BLOCK.getKey(ores.block(Ore.ZINC, false).getBlock()).getNamespace().equals("techreborn")) {
			return "The geology places Tech Reborn's sphalerite, a Nether ore";
		}
		return null;
	}

	/** The deepest soil around the spawn, how deep it goes, and what is wrong with the soil there, if anything. */
	private record SoilSurvey(@Nullable BlockPos deepest, int depth, @Nullable String problem) {
	}

	/**
	 * Walks down the grass-covered columns within 96 blocks of the spawn to the rock, and tallies their soil. On gentle
	 * ground ({@value #GENTLE_SLOPE} blocks per block or less) outside limestone, chalk and marble, the soil must go
	 * deeper than vanilla's few blocks of dirt on average: the slopes and those rocks keep it thin. No sand or gravel
	 * may hang over open space in it, and the solid rock under it holds no dirt or gravel down to the first open space:
	 * Diffusicraft worlds leave vanilla's blobs of them out.
	 */
	private static SoilSurvey checkSoils(ServerLevel level, TerrainSurface surface, BlockPos spawn) {
		Set<Block> carbonates = Set.of(DiffusicraftBlocks.LIMESTONE, DiffusicraftBlocks.CHALK, DiffusicraftBlocks.MARBLE);
		Map<String, Integer> tally = new TreeMap<>();
		int columns = 0, gentle = 0, buried = 0;
		long total = 0, gentleTotal = 0;
		int deepestDepth = 0;
		BlockPos deepest = null;
		String problem = null;
		for (int dx = -96; dx <= 96; dx += 2) {
			for (int dz = -96; dz <= 96; dz += 2) {
				int x = spawn.getX() + dx, z = spawn.getZ() + dz;
				int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
				if (top <= level.getSeaLevel() || !level.getBlockState(new BlockPos(x, top, z)).is(Blocks.GRASS_BLOCK)) {
					continue;
				}
				columns++;
				int depth = 1;
				BlockState rock = null;
				for (int y = top - 1; y > top - 40; y--) {
					BlockPos at = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(at);
					boolean falls = state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
					if (!falls && !state.is(BlockTags.SUBSTRATE_OVERWORLD) && !state.is(Blocks.CLAY)
						&& !state.is(Blocks.CALCITE)) {
						rock = state;
						break;
					}
					depth++;
					tally.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
					if (problem == null && falls && level.getBlockState(at.below()).isAir()) {
						problem = "The soil at " + at + " is " + state + " over open space";
					}
				}
				for (int y = top - 1; y > top - ROCK_REACH; y--) {
					BlockPos at = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(at);
					if (state.isAir() || !state.getFluidState().isEmpty()) {
						break;
					}
					if (y < top - ROCK_DEPTH && (state.is(Blocks.DIRT) || state.is(Blocks.GRAVEL))) {
						buried++;
						if (problem == null) {
							problem = "The rock at " + at + ", " + (top - y) + " blocks under the grass, is " + state;
						}
					}
				}
				total += depth;
				if (Erosion.slope(surface, x, z) <= GENTLE_SLOPE && (rock == null || !carbonates.contains(rock.getBlock()))) {
					gentle++;
					gentleTotal += depth;
				}
				if (depth > deepestDepth) {
					deepestDepth = depth;
					deepest = new BlockPos(x, top, z);
				}
			}
		}
		double mean = columns == 0 ? 0 : total / (double) columns;
		double gentleMean = gentle == 0 ? 0 : gentleTotal / (double) gentle;
		LOGGER.info("Under the grass of {} columns around the spawn the soil goes {} blocks deep on average and {} at "
			+ "most, at {}: {}. On the {} of gentle ground outside carbonate rock it goes {} blocks deep. The rock "
			+ "under it holds {} blocks of dirt and gravel", columns, Math.round(mean * 10) / 10.0, deepestDepth, deepest,
			tally, gentle, Math.round(gentleMean * 10) / 10.0, buried);
		if (problem == null && gentle >= MIN_GENTLE_COLUMNS && gentleMean < 4) {
			problem = "The soil on gentle ground around the spawn goes only " + gentleMean + " blocks deep on average";
		}
		return new SoilSurvey(deepest, deepestDepth, problem);
	}

	/** A column on a cliff, and which way the ground rises there, as a unit vector. */
	private record Cliff(BlockPos at, double uphillX, double uphillZ) {
	}

	/** Finds the steepest ground within 1 km of the spawn that {@link Erosion} leaves bare. */
	private static Cliff findCliff(Probe probe) {
		long seed = ((RandomStateAccessor) (Object) probe.state).diffusicraft$random().fromHashOf(StrataRule.SEED_ID)
			.nextLong();
		Erosion erosion = new Erosion(seed);
		Erosion.Surface ground = probe.field.surface(SIZE);
		double steepest = 0;
		Cliff best = null;
		for (int dx = -1024; dx <= 1024; dx += 8) {
			for (int dz = -1024; dz <= 1024; dz += 8) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				double cx = x + 0.5, cz = z + 0.5, y = ground.y(cx, cz);
				if (y < SIZE.seaLevel() + 8) {
					continue;
				}
				double gx = (ground.y(cx + 1, cz) - ground.y(cx - 1, cz)) / 2;
				double gz = (ground.y(cx, cz + 1) - ground.y(cx, cz - 1)) / 2;
				double slope = Math.sqrt(gx * gx + gz * gz);
				if (slope > steepest && erosion.kind(ground, x, z) == Erosion.Kind.CLIFF) {
					steepest = slope;
					best = new Cliff(new BlockPos(x, (int) Math.floor(y), z), gx / slope, gz / slope);
				}
			}
		}
		if (best == null) {
			throw new AssertionError("No cliff within 1 km of the spawn");
		}
		LOGGER.info("The steepest cliff near the spawn rises {} blocks per block at {}",
			Math.round(steepest * 100) / 100.0, best.at);
		return best;
	}

	/**
	 * Whether the cliff at {@code at} shows its rock: no soil or sand among the top blocks of the ground.
	 */
	private static @Nullable String checkCliff(ServerLevel level, BlockPos at) {
		int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, at.getX(), at.getZ()) - 1;
		List<String> blocks = new ArrayList<>();
		String problem = null;
		for (int y = top; y > top - 4; y--) {
			BlockState state = level.getBlockState(at.atY(y));
			blocks.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
			if (problem == null && (state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND))) {
				problem = "The cliff at " + at.atY(y) + " is covered with " + state;
			}
		}
		List<String> around = new ArrayList<>();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				BlockPos column = at.offset(dx, 0, dz);
				int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, column.getX(), column.getZ()) - 1;
				around.add(y + " " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(column.atY(y)).getBlock()).getPath());
			}
		}
		LOGGER.info("The cliff from y = {} down: {}; the tops of the columns around it, row by row: {}", top, blocks,
			around);
		return problem;
	}

	/**
	 * The top of a sea cliff, the column at its foot beside it, and which way is inland, as a unit vector. The top is
	 * the cliff column's ground as {@link TerrainSurface} has it, the first block of air above it.
	 */
	private record SeaCliff(BlockPos top, BlockPos foot, double inlandX, double inlandZ) {
	}

	/** Finds the tallest sea cliff within 2 km of the spawn that no river cuts through, if any. */
	private static @Nullable SeaCliff findSeaCliff(Probe probe) {
		TerrainSurface surface = probe.field.surface(SIZE);
		Rivers rivers = Rivers.of(probe.field);
		double pixelsPerBlock = SIZE.pixelsPerBlock();
		float[] inland = new float[2];
		double tallest = 0;
		int columns = 0;
		SeaCliff best = null;
		for (int dx = -2048; dx <= 2048; dx += 4) {
			for (int dz = -2048; dz <= 2048; dz += 4) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				float top = surface.cliff(x, z);
				if (Float.isNaN(top)) {
					continue;
				}
				columns++;
				probe.field.coast((z + 0.5) * pixelsPerBlock, (x + 0.5) * pixelsPerBlock, inland);
				double length = Math.hypot(inland[0], inland[1]), ux = inland[1] / length, uz = inland[0] / length;
				// Walk out to sea to the foot of the face.
				int fx = x, fz = z;
				for (int step = 1; step <= 8; step++) {
					fx = x - (int) Math.round(step * ux);
					fz = z - (int) Math.round(step * uz);
					if (surface.y(fx, fz) < SIZE.seaLevel() + 1.5f) {
						break;
					}
				}
				// The last column before the foot carries the top of the face.
				int tx = fx + (int) Math.round(ux), tz = fz + (int) Math.round(uz);
				double drop = surface.y(tx, tz) - surface.y(fx, fz);
				if (drop > tallest && !rivers.column(SIZE, tx, tz).river && !rivers.column(SIZE, fx, fz).river) {
					tallest = drop;
					best = new SeaCliff(new BlockPos(tx, (int) Math.ceil(surface.y(tx, tz)), tz),
						new BlockPos(fx, (int) Math.ceil(surface.y(fx, fz)), fz), ux, uz);
				}
			}
		}
		LOGGER.info("{} of the columns sampled within 2 km of the spawn are on sea cliffs; the tallest drops {} blocks "
			+ "at {}", columns, Math.round(tallest * 10) / 10.0, best == null ? null : best.top);
		return best;
	}

	/**
	 * Whether the face of a sea cliff is as sheer as {@link TerrainSurface} has it: the columns along the top of it
	 * solid over nine tenths of the face above the water, which the density, interpolated across cells 4 blocks wide,
	 * would undercut, and the neighbour at the foot open halfway up, where it would put a slope. Under the water, caves
	 * and ravines may open into the face, as sea caves do; above it, a cave may open into one column of the face, so the
	 * top is measured along it.
	 */
	private static @Nullable String checkSeaCliff(ServerLevel level, TerrainSurface surface, SeaCliff cliff) {
		int middle = (cliff.top.getY() + cliff.foot.getY()) / 2;
		if (cliff.top.getY() - cliff.foot.getY() < 4) {
			return null;
		}
		// The ground across the face, from the sea inland, in the world and as the surface has it.
		List<String> profile = new ArrayList<>();
		for (int step = -2; step <= 3; step++) {
			int x = cliff.foot.getX() + (int) Math.round(step * cliff.inlandX);
			int z = cliff.foot.getZ() + (int) Math.round(step * cliff.inlandZ);
			profile.add(x + "," + z + ": " + level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) + "/"
				+ (int) Math.ceil(surface.y(x, z)) + (Float.isNaN(surface.cliff(x, z)) ? "" : " cliff"));
		}
		LOGGER.info("Across the sea cliff, the world's ground and the surface's: {}", profile);
		BlockState top = level.getBlockState(cliff.top.atY(middle)), foot = level.getBlockState(cliff.foot.atY(middle));
		LOGGER.info("Halfway up the sea cliff, at y = {}: {} behind its face, {} in front", middle, top, foot);
		// The columns along the top of the face, a few blocks either way, from the water up to their own ground.
		int water = Math.max(cliff.foot.getY(), SIZE.seaLevel() + 1), solid = 0, blocks = 0;
		List<String> columns = new ArrayList<>();
		for (int step = -4; step <= 4; step++) {
			BlockPos column = cliff.top.offset((int) Math.round(-step * cliff.inlandZ), 0,
				(int) Math.round(step * cliff.inlandX));
			int ground = (int) Math.ceil(surface.y(column.getX(), column.getZ()));
			if (ground < cliff.top.getY() - 2) {
				continue;
			}
			int count = 0, openFrom = Integer.MIN_VALUE;
			List<String> open = new ArrayList<>();
			for (int y = water; y < ground; y++) {
				BlockPos at = column.atY(y);
				if (level.getBlockState(at).isCollisionShapeFullBlock(level, at)) {
					count++;
					if (openFrom != Integer.MIN_VALUE) {
						open.add(openFrom + "-" + (y - 1));
						openFrom = Integer.MIN_VALUE;
					}
				} else if (openFrom == Integer.MIN_VALUE) {
					openFrom = y;
				}
			}
			if (openFrom != Integer.MIN_VALUE) {
				open.add(openFrom + "-" + (ground - 1));
			}
			solid += count;
			blocks += ground - water;
			columns.add(column.getX() + "," + column.getZ() + ": " + count + " of " + (ground - water) + " solid, open "
				+ open);
		}
		LOGGER.info("Along the top of the sea cliff, from the water at y = {} up: {}", water, columns);
		if (solid * 10 < blocks * 9) {
			return "The sea cliff at " + cliff.top + " is open over " + (blocks - solid) + " of the " + blocks
				+ " blocks of its face above the water";
		}
		if (foot.isCollisionShapeFullBlock(level, cliff.foot.atY(middle)) && !foot.is(BlockTags.LOGS)
			&& !foot.is(BlockTags.LEAVES)) {
			return "The foot of the sea cliff at " + cliff.foot.atY(middle) + " is solid: " + foot;
		}
		return null;
	}

	/** Finds the deepest lake within 2 km of the spawn, if any, and returns its water surface there. */
	private static @Nullable BlockPos findLake(Probe probe) {
		Rivers rivers = Rivers.of(probe.field);
		int deepest = 0, columns = 0, sampled = 0;
		BlockPos best = null;
		for (int dx = -2048; dx <= 2048; dx += 16) {
			for (int dz = -2048; dz <= 2048; dz += 16) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				RiverColumn column = rivers.column(SIZE, x, z);
				sampled++;
				if (column.lake) {
					columns++;
					if (column.waterTop - column.bed > deepest) {
						deepest = column.waterTop - column.bed;
						best = new BlockPos(x, column.waterTop, z);
					}
				}
			}
		}
		LOGGER.info("Lakes cover {} of {} columns sampled within 2 km of the spawn; the deepest is {} blocks at {}",
			columns, sampled, deepest, best);
		return best;
	}

	/** What the test reads from the server: the spawn, and what it needs to sample the generator off-thread. */
	record Probe(BlockPos spawn, int spawnSurface, String spawnBiome, RandomState state, NoiseRouter router,
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
			TerrainField field = TerrainFields.forRandom(random);
			return new Probe(spawn, surface, biome, state, noise.generatorSettings().value().noiseRouter(),
				generator.getBiomeSource(), hollowsSeed, field, level.getMinY());
		}
	}

	static int surface(Probe probe, BlockPos at) {
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
	 * Adds the Diffusicraft biomes missing from the map: the nearest spot on the coarse map within 24 km, or along the
	 * meridian through the spawn towards the equator, 50° of latitude, in a strip {@value #MERIDIAN_STRIP} blocks to
	 * each side, where the tropical biomes are. Each is confirmed on the full terrain within 512 blocks of it. Biomes
	 * the climate of this seed has nowhere near are left out.
	 */
	private static void findFarBiomes(Probe probe, Map<String, BlockPos> found) {
		long start = System.nanoTime();
		int reach = 24 * 1024;
		int meridian = (int) (50 * Geography.KM_PER_DEGREE * 1000 / SIZE.metersPerBlock());
		Map<String, BlockPos> coarse = new TreeMap<>();
		Map<String, Long> distance = new TreeMap<>();
		CoarseClimate.call(() -> {
			BiomeResolver resolver = probe.biomes.createResolver(
				probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			searchCoarse(probe, resolver, found, -reach, reach, 256, -reach, reach, 256, coarse, distance);
			searchCoarse(probe, resolver, found, -MERIDIAN_STRIP, MERIDIAN_STRIP, 1024, reach, meridian, 512, coarse,
				distance);
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
		LOGGER.info("Diffusicraft biomes within 24 km or along the meridian, found in {} s: {}",
			(System.nanoTime() - start) / 1_000_000_000L, found);
	}

	/**
	 * Puts into {@code coarse} the nearest spot to the spawn, in {@code [dx0, dx1] × [dz0, dz1]} around it every
	 * {@code xStep} and {@code zStep} blocks, of each Diffusicraft biome not in {@code found}, unless {@code distance}
	 * holds a nearer one.
	 */
	private static void searchCoarse(Probe probe, BiomeResolver resolver, Map<String, BlockPos> found, int dx0, int dx1,
		int xStep, int dz0, int dz1, int zStep, Map<String, BlockPos> coarse, Map<String, Long> distance) {
		for (int dz = dz0; dz <= dz1; dz += zStep) {
			for (int dx = dx0; dx <= dx1; dx += xStep) {
				int x = probe.spawn.getX() + dx, z = probe.spawn.getZ() + dz;
				String b = biomeAt(resolver, x, 160, z);
				long d = (long) dx * dx + (long) dz * dz;
				if ((BiomeRefiner.TARGETS.contains(b) || SubBiomes.targets().contains(b)) && b.startsWith("diffusicraft:")
					&& !found.containsKey(b)
					&& d < distance.getOrDefault(b, Long.MAX_VALUE)) {
					distance.put(b, d);
					coarse.put(b, new BlockPos(x, 0, z));
				}
			}
		}
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
	static int groundAt(ServerLevel level, BlockPos.MutableBlockPos pos) {
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
