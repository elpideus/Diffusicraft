package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps the world border within {@value ExtendedHeight#BORDER_LIMIT} blocks of the origin, whatever
 * {@code max-world-size} or the server says, so that players stay where block positions can be packed.
 */
@Mixin(WorldBorder.class)
abstract class WorldBorderMixin {
	// The no-argument constructor delegates to this one, which holds the field initialiser.
	@ModifyConstant(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder$Settings;)V", constant = @Constant(intValue = 29999984))
	private int diffusicraft$absoluteMaxSize(int vanilla) {
		return ExtendedHeight.BORDER_LIMIT;
	}

	@ModifyVariable(method = "setAbsoluteMaxSize", at = @At("HEAD"), argsOnly = true)
	private int diffusicraft$clampAbsoluteMaxSize(int size) {
		return Math.min(size, ExtendedHeight.BORDER_LIMIT);
	}
}
