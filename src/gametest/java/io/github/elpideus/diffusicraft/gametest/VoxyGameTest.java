package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyLods;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;

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
	static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	static final int TICKS_PER_MINUTE = 20 * 60;
	static final int RENDER_DISTANCE = 6;
	/** How long the count of filled sections must stand still before a view is taken, and how long to wait at most. */
	private static final int SETTLED_TICKS = 15 * 20;
	private static final int FILL_TIMEOUT_TICKS = 8 * TICKS_PER_MINUTE;

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
			WorldGenerationGameTest.Probe probe = world.getServer()
				.computeOnServer(server -> WorldGenerationGameTest.Probe.of(server.overworld()));
			LOGGER.info("Spawn {} at surface y={}", probe.spawn(), probe.spawnSurface());
			look(context, world.getServer());
			BlockPos above = probe.spawn().atY(probe.spawnSurface() + 80);
			view(context, world.getServer(), world.getConnection(), above, 0f, 10f, "south");
			view(context, world.getServer(), world.getConnection(), above, 180f, 10f, "north");
			view(context, world.getServer(), world.getConnection(), above.above(400), 90f, 30f, "high-west");

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

	/**
	 * Whether {@code test} is to run, with Voxy installed and the models downloaded, and if so sets the window, the
	 * render distance and the device up for it.
	 */
	static boolean prepare(FabricClientGameTest test, ClientGameTestContext context) {
		if (GameTests.skips(test)) {
			return false;
		}
		String name = test.getClass().getSimpleName();
		if (!FabricLoader.getInstance().isModLoaded("voxy")) {
			LOGGER.info("Voxy is not installed; skipping {} (run with -PdevMods=voxy,sodium)", name);
			return false;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping {}", name);
			return false;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		// -Pdiffusicraft.gpuMemoryLimitMb keeps the models within what other programs leave free on the GPU.
		Resources.get().updateConfig(config -> {
			config.device = device;
			config.gpuMemoryLimitMb = Integer.getInteger("diffusicraft.gpuMemoryLimitMb", config.gpuMemoryLimitMb);
		});
		if (!Resources.get().config().voxyLods) {
			throw new AssertionError("voxyLods is off in the test's config");
		}
		return true;
	}

	/** Clears the weather, makes the player a spectator and hides the HUD. */
	static void look(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("weather clear");
		server.runCommand("gamemode spectator @a");
		context.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
	}

	/** Teleports the player, waits for the chunks and then for Voxy, and takes a screenshot at noon. */
	static void view(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
					 BlockPos at, float yaw, float pitch, String name) {
		view(context, server, connection, at, yaw, pitch, name, SETTLED_TICKS, FILL_TIMEOUT_TICKS, () -> true);
	}

	/**
	 * As {@link #view(ClientGameTestContext, TestServerContext, TestServerConnection, BlockPos, float, float, String)},
	 * waiting until no section has been filled for {@code settledTicks} while {@code idle} held, or for
	 * {@code timeoutTicks} at most.
	 */
	static void view(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
					 BlockPos at, float yaw, float pitch, String name, int settledTicks, int timeoutTicks,
					 BooleanSupplier idle) {
		server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d %.1f %.1f", at.getX(), at.getY(), at.getZ(), yaw,
			pitch));
		connection.waitForClientboundPackets();
		connection.waitForChunksRender();
		long start = System.nanoTime();
		long before = VoxyLods.filled();
		long[] last = {before, 0};
		try {
			context.waitFor(mc -> {
				long now = VoxyLods.filled();
				if (now != last[0] || !idle.getAsBoolean()) {
					last[0] = now;
					last[1] = 0;
				}
				return now > before && ++last[1] >= settledTicks;
			}, timeoutTicks);
		} catch (AssertionError e) {
			LOGGER.warn("Voxy still filling for {} after {} s; taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L);
		}
		LOGGER.info("Voxy filled {} sections for {} in {} s", VoxyLods.filled() - before, name,
			(System.nanoTime() - start) / 1_000_000_000L);
		server.runCommand("time set noon");
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-voxy-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}
}
