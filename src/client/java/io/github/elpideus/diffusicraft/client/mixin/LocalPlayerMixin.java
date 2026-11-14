package io.github.elpideus.diffusicraft.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds a player who does not fly where the chunk under them has not come yet. Vanilla lets them sink through it, which
 * in a vanilla world lasts a moment. Here a new chunk can take many seconds, and they would end up inside the ground.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$waitForTheGround(CallbackInfo info) {
		LocalPlayer player = (LocalPlayer) (Object) this;
		// The client's level says it has every chunk, so ask the chunks.
		if (!player.level().getChunkSource().hasChunk(SectionPos.blockToSectionCoord(player.getBlockX()),
			SectionPos.blockToSectionCoord(player.getBlockZ())) && !player.getAbilities().flying
			&& !player.isFallFlying() && !player.isSpectator() && !player.isPassenger()) {
			player.setOldPosAndRot();
			info.cancel();
		}
	}
}
