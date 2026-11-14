package io.github.elpideus.diffusicraft.client.mixin.sodium;

import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Shows a section whole as soon as it is built, as {@code RenderSectionMixin} does. Applied only with Sodium. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager", remap = false)
abstract class UniformBufferManagerMixin {
	/** Sodium's shader takes a section whose time is under zero to have faded in long ago. */
	@ModifyVariable(method = "writeMeshTimes", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int diffusicraft$noFadeIn(int time) {
		return FarTerrain.reach() > 0 ? -1 : time;
	}
}
