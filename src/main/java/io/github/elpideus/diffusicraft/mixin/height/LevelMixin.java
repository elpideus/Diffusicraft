package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Shrinks the world from ±30,000,000 to ±{@value ExtendedHeight#WORLD_LIMIT} blocks, inside the ±8,388,607 that block
 * positions can now hold: where blocks can be placed, and where heightmaps are looked up. Chunks still generate a
 * little past the limit, as they do past 30,000,000 in vanilla.
 */
@Mixin(Level.class)
abstract class LevelMixin {
	@ModifyConstant(method = {"isInWorldBoundsHorizontal", "getHeight"},
		constant = {@Constant(intValue = 30000000), @Constant(intValue = -30000000)})
	private static int diffusicraft$worldLimit(int vanilla) {
		return ExtendedHeight.worldLimit(vanilla);
	}
}
