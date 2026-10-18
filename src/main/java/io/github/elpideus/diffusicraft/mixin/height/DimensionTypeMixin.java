package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caps {@code BITS_FOR_Y} at 15 (heights up to 32,736 blocks) rather than the 16 the new block position packing would
 * allow, so that every valid Y also fits the network layout of {@link ExtendedHeight#toWire}. The codecs and checks
 * that read {@code MIN_Y}, {@code MAX_Y} and {@code Y_SIZE} (dimension types, noise settings, vertical anchors, flat
 * layers) follow.
 */
@Mixin(DimensionType.class)
abstract class DimensionTypeMixin {
	@ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/core/BlockPos;PACKED_Y_LENGTH:I"))
	private static int diffusicraft$bitsForY(int packedYLength) {
		return Math.min(packedYLength, ExtendedHeight.DIMENSION_Y_BITS);
	}
}
