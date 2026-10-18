package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Clamps client movement to ±{@value ExtendedHeight#WORLD_LIMIT} blocks rather than ±3.0E7, as {@link LevelMixin} does. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@ModifyConstant(method = "clampHorizontal", constant = {@Constant(doubleValue = 3.0E7), @Constant(doubleValue = -3.0E7)})
	private static double diffusicraft$worldLimit(double vanilla) {
		return ExtendedHeight.worldLimit(vanilla);
	}
}
