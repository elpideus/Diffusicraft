package io.github.elpideus.diffusicraft.client.mixin;

import io.github.elpideus.diffusicraft.client.DiffusicraftClient;
import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

/** Adds the resource screen to the screens shown once the game has loaded, after vanilla's onboarding screens. */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "addInitialScreens", at = @At("RETURN"))
	private void diffusicraft$addResourceScreen(List<Function<Runnable, Screen>> screens, CallbackInfoReturnable<Boolean> cir) {
		if (DiffusicraftClient.showResourcesAtLaunch()) {
			screens.add(ResourceManagerScreen::atLaunch);
		}
	}
}
