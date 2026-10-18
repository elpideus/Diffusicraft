package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.server.commands.ForceLoadCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** {@code /forceload} accepts chunks within ±{@value ExtendedHeight#WORLD_LIMIT} blocks, as {@link LevelMixin} does. */
@Mixin(ForceLoadCommand.class)
abstract class ForceLoadCommandMixin {
	@ModifyConstant(method = "changeForceLoad", constant = {@Constant(intValue = 30000000), @Constant(intValue = -30000000)})
	private static int diffusicraft$worldLimit(int vanilla) {
		return ExtendedHeight.worldLimit(vanilla);
	}
}
