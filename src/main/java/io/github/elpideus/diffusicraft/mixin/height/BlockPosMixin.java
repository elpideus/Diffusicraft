package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Packs block positions as 24 bits each for X and Z and 16 for Y, instead of 26/26/12. Every class that reads
 * {@code BlockPos.PACKED_*} or {@code MAX_HORIZONTAL_COORDINATE} follows, including {@code DimensionType}.
 */
@Mixin(BlockPos.class)
abstract class BlockPosMixin {
	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 30000000))
	private static int diffusicraft$packingSize(int vanilla) {
		return ExtendedHeight.PACKING_SIZE;
	}
}
