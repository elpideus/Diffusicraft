package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Ticks a player without waiting for the chunks around them; see {@link UnloadedChunks}. The client holds a player over
 * a chunk that has not come and sends nothing meanwhile, which is no reason to kick them for floating.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@WrapMethod(method = "doTick")
	private void diffusicraft$neverWaitForChunks(Operation<Void> original) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		UnloadedChunks.around(player.level(), original::call);
		if (player.level().getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(player.getBlockX()),
			SectionPos.blockToSectionCoord(player.getBlockZ())) == null) {
			player.connection.resetFlyingTicks();
		}
	}
}
