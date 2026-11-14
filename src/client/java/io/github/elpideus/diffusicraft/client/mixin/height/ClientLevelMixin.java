package io.github.elpideus.diffusicraft.client.mixin.height;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Spreads a burst of light updates over a few frames. With a thousand queued vanilla runs them all at once, and under a
 * tall sky dropping a column walks all of its sections, so what a teleport leaves behind took a third of a second.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Unique
	private static final int MOST_LIGHT_UPDATES_A_FRAME = 64;

	@ModifyVariable(method = "pollLightUpdates", at = @At("STORE"), ordinal = 1)
	private int diffusicraft$spreadOut(int updates) {
		return Math.min(updates, MOST_LIGHT_UPDATES_A_FRAME);
	}
}
