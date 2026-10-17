package io.github.elpideus.diffusicraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;
import io.github.elpideus.diffusicraft.runtime.Resources;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Walks through every way into the resource screen (at launch, Create World > More) and saves a screenshot of each
 * step to {@code build/run/clientGameTest/screenshots}. Nothing is downloaded: install dialogs are cancelled.
 *
 * <p>The launch screen appears on its own when something is missing. Run with an empty data directory
 * ({@code XDG_DATA_HOME=/some/empty/dir} on Linux) to see a fresh install; otherwise the test opens the screen itself
 * and shows what is installed on this machine.
 */
public class ResourceScreensGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null && Resources.get().hardware().isDone(), 20 * 60);

		// 1. At launch.
		boolean shownAtLaunch = context.computeOnClient(mc -> mc.gui.screen() instanceof ResourceManagerScreen);
		LOGGER.info("Resource screen shown at launch: {} (setup suggested: {})", shownAtLaunch, Resources.get().setupSuggestedAtLaunch());
		if (!shownAtLaunch) {
			context.setScreen(() -> ResourceManagerScreen.atLaunch(() -> Minecraft.getInstance().gui.setScreen(new TitleScreen())));
		}
		context.waitTicks(5);
		screenshot(context, "launch");
		context.clickScreenButton("diffusicraft.resources.action.later");
		context.waitForScreen(TitleScreen.class);

		// 2. From world creation: More tab > Diffusicraft Resources...
		context.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		context.waitForScreen(CreateWorldScreen.class);
		context.runOnClient(mc -> selectTab(mc.gui.screen().children(), "createWorld.tab.more.title"));
		context.waitTicks(2);
		screenshot(context, "create-world-more");
		context.clickScreenButton("diffusicraft.resources.button");
		context.waitForScreen(ResourceManagerScreen.class);
		context.waitTicks(5);
		screenshot(context, "from-create-world");

		// The lower rows (settings, storage) at a smaller GUI scale, scrolled to the bottom.
		context.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.resizeGui();
		});
		context.waitTicks(2);
		screenshot(context, "gui-scale-2");
		context.runOnClient(mc -> scrollToBottom(mc.gui.screen().children()));
		context.waitTicks(2);
		screenshot(context, "gui-scale-2-bottom");
		context.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			mc.resizeGui();
		});

		// 3. Installing a runtime first lists the licenses to accept. Cancel, so nothing is downloaded.
		if (context.computeOnClient(mc -> press(mc.gui.screen().children(), "diffusicraft.resources.action.install"))) {
			context.waitForScreen(ConfirmScreen.class);
			context.waitTicks(2);
			screenshot(context, "install-licenses");
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(ResourceManagerScreen.class);
		} else {
			LOGGER.info("Every GPU runtime is installed or unavailable here; no install dialog to show");
		}

		context.clickScreenButton("gui.done");
		context.waitForScreen(CreateWorldScreen.class);
		context.setScreen(TitleScreen::new);
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		// Opening a screen centres the cursor; park it in a corner so that no row tooltip covers the screenshot.
		context.getInput().setCursorPos(0, 0);
		context.waitTick();
		Path file = context.takeScreenshot("diffusicraft-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}

	private static boolean hasKey(Component text, String key) {
		return text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key);
	}

	private static void selectTab(List<? extends GuiEventListener> children, String titleKey) {
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

	private static void scrollToBottom(List<? extends GuiEventListener> children) {
		for (GuiEventListener child : children) {
			if (child instanceof AbstractScrollArea area) {
				area.setScrollAmount(Double.MAX_VALUE);
			}
		}
	}

	/** Presses the first visible, active button labelled {@code key}, searching nested lists too, as Enter would. */
	private static boolean press(List<? extends GuiEventListener> children, String key) {
		for (GuiEventListener child : children) {
			if (child instanceof AbstractButton button && button.visible && button.active && hasKey(button.getMessage(), key)) {
				button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				return true;
			}
			if (child instanceof ContainerEventHandler container && press(container.children(), key)) {
				return true;
			}
		}
		return false;
	}
}
