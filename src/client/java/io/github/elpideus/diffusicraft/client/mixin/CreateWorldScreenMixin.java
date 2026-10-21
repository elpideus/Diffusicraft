package io.github.elpideus.diffusicraft.client.mixin;

import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;
import io.github.elpideus.diffusicraft.client.worldgen.FarTerrainLimits;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizeSlider;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import java.util.List;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the resource screen instead of creating a Diffusicraft world while the models are missing, which would
 * otherwise fail at the first chunk, and asks before creating a world taller than the installed far-terrain mods hold.
 */
@Mixin(CreateWorldScreen.class)
abstract class CreateWorldScreenMixin extends Screen {
	/** The size the player chose to create although their far-terrain mods cannot hold it whole. */
	@Unique
	private @Nullable WorldScale diffusicraft$acceptedSize;

	@Shadow
	public abstract WorldCreationUiState getUiState();

	@Shadow
	private void onCreate() {
	}

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

	@Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$confirmFarTerrain(CallbackInfo ci) {
		WorldScale scale = WorldSizes.selected(getUiState());
		List<Component> warnings = scale == null ? List.of() : FarTerrainLimits.warnings(scale);
		if (!warnings.isEmpty() && scale != diffusicraft$acceptedSize) {
			minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
				minecraft.gui.setScreen(this);
				if (accepted) {
					diffusicraft$acceptedSize = scale;
					onCreate();
				}
			}, Component.translatable("diffusicraft.limits.title"), diffusicraft$message(warnings),
				Component.translatable("diffusicraft.limits.create"), Component.translatable("gui.back")));
			ci.cancel();
		}
	}

	@Unique
	private static Component diffusicraft$message(List<Component> warnings) {
		MutableComponent message = Component.empty();
		for (Component warning : warnings) {
			message.append(warning).append("\n\n");
		}
		return message.append(Component.translatable("diffusicraft.limits.smaller",
			WorldSizeSlider.name(FarTerrainLimits.largestWhole())));
	}
}
