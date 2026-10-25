package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Diffusicraft's own far terrain ({@code client.lod.FarTerrain}). Creates the world of {@link WorldGenerationGameTest}
 * with a render distance of {@value #RENDER_DISTANCE} chunks, so that everything past some 100 blocks in the views is
 * far terrain, and saves views from above the spawn to {@code build/run/clientGameTest/screenshots}, each once the far
 * terrain has built every tile it wants, one with its line of the debug screen and one with the far terrain off to
 * compare. Then raises a tower of gold
 * {@value #TOWER_DISTANCE} blocks south of the spawn and views it from {@value #TOWER_VIEW_DISTANCE} blocks north of it,
 * out of the chunks' reach, so that the far terrain shows what players build.
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn it off.
 */
public class FarTerrainGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain test");
	static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final int TICKS_PER_MINUTE = 20 * 60;
	static final int RENDER_DISTANCE = 6;
	/** How long the far terrain must have every tile it wants before a view is taken, and how long to wait at most. */
	private static final int SETTLED_TICKS = 3 * 20;
	private static final int BUILD_TIMEOUT_TICKS = 10 * TICKS_PER_MINUTE;
	static final int TOWER_DISTANCE = 480;
	static final int TOWER_RADIUS = 8;
	static final int TOWER_HEIGHT = 80;
	/** How far north of the tower its view is taken from, well out of the chunks' reach. */
	static final int TOWER_VIEW_DISTANCE = 240;
	/** How high over the top of the tower its view is taken from, so that no hill between hides it. */
	static final int TOWER_VIEW_HEIGHT = 160;
	/** The far terrain's line of the debug screen. */
	private static final Identifier DEBUG_ENTRY = Diffusicraft.id("far_terrain");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!prepare(this, context)) {
			return;
		}
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			WorldGenerationGameTest.Probe probe = server.computeOnServer(s -> WorldGenerationGameTest.Probe.of(s.overworld()));
			LOGGER.info("Spawn {} at surface y={}", probe.spawn(), probe.spawnSurface());
			VoxyGameTest.look(context, server);
			BlockPos above = probe.spawn().atY(probe.spawnSurface() + 80);
			view(context, server, connection, above, 0f, 10f, "south");
			view(context, server, connection, above, 180f, 10f, "north");
			view(context, server, connection, above.above(400), 90f, 30f, "high-west");

			// The debug screen shows only with the HUD, which the views hide.
			context.runOnClient(mc -> {
				mc.gui.hud.toggle();
				mc.debugEntries.setStatus(DEBUG_ENTRY, DebugScreenEntryStatus.ALWAYS_ON);
			});
			context.waitTicks(5);
			context.takeScreenshot("diffusicraft-far-terrain-high-west-debug");
			context.runOnClient(mc -> {
				mc.debugEntries.setStatus(DEBUG_ENTRY, DebugScreenEntryStatus.NEVER);
				mc.gui.hud.toggle();
			});

			Resources.get().updateConfig(config -> config.farTerrain = false);
			context.waitTicks(20);
			context.takeScreenshot("diffusicraft-far-terrain-high-west-off");
			Resources.get().updateConfig(config -> config.farTerrain = true);

			BlockPos tower = raiseTower(context, server, connection, probe.spawn().south(TOWER_DISTANCE));
			long before = FarTerrain.uploads();
			viewTower(context, server, connection, tower, "tower");
			if (FarTerrain.uploads() == before) {
				throw new AssertionError("The far terrain built nothing after the tower at " + tower + " was raised");
			}
		}
	}

	/** Raises a tower of gold on the ground at {@code at}, saves its chunks, and returns the top of the tower. */
	private static BlockPos raiseTower(ClientGameTestContext context, TestServerContext server,
									   TestServerConnection connection, BlockPos at) {
		server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d", at.getX(), at.getY() + 200, at.getZ()));
		connection.waitForClientboundPackets();
		connection.waitForChunksRender();
		int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(),
			at.getZ()));
		BlockPos top = at.atY(ground + TOWER_HEIGHT);
		server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gold_block",
			at.getX() - TOWER_RADIUS, ground, at.getZ() - TOWER_RADIUS,
			at.getX() + TOWER_RADIUS, top.getY(), at.getZ() + TOWER_RADIUS));
		server.runOnServer(s -> s.saveEverything(true, true, true));
		LOGGER.info("Raised a tower of gold from {} to y={}", at.atY(ground), top.getY());
		return top;
	}

	/**
	 * Gets a far terrain test ready: false if it is to be skipped, as it is where Distant Horizons or Voxy is installed
	 * or the models are not downloaded. Sets the render distance to {@value #RENDER_DISTANCE} chunks and turns the far
	 * terrain on, on the device {@code diffusicraft.device} names.
	 */
	static boolean prepare(FabricClientGameTest test, ClientGameTestContext context) {
		if (GameTests.skips(test)) {
			return false;
		}
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")
			|| FabricLoader.getInstance().isModLoaded("voxy")) {
			LOGGER.info("Distant Horizons or Voxy is installed, which turns the far terrain off; skipping");
			return false;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping {}",
				test.getClass().getSimpleName());
			return false;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> {
			config.device = device;
			config.farTerrain = true;
		});
		return true;
	}

	/**
	 * Views the tower whose top is {@code top} from {@value #TOWER_VIEW_DISTANCE} blocks north of it, high enough that
	 * no hill between hides it, as {@link #view} does.
	 */
	static void viewTower(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
						  BlockPos top, String name) {
		BlockPos from = top.north(TOWER_VIEW_DISTANCE);
		int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, from.getX(),
			from.getZ()));
		int eye = Math.max(ground + 20, top.getY() + TOWER_VIEW_HEIGHT);
		float pitch = (float) Math.toDegrees(Math.atan2(eye - (top.getY() - TOWER_HEIGHT / 2.0), TOWER_VIEW_DISTANCE));
		view(context, server, connection, from.atY(eye), 0f, pitch, name);
	}

	/** Teleports the player, waits for the chunks and then for the far terrain, and takes a screenshot at noon. */
	static void view(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
					 BlockPos at, float yaw, float pitch, String name) {
		server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(), at.getZ(), yaw,
			pitch));
		connection.waitForClientboundPackets();
		connection.waitForChunksRender();
		long start = System.nanoTime();
		long before = FarTerrain.uploads();
		int[] settled = {0};
		try {
			context.waitFor(mc -> {
				settled[0] = FarTerrain.settled() ? settled[0] + 1 : 0;
				return settled[0] >= SETTLED_TICKS;
			}, BUILD_TIMEOUT_TICKS);
		} catch (AssertionError e) {
			LOGGER.warn("The far terrain was still building for {} after {} s; taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L);
		}
		LOGGER.info("The far terrain built {} tiles for {} in {} s", FarTerrain.uploads() - before, name,
			(System.nanoTime() - start) / 1_000_000_000L);
		server.runCommand("time set noon");
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-far-terrain-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}
}
