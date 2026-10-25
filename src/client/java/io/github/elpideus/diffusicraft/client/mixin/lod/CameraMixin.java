package io.github.elpideus.diffusicraft.client.mixin.lod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves the far plane of the camera out past the far terrain. Vanilla puts it at four times the render distance, which
 * would cut the far terrain off long before its end.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
	private float diffusicraft$reachFarTerrain(float depthFar) {
		return Math.max(depthFar, FarTerrain.reach() * 2.0F);
	}
}
