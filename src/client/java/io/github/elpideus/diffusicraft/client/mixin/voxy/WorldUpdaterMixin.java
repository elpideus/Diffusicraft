package io.github.elpideus.diffusicraft.client.mixin.voxy;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyHeights;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves out of Voxy the sections of a chunk above or below the heights it can hold. Every chunk section Voxy reads,
 * from the chunks it loads or from its importers, goes through {@code WorldUpdater.insertUpdate}; one past
 * {@link VoxyHeights} would wrap round and land at the other end of the level. Applied only when Voxy is installed.
 */
@Mixin(targets = "me.cortex.voxy.common.world.WorldUpdater", remap = false)
abstract class WorldUpdaterMixin {
	@Inject(method = "insertUpdate", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$withinHeights(WorldEngine into, VoxelizedSection section, CallbackInfo ci) {
		if (!VoxyHeights.holdsSection(section.y)) {
			ci.cancel();
		}
	}
}
