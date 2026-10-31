package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.clouds.CloudScale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * The clouds of the Ultra size (1:1), which float about 3000 m above the sea, at y = 4678, and which vanilla's renderer
 * draws as many times larger as they float higher than its own. They are seen over a flat dimension of the Ultra size's
 * type ({@code diffusicraft-gametest:clouds}), so that no terrain is generated and the models are not needed. Checks
 * how much larger they are drawn and that the far plane reaches their end, and saves views of the sky from the ground
 * and from under the clouds ({@code clouds-ultra}, {@code clouds-ultra-high}), with one of the overworld's sky for
 * comparison ({@code clouds-vanilla}). Under a shader pack ({@code -Pdiffusicraft.shaderPack}), the views show the
 * pack's clouds.
 */
public class CloudsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft clouds test");
	private static final ResourceKey<Level> CLOUDS =
		ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("diffusicraft-gametest", "clouds"));
	/** How many times higher above the sea the Ultra size's clouds float than vanilla's: (4678.33 - 63) / 129.33. */
	private static final float ULTRA_SCALE = 35.69F;
	private static final int CLOUD_RANGE = 128;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.renderDistance().set(4));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksRender();
			context.runOnClient(mc -> {
				mc.options.cloudStatus().set(CloudStatus.FANCY);
				mc.options.cloudRange().set(CLOUD_RANGE);
			});
			server.runCommand("gamemode spectator @a");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule advance_weather false");
			server.runCommand("time set 1000");
			server.runCommand("weather clear");

			server.runCommand("tp @a 0.5 -40 0.5 0 -25");
			context.waitTicks(20);
			world.getConnection().waitForChunksRender();
			context.runOnClient(mc -> check(CloudScale.of(mc.level) == 1, "the overworld's clouds keep vanilla's size"));
			screenshot(context, "clouds-vanilla");

			view(context, server, 80, -25);
			float scale = context.computeOnClient(mc -> CloudScale.of(mc.level));
			float far = context.computeOnClient(mc -> depthFar(mc.gameRenderer.mainCamera()));
			LOGGER.info("Ultra: the clouds are drawn {} times larger, the far plane is {} blocks away", scale, far);
			check(Math.abs(scale - ULTRA_SCALE) < 0.01F, "the Ultra size's clouds are drawn " + ULTRA_SCALE
				+ " times larger, not " + scale);
			// A shader pack that draws clouds of its own turns vanilla's off, and the far plane need not reach them
			if (context.computeOnClient(mc -> mc.options.getCloudStatus()) != CloudStatus.OFF) {
				check(far >= CLOUD_RANGE * 16 * scale, "the far plane reaches the end of the clouds, not " + far);
			}
			screenshot(context, "clouds-ultra");

			view(context, server, 4300, -15);
			screenshot(context, "clouds-ultra-high");
		}
	}

	/** Takes the player to {@code y} in the flat dimension of the Ultra size's type, looking up by {@code pitch}. */
	private static void view(ClientGameTestContext context, TestServerContext server, int y, float pitch) {
		server.runCommand("execute in " + CLOUDS.identifier() + " run tp @a 0.5 " + y + " 0.5 0 " + pitch);
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == CLOUDS
			&& Math.abs(mc.player.getY() - y) < 2);
		context.waitTicks(40);
	}

	private static float depthFar(Camera camera) {
		try {
			Field field = Camera.class.getDeclaredField("depthFar");
			field.setAccessible(true);
			return field.getFloat(camera);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		LOGGER.info("Screenshot: {}", context.takeScreenshot(name).toAbsolutePath());
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Expected " + what);
		}
	}
}
