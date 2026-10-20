package io.github.elpideus.diffusicraft.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the game tests to run. {@code -Pdiffusicraft.gametests=TallDimension,Earth} runs only the tests whose class
 * names start with one of those; without it, all of them run.
 */
final class GameTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");

	private GameTests() {
	}

	/** Whether the filter leaves {@code test} out. The test then returns at once. */
	static boolean skips(FabricClientGameTest test) {
		String filter = System.getProperty("diffusicraft.gametests", "");
		if (filter.isBlank()) {
			return false;
		}
		String name = test.getClass().getSimpleName();
		for (String prefix : filter.split(",")) {
			if (!prefix.isBlank() && name.startsWith(prefix.strip())) {
				return false;
			}
		}
		LOGGER.info("Skipping {}: not in diffusicraft.gametests", name);
		return true;
	}

	/**
	 * Waits until the section holding {@code shown} has been built, then until nothing is left to build. Fabric's
	 * {@code waitForChunksRender} alone returns at once under Sodium, which counts its terrain as rendered whenever
	 * nothing is queued, including before it queues the first section. Pick a block that is not air.
	 */
	static void waitForRender(ClientGameTestContext context, TestServerConnection connection, BlockPos shown) {
		context.waitFor(mc -> mc.levelRenderer.isSectionCompiledAndVisible(shown, 0));
		connection.waitForChunksRender();
	}
}
