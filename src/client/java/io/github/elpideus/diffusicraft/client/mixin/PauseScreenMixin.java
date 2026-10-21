package io.github.elpideus.diffusicraft.client.mixin;

import io.github.elpideus.diffusicraft.client.screen.ReliefMapButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Adds the relief map's button to the pause menu's row of icon buttons. The row is the only {@link LinearLayout} that
 * {@code createPauseMenu} adds to its grid; the same call adds the Back to Game button.
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {
	@ModifyArg(method = "createPauseMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"), index = 0)
	private LayoutElement diffusicraft$addReliefMapButton(LayoutElement child) {
		if (child instanceof LinearLayout iconButtonRow) {
			ReliefMapButton.addTo(iconButtonRow);
		}
		return child;
	}
}
