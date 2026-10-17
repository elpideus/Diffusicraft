package io.github.elpideus.diffusicraft.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Diffusicraft Resources..." button under Game Rules, Experiments and Data Packs in the "More" tab. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$MoreTab")
abstract class CreateWorldScreenMoreTabMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void diffusicraft$addResourcesButton(CreateWorldScreen screen, CallbackInfo ci, @Local GridLayout.RowHelper helper) {
		// Same width as the vanilla buttons in this tab.
		helper.addChild(Button.builder(Component.translatable("diffusicraft.resources.button"),
			button -> Minecraft.getInstance().gui.setScreen(ResourceManagerScreen.from(screen))).width(210).build());
	}
}
