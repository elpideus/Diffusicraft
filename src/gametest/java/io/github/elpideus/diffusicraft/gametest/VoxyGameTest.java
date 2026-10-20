package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyLods;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Voxy's far terrain filled from the terrain (see {@code client.compat.voxy.VoxyTerrain}). Creates the world of
 * {@link WorldGenerationGameTest} with a render distance of {@value #RENDER_DISTANCE} chunks, so that everything past
 * some 100 blocks in the views is Voxy's, and saves views from above the spawn to
 * {@code build/run/clientGameTest/screenshots}, each once Voxy has stopped asking for sections. Checks that sections
 * were filled and that none failed.
 *
 * <p>Runs only with Voxy installed: {@code -PdevMods=voxy,sodium}. Needs the Terrain Diffusion models.
 */
public class VoxyGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft Voxy test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 6;
	/** How long the count of filled sections must stand still before a view is taken, and how long to wait at most. */
	private static final int SETTLED_TICKS = 15 * 20;
	private static final int FILL_TIMEOUT_TICKS = 8 * TICKS_PER_MINUTE;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		if (!FabricLoader.getInstance().isModLoaded("voxy")) {
			LOGGER.info("Voxy is not installed; skipping the Voxy test (run with -PdevMods=voxy,sodium)");
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the Voxy test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);
		if (!Resources.get().config().voxyLods) {
			throw new AssertionError("voxyLods is off in the test's config");
		}

		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			WorldGenerationGameTest.Probe probe = world.getServer()
				.computeOnServer(server -> WorldGenerationGameTest.Probe.of(server.overworld()));
			LOGGER.info("Spawn {} at surface y={}", probe.spawn(), probe.spawnSurface());
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			BlockPos above = probe.spawn().atY(probe.spawnSurface() + 80);
			view(context, world, above, 0f, 10f, "south");
			view(context, world, above, 180f, 10f, "north");
			view(context, world, above.above(400), 90f, 30f, "high-west");

			long filled = VoxyLods.filled();
			long failed = VoxyLods.failed();
			LOGGER.info("Voxy sections filled from the terrain: {}, failed: {}", filled, failed);
			if (filled == 0) {
				throw new AssertionError("No section of Voxy's was filled from the terrain");
			}
			if (failed != 0) {
				throw new AssertionError(failed + " of Voxy's sections could not be filled");
			}
		}
	}

	/** Teleports the player, waits for the chunks and then for Voxy, and takes a screenshot at noon. */
	private static void view(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at, float yaw,
							 float pitch, String name) {
		world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(),
			at.getZ(), yaw, pitch));
		world.getConnection().waitForClientboundPackets();
		world.getConnection().waitForChunksRender();
		long start = System.nanoTime();
		long before = VoxyLods.filled();
		long[] last = {before, 0};
		try {
			context.waitFor(mc -> {
				long now = VoxyLods.filled();
				if (now != last[0]) {
					last[0] = now;
					last[1] = 0;
				}
				return now > 0 && ++last[1] >= SETTLED_TICKS;
			}, FILL_TIMEOUT_TICKS);
		} catch (AssertionError e) {
			LOGGER.warn("Voxy still filling for {} after {} s; taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L);
		}
		LOGGER.info("Voxy filled {} sections for {} in {} s", VoxyLods.filled() - before, name,
			(System.nanoTime() - start) / 1_000_000_000L);
		world.getServer().runCommand("time set noon");
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-voxy-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}
}
