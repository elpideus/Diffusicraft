package io.github.elpideus.diffusicraft.client.mixin.clouds;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.client.clouds.CloudScale;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves the far plane out to the end of the clouds that {@link CloudScale} grows, as vanilla moves it to the end of
 * its own.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
	private float diffusicraft$reachClouds(float depthFar) {
		if (minecraft.options.getCloudStatus() == CloudStatus.OFF) {
			return depthFar;
		}
		return Math.max(depthFar, minecraft.options.cloudRange().get() * 16 * CloudScale.of(minecraft.level));
	}
}
