package io.github.elpideus.diffusicraft.client.worldgen;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Picks the size of a Diffusicraft world on the Create World screen, in six steps from Vanilla (1:32) on the left to
 * Ultra (1:1) on the right. Its tooltip says what the size is like and what the installed far-terrain mods will do with
 * it; the message turns yellow when they cannot hold it whole.
 */
public final class WorldSizeSlider extends AbstractSliderButton {
	/** From the smallest size up. */
	private static final WorldScale[] SIZES = WorldScale.values();
	private static final int STEPS = SIZES.length - 1;

	private final WorldCreationUiState ui;

	public WorldSizeSlider(WorldCreationUiState ui, int width) {
		super(0, 0, width, DEFAULT_HEIGHT, Component.empty(), 0);
		this.ui = ui;
		show(WorldScale.DEFAULT);
	}

	/** Moves the slider to a size picked elsewhere, without picking it again. */
	public void show(WorldScale scale) {
		value = (double) scale.ordinal() / STEPS;
		updateMessage();
	}

	public static Component name(WorldScale scale) {
		return Component.translatable("diffusicraft.size." + scale.getSerializedName());
	}

	private WorldScale scale() {
		return SIZES[(int) Math.round(value * STEPS)];
	}

	@Override
	protected void setValue(double newValue) {
		super.setValue((double) Math.round(Math.clamp(newValue, 0.0, 1.0) * STEPS) / STEPS);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// One size per arrow press, where a vanilla slider moves by a pixel.
		if (canChangeValue && (event.isLeft() || event.isRight())) {
			setValue(value + (event.isLeft() ? -1.0 : 1.0) / STEPS);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateMessage() {
		WorldScale scale = scale();
		List<Component> warnings = FarTerrainLimits.warnings(scale);
		MutableComponent message = Component.translatable("diffusicraft.size.slider", name(scale), "1:" + scale.ratio());
		setMessage(warnings.isEmpty() ? message : message.withStyle(ChatFormatting.YELLOW));
		MutableComponent tooltip = Component.translatable("diffusicraft.size.info", scale.ratio(), scale.minY(),
			scale.maxY(), Math.round(scale.maxSurfaceY()));
		for (Component warning : warnings) {
			tooltip.append("\n\n").append(warning.copy().withStyle(ChatFormatting.YELLOW));
		}
		setTooltip(Tooltip.create(tooltip));
	}

	@Override
	protected void applyValue() {
		WorldScale scale = scale();
		if (WorldSizes.selected(ui) != scale) {
			WorldSizes.select(ui, scale);
		}
	}
}
