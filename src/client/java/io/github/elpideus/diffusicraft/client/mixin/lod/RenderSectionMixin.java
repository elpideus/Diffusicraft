package io.github.elpideus.diffusicraft.client.mixin.lod;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shows a section whole as soon as it is built. Vanilla fades it in from the colour of the fog, which suits the edge of
 * the world. Here the far terrain stood in its place until that moment, and the section flashes the colour of the sky.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionMixin {
	@ModifyReturnValue(method = "getVisibility", at = @At("RETURN"))
	private float diffusicraft$noFadeIn(float visibility) {
		return FarTerrain.reach() > 0 ? 1 : visibility;
	}
}
