package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Where the far terrain meets the chunks, in a 1:1 world at a render distance of {@value #RENDER_DISTANCE}: nothing
 * may be missing between the two. Saves to {@code build/run/clientGameTest/screenshots} views taken while flying from
 * the spawn {@value #LOW_HEIGHT} blocks over low land, as players do, where a shader pack's own test of the distance
 * would leave out the far terrain round the camera before the chunks are drawn. Then teleports {@value #TELEPORTS}
 * times onto fresh ground, taking a screenshot as soon as the far terrain is built there, and another a few frames
 * after hopping {@value #HOP} blocks on, past the chunks drawn, where the far terrain stands and the chunks are not
 * built yet. Last, finds the steepest mountainside near the spawn, whose mountains rise well past the rows of sections
 * the world draws around the camera, and takes a view from high over its summit, whose ground lies below those rows;
 * one from the valley at its foot, whose slope rises above them; and four while flying from over the valley past the
 * summit faster than its chunks are built.
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn it off.
 */
public class FarTerrainGapsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain gaps test");
	private static final String SEED = "20261018";
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 8;
	/** How many coarse pixels around the spawn, in each direction, the steepest mountainside is looked for in. */
	private static final int SEARCH = 4;
	/** How far apart the columns are that the steepest drop is looked for between, and the furthest such drop. */
	private static final int SLOPE_STEP = 64;
	private static final int SLOPE_REACH = 4;
	/** How high over the summit the first view is taken from: more than the 8 rows of sections drawn below it. */
	private static final int ABOVE = 220;
	/** How many blocks over the summit the flight passes, and how far before the valley it starts. */
	private static final int FLIGHT_HEIGHT = 40;
	private static final int FLIGHT_RUN_UP = 200;
	/** Blocks flown per tick: some 30 blocks a second, faster than the chunks are built at 1:1. */
	private static final double FLIGHT_SPEED = 1.5;
	private static final int FLIGHT_TICKS = 12 * 20;
	/** How many blocks the low flight passes over the ground or the sea, the highest within how many ticks of it. */
	private static final int LOW_HEIGHT = 4;
	private static final int LOW_SMOOTHING = 8;
	/** How high over the sea the ground the low flight looks for may rise. */
	private static final int LOW_LAND = 40;
	/** How many teleports onto fresh ground, and how far apart: further than the far terrain and the chunks reach. */
	private static final int TELEPORTS = 3;
	private static final int TELEPORT_STRIDE = 3000;
	/** How far on from each teleport the hop goes, along the view, and how many ticks after it the screenshot is taken. */
	private static final int HOP = 256;
	private static final int HOP_TICKS = 5;
	private static final int SETTLED_TICKS = 3 * 20;
	private static final int VIEW_TIMEOUT_TICKS = 5 * TICKS_PER_MINUTE;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		if (FabricLoader.getInstance().isModLoaded("distanthorizons") || FabricLoader.getInstance().isModLoaded("voxy")) {
			LOGGER.info("Distant Horizons or Voxy is installed, which turns the far terrain off; skipping");
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the far terrain gaps test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> {
			config.device = device;
			config.farTerrain = true;
		});

		try (TestSingleplayerContext world = GameTests.createAnyway(context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, WorldScale.ULTRA);
			}))) {
			TestServerContext server = world.getServer();
			WorldGenerationGameTest.Probe probe = server.computeOnServer(s -> WorldGenerationGameTest.Probe.of(s.overworld()));
			VoxyGameTest.look(context, server);
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");

			int sea = server.computeOnServer(s -> s.overworld().getSeaLevel());
			lowFlight(context, world, probe, sea);
			for (int k = 1; k <= TELEPORTS; k++) {
				BlockPos column = probe.spawn().offset(k * TELEPORT_STRIDE, 0, k * TELEPORT_STRIDE / 2);
				teleport(context, world, probe, sea, column, "teleport-" + k);
			}

			BlockPos[] slope = steepest(probe);
			BlockPos summit = slope[0], valley = slope[1];
			int summitY = WorldGenerationGameTest.surface(probe, summit);
			int valleyY = WorldGenerationGameTest.surface(probe, valley);
			LOGGER.info("Summit at {}, {}, y = {}; valley at {}, {}, y = {}", summit.getX(), summit.getZ(), summitY,
				valley.getX(), valley.getZ(), valleyY);
			double dx = summit.getX() - valley.getX(), dz = summit.getZ() - valley.getZ();
			double run = Math.max(1, Math.hypot(dx, dz));
			// Minecraft's yaw: 0 looks south, along +z, and 90 west, along -x.
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			view(context, world, summit.offset((int) (-dx / run * 120), 0, (int) (-dz / run * 120)).atY(summitY + ABOVE),
				yaw, 45f, "above");
			float pitch = (float) -Math.toDegrees(Math.atan2((summitY - valleyY) * 0.6, run));
			view(context, world, valley.atY(valleyY + 3), yaw, pitch, "valley");
			BlockPos from = valley.offset((int) (-dx / run * FLIGHT_RUN_UP), 0, (int) (-dz / run * FLIGHT_RUN_UP))
				.atY(summitY + FLIGHT_HEIGHT);
			int[] heights = new int[FLIGHT_TICKS + 1];
			Arrays.fill(heights, from.getY());
			fly(context, world, from, dx / run, dz / run, yaw, 12f, heights, "flight");
		}
	}

	/**
	 * Flies from the spawn {@value #LOW_HEIGHT} blocks over the ground or the sea, following it, in whichever of eight
	 * directions passes over the most land at most {@value #LOW_LAND} blocks over the sea.
	 */
	private static void lowFlight(ClientGameTestContext context, TestSingleplayerContext world,
								  WorldGenerationGameTest.Probe probe, int sea) {
		BlockPos from = probe.spawn();
		int[][] grounds = IntStream.range(0, 8).mapToObj(direction -> IntStream.rangeClosed(0, FLIGHT_TICKS)
				.parallel()
				.map(tick -> Math.max(sea, WorldGenerationGameTest.surface(probe, along(from, direction, tick))))
				.toArray())
			.toArray(int[][]::new);
		int direction = IntStream.range(0, 8).boxed()
			.max(Comparator.comparingLong(d -> Arrays.stream(grounds[d])
				.filter(y -> y > sea && y <= sea + LOW_LAND)
				.count()))
			.orElseThrow();
		int[] heights = new int[FLIGHT_TICKS + 1];
		for (int tick = 0; tick <= FLIGHT_TICKS; tick++) {
			int highest = sea;
			for (int k = Math.max(0, tick - LOW_SMOOTHING); k <= Math.min(FLIGHT_TICKS, tick + LOW_SMOOTHING); k++) {
				highest = Math.max(highest, grounds[direction][k]);
			}
			heights[tick] = highest + LOW_HEIGHT;
		}
		double angle = direction * Math.PI / 4;
		LOGGER.info("Flying low from the spawn at {}, {}, {}° from east, over ground from y = {} to {}", from.getX(),
			from.getZ(), direction * 45, Arrays.stream(grounds[direction]).min().orElseThrow(),
			Arrays.stream(grounds[direction]).max().orElseThrow());
		fly(context, world, from.atY(heights[0]), Math.cos(angle), Math.sin(angle),
			(float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))), 10f, heights, "low");
	}

	/** Where the low flight from {@code from} in {@code direction}, eighths of a turn from east, is at {@code tick}. */
	private static BlockPos along(BlockPos from, int direction, int tick) {
		double angle = direction * Math.PI / 4;
		double run = tick * FLIGHT_SPEED;
		return from.offset((int) Math.round(Math.cos(angle) * run), 0, (int) Math.round(Math.sin(angle) * run));
	}

	/**
	 * Teleports the player to stand on the ground or the sea at {@code column}, and takes a screenshot as soon as the
	 * far terrain is built there, whether the chunks are or not. Then hops {@value #HOP} blocks south, the way the
	 * camera looks, and takes another a few frames after, before the chunks there are built.
	 */
	private static void teleport(ClientGameTestContext context, TestSingleplayerContext world,
								 WorldGenerationGameTest.Probe probe, int sea, BlockPos column, String name) {
		int y = standOn(world, probe, sea, column);
		// A frame from the new place, for the far terrain to know what it wants there.
		context.waitTicks(3);
		long start = System.nanoTime();
		try {
			context.waitFor(mc -> FarTerrain.built(), VIEW_TIMEOUT_TICKS);
		} catch (AssertionError e) {
			LOGGER.warn("The far terrain at {} was not built after {} s; taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L);
		}
		screenshot(context, name, column, y, start);
		BlockPos on = column.offset(0, 0, HOP);
		int hopY = standOn(world, probe, sea, on);
		start = System.nanoTime();
		context.waitTicks(HOP_TICKS);
		screenshot(context, name + "-hop", on, hopY, start);
	}

	/** Teleports the player to stand on the ground or the sea at {@code column}, looking south, and returns its y. */
	private static int standOn(TestSingleplayerContext world, WorldGenerationGameTest.Probe probe, int sea,
							   BlockPos column) {
		int y = Math.max(sea, WorldGenerationGameTest.surface(probe, column)) + 2;
		world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %d %d 0 15", column.getX(), y,
			column.getZ()));
		world.getConnection().waitForClientboundPackets();
		return y;
	}

	/** Takes a screenshot of the view from {@code column}, and logs what is still to be drawn. */
	private static void screenshot(ClientGameTestContext context, String name, BlockPos column, int y, long start) {
		String pending = context.computeOnClient(WorldGenerationGameTest::pendingWork);
		Path file = context.takeScreenshot("diffusicraft-far-terrain-gaps-" + name);
		LOGGER.info("{} at {}, {}, y = {}, after {} s, with {}: {}", name, column.getX(), column.getZ(), y,
			(System.nanoTime() - start) / 1_000_000_000L, pending.isEmpty() ? "every chunk drawn" : pending,
			file.toAbsolutePath());
	}

	/**
	 * The summit and the foot of the steepest mountainside near the spawn: in the coarse pixel whose elevation differs
	 * most from one of its neighbours', the two columns at most {@value #SLOPE_REACH} × {@value #SLOPE_STEP} blocks
	 * apart whose ground differs most.
	 */
	private static BlockPos[] steepest(WorldGenerationGameTest.Probe probe) {
		WorldScale scale = WorldScale.ULTRA;
		TerrainField field = probe.field();
		int coarse = field.coarseScale();
		double i0 = scale.toPixel(probe.spawn().getZ()), j0 = scale.toPixel(probe.spawn().getX());
		int side = 2 * SEARCH + 1;
		int best = IntStream.range(0, side * side).parallel().boxed()
			.max(Comparator.comparingDouble(k -> {
				double i = i0 + (k / side - SEARCH) * coarse, j = j0 + (k % side - SEARCH) * coarse;
				float here = field.coarseElevation(i, j);
				double drop = 0;
				for (int di = -1; di <= 1; di++) {
					for (int dj = -1; dj <= 1; dj++) {
						drop = Math.max(drop, Math.abs(here - field.coarseElevation(i + di * coarse, j + dj * coarse)));
					}
				}
				return drop;
			}))
			.orElseThrow();
		int blocks = (int) Math.round(coarse / scale.pixelsPerBlock());
		BlockPos centre = probe.spawn().offset((best % side - SEARCH) * blocks, 0, (best / side - SEARCH) * blocks);
		int columns = blocks / SLOPE_STEP + 1;
		BlockPos corner = centre.offset(-blocks / 2, 0, -blocks / 2);
		int[] ground = IntStream.range(0, columns * columns).parallel()
			.map(k -> WorldGenerationGameTest.surface(probe, corner.offset(k % columns * SLOPE_STEP, 0, k / columns * SLOPE_STEP)))
			.toArray();
		long[] steepest = {0, 0, Integer.MIN_VALUE};
		for (int k = 0; k < ground.length; k++) {
			int x = k % columns, z = k / columns;
			for (int dz = -SLOPE_REACH; dz <= SLOPE_REACH; dz++) {
				for (int dx = -SLOPE_REACH; dx <= SLOPE_REACH; dx++) {
					int nx = x + dx, nz = z + dz;
					if (nx >= 0 && nz >= 0 && nx < columns && nz < columns && ground[k] - ground[nz * columns + nx] > steepest[2]) {
						steepest[0] = k;
						steepest[1] = nz * columns + nx;
						steepest[2] = ground[k] - ground[nz * columns + nx];
					}
				}
			}
		}
		Comparator<BlockPos> height = Comparator.comparingInt(at -> WorldGenerationGameTest.surface(probe, at));
		BlockPos summit = corner.offset((int) steepest[0] % columns * SLOPE_STEP, 0, (int) steepest[0] / columns * SLOPE_STEP);
		BlockPos valley = corner.offset((int) steepest[1] % columns * SLOPE_STEP, 0, (int) steepest[1] / columns * SLOPE_STEP);
		return new BlockPos[]{extreme(summit, SLOPE_STEP / 2, 1, height), extreme(valley, SLOPE_STEP / 2, 4, height.reversed())};
	}

	/** The greatest by {@code order} of the columns {@code step} blocks apart within {@code radius} of {@code centre}. */
	private static BlockPos extreme(BlockPos centre, int radius, int step, Comparator<BlockPos> order) {
		int side = 2 * radius / step + 1;
		return IntStream.range(0, side * side).parallel()
			.mapToObj(k -> centre.offset((k % side) * step - radius, 0, (k / side) * step - radius))
			.max(order)
			.orElseThrow();
	}

	/**
	 * Flies from {@code from} along {@code (dx, dz)} at {@value #FLIGHT_SPEED} blocks a tick, as a player in spectator
	 * mode would, at {@code heights[tick]} after each tick, and takes a screenshot every quarter of the way without
	 * waiting for anything.
	 */
	private static void fly(ClientGameTestContext context, TestSingleplayerContext world, BlockPos from, double dx,
							double dz, float yaw, float pitch, int[] heights, String name) {
		view(context, world, from, yaw, pitch, name + "-start");
		for (int tick = 1; tick <= FLIGHT_TICKS; tick++) {
			double run = tick * FLIGHT_SPEED;
			world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f %.1f",
				from.getX() + 0.5 + dx * run, heights[tick], from.getZ() + 0.5 + dz * run, yaw, pitch));
			context.waitTick();
			if (tick % (FLIGHT_TICKS / 4) == 0) {
				String pending = context.computeOnClient(WorldGenerationGameTest::pendingWork);
				Path file = context.takeScreenshot("diffusicraft-far-terrain-gaps-" + name + "-" + tick);
				LOGGER.info("Screenshot after {} blocks of flight, with {}: {}", Math.round(run),
					pending.isEmpty() ? "every chunk drawn" : pending, file.toAbsolutePath());
			}
		}
	}

	/** Teleports the player, waits for the chunks and the far terrain as far as they get, and takes a screenshot. */
	private static void view(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at, float yaw,
							 float pitch, String name) {
		world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(),
			at.getZ(), yaw, pitch));
		world.getConnection().waitForClientboundPackets();
		long start = System.nanoTime();
		int[] settled = {0};
		try {
			context.waitFor(mc -> {
				boolean idle = FarTerrain.built() && WorldGenerationGameTest.pendingWork(mc).isEmpty();
				settled[0] = idle ? settled[0] + 1 : 0;
				return settled[0] >= SETTLED_TICKS;
			}, VIEW_TIMEOUT_TICKS);
		} catch (AssertionError e) {
			LOGGER.warn("The chunks or the far terrain for {} were not all built after {} s ({}); taking the screenshot "
				+ "anyway", name, (System.nanoTime() - start) / 1_000_000_000L,
				context.computeOnClient(WorldGenerationGameTest::pendingWork));
		}
		LOGGER.info("{} at {} ready in {} s", name, at, (System.nanoTime() - start) / 1_000_000_000L);
		Path file = context.takeScreenshot("diffusicraft-far-terrain-gaps-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}
}
