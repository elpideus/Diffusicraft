package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Moves a player without waiting for the chunks they move into; see {@link UnloadedChunks}. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@WrapMethod(method = "handlePlayerPositionChange")
	private void diffusicraft$neverWaitForChunks(double x, double y, double z, float yRot, float xRot, boolean onGround,
		boolean horizontalCollision, Operation<Void> original) {
		UnloadedChunks.around(player.level(), () -> original.call(x, y, z, yRot, xRot, onGround, horizontalCollision));
	}
}
