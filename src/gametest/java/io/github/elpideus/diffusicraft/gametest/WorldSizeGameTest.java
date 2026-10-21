package io.github.elpideus.diffusicraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.elpideus.diffusicraft.client.worldgen.FarTerrainLimits;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizeSlider;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

/**
 * Picks a size with the slider in the "World" tab of the Create World screen, by mouse and by the arrow keys, and checks
 * the world type follows. With Distant Horizons or Voxy installed, Create on Ultra must ask first. Nothing is created;
 * screenshots go to {@code build/run/clientGameTest/screenshots}.
 */
public class WorldSizeGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		context.waitForScreen(CreateWorldScreen.class);
		context.runOnClient(mc -> GameTests.selectTab(mc.gui.screen().children(), "createWorld.tab.world.title"));
		context.waitTicks(2);
		expect(context, WorldScale.DEFAULT);
		boolean customizeShown = context.computeOnClient(mc -> {
			AbstractButton customize = find(mc, AbstractButton.class, "selectWorld.customizeType");
			return customize != null && customize.visible;
		});
		if (customizeShown) {
			throw new AssertionError("\"Customize\" shows beside a Diffusicraft world");
		}

		// The left end of the slider is the smallest size; each arrow press moves one size.
		double[] left = context.computeOnClient(mc -> {
			WorldSizeSlider slider = slider(mc);
			double toWindow = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[]{(slider.getX() + 2) * toWindow, (slider.getY() + slider.getHeight() / 2.0) * toWindow};
		});
		context.getInput().setCursorPos(left[0], left[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		expect(context, WorldScale.VANILLA);
		context.getInput().pressKey(InputConstants.KEY_RIGHT);
		context.getInput().pressKey(InputConstants.KEY_RIGHT);
		context.waitTick();
		expect(context, WorldScale.LARGE);
		// Hovered long enough for the tooltip.
		context.waitTicks(20);
		screenshot(context, "world-size-large");
		for (int i = WorldScale.LARGE.ordinal(); i < WorldScale.ULTRA.ordinal(); i++) {
			context.getInput().pressKey(InputConstants.KEY_RIGHT);
		}
		context.waitTicks(20);
		expect(context, WorldScale.ULTRA);
		screenshot(context, "world-size-ultra");

		// Too tall for an installed far-terrain mod: Create asks first. Without the models it opens the resource
		// screen instead, before asking.
		boolean asks = context.computeOnClient(mc -> !FarTerrainLimits.warnings(WorldScale.ULTRA).isEmpty());
		if (asks && Resources.get().status() != null && Resources.get().status().modelsReady()) {
			context.clickScreenButton("selectWorld.create");
			context.waitForScreen(ConfirmScreen.class);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(2);
			screenshot(context, "world-size-confirm");
			context.clickScreenButton("gui.back");
			context.waitForScreen(CreateWorldScreen.class);
		} else {
			LOGGER.info("No far-terrain mod limits Ultra here, or the models are missing: no question on Create");
		}
		context.setScreen(TitleScreen::new);
	}

	private static void expect(ClientGameTestContext context, WorldScale scale) {
		String problem = context.computeOnClient(mc -> {
			WorldScale selected = WorldSizes.selected(((CreateWorldScreen) mc.gui.screen()).getUiState());
			WorldSizeSlider slider = slider(mc);
			String message = slider.getMessage().getString();
			if (selected != scale) {
				return "The world is " + selected + ", not " + scale;
			}
			if (!slider.visible || !message.contains(WorldSizeSlider.name(scale).getString())) {
				return "The slider reads \"" + message + "\" (visible: " + slider.visible + ") for " + scale;
			}
			return null;
		});
		if (problem != null) {
			throw new AssertionError(problem);
		}
		LOGGER.info("Size picked: {}", scale);
	}

	private static WorldSizeSlider slider(Minecraft mc) {
		WorldSizeSlider slider = find(mc, WorldSizeSlider.class, null);
		if (slider == null) {
			throw new AssertionError("No size slider in the World tab");
		}
		return slider;
	}

	/** The first widget of {@code type} on the screen, labelled {@code key} if it is not null. */
	private static <T extends GuiEventListener> @Nullable T find(Minecraft mc, Class<T> type, @Nullable String key) {
		for (GuiEventListener child : mc.gui.screen().children()) {
			if (type.isInstance(child) && (key == null || child instanceof AbstractButton button
				&& GameTests.hasKey(button.getMessage(), key))) {
				return type.cast(child);
			}
		}
		return null;
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		Path file = context.takeScreenshot("diffusicraft-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}
}
