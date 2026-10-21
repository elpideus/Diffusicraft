package io.github.elpideus.diffusicraft.client.mixin;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizeSlider;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the size slider beside the world type in the "World" tab while a Diffusicraft world is selected, in the place
 * of "Customize", which no Diffusicraft world has.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
abstract class CreateWorldScreenWorldTabMixin extends GridLayoutTab {
	@Shadow
	@Final
	private Button customizeTypeButton;

	private CreateWorldScreenWorldTabMixin(Component title) {
		super(title);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void diffusicraft$addSizeSlider(CreateWorldScreen screen, CallbackInfo ci) {
		WorldCreationUiState ui = screen.getUiState();
		WorldSizeSlider slider = layout.addChild(new WorldSizeSlider(ui, customizeTypeButton.getWidth()), 0, 1);
		Button customize = customizeTypeButton;
		ui.addListener(state -> diffusicraft$showSize(state, slider, customize));
		diffusicraft$showSize(ui, slider, customize);
	}

	private static void diffusicraft$showSize(WorldCreationUiState ui, WorldSizeSlider slider, Button customize) {
		WorldScale scale = WorldSizes.selected(ui);
		slider.visible = scale != null;
		customize.visible = scale == null;
		if (scale != null) {
			slider.show(scale);
		}
	}
}
