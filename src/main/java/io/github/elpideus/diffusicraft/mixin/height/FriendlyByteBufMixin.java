package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sends block positions in the layout of {@link ExtendedHeight#toWire} rather than the in-memory packing, which no
 * longer matches vanilla's. Every packet position goes through these two methods ({@code BlockPos.STREAM_CODEC}).
 */
@Mixin(FriendlyByteBuf.class)
abstract class FriendlyByteBufMixin {
	@ModifyExpressionValue(method = "writeBlockPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/core/BlockPos;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"))
	private static long diffusicraft$toWire(long packed) {
		return ExtendedHeight.toWire(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
	}

	@ModifyExpressionValue(method = "readBlockPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/core/BlockPos;",
		at = @At(value = "INVOKE", target = "Lio/netty/buffer/ByteBuf;readLong()J"))
	private static long diffusicraft$fromWire(long wire) {
		return BlockPos.asLong(ExtendedHeight.wireX(wire), ExtendedHeight.wireY(wire), ExtendedHeight.wireZ(wire));
	}
}
