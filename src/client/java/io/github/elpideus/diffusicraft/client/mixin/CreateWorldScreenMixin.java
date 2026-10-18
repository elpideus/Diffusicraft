package io.github.elpideus.diffusicraft.client.mixin;

import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the resource screen instead of creating a Diffusicraft world while the models are missing, which would
 * otherwise fail at the first chunk.
 */
@Mixin(CreateWorldScreen.class)
abstract class CreateWorldScreenMixin extends Screen {
	@Shadow
	public abstract WorldCreationUiState getUiState();

	private CreateWorldScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$requireModels(CallbackInfo ci) {
		Resources.Status status = Resources.get().status();
		// Before the first check finishes the status is unknown; the generator then waits or explains itself.
		if (status != null && !status.modelsReady()
			&& DiffusicraftWorldgen.usesModels(getUiState().getSettings().selectedDimensions().overworld())) {
			minecraft.gui.setScreen(ResourceManagerScreen.from(this));
			ci.cancel();
		}
	}
}
