package io.github.elpideus.diffusicraft.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * Picks the game tests to run. {@code -Pdiffusicraft.gametests=TallDimension,Ultra} runs only the tests whose class
 * names start with one of those; without it, all of them run but those that run only when named.
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

	/** Whether the filter names {@code test}, for the tests that run only when asked for. */
	static boolean named(FabricClientGameTest test) {
		String filter = System.getProperty("diffusicraft.gametests", "");
		return !filter.isBlank() && !skips(test);
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

	/** Whether {@code text} is the translation of {@code key}. */
	static boolean hasKey(Component text, String key) {
		return text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key);
	}

	/** Opens the tab titled {@code titleKey} of the tab bar among {@code children}. */
	static void selectTab(List<? extends GuiEventListener> children, String titleKey) {
		for (GuiEventListener child : children) {
			if (child instanceof TabNavigationBar bar) {
				List<Tab> tabs = bar.getTabs();
				for (int i = 0; i < tabs.size(); i++) {
					if (hasKey(tabs.get(i).getTabTitle(), titleKey)) {
						bar.selectTab(i, false);
						return;
					}
				}
			}
		}
		throw new AssertionError("No tab titled " + titleKey);
	}
}
