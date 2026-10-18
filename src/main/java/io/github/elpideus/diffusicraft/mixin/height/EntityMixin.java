package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Clamps entity positions to ±{@value ExtendedHeight#WORLD_LIMIT} blocks rather than ±3.0E7, as {@link LevelMixin} does. */
@Mixin(Entity.class)
abstract class EntityMixin {
	@ModifyConstant(method = "absSnapTo(DDD)V", constant = {@Constant(doubleValue = 3.0E7), @Constant(doubleValue = -3.0E7)})
	private static double diffusicraft$worldLimit(double vanilla) {
		return ExtendedHeight.worldLimit(vanilla);
	}
}
