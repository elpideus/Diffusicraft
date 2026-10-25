package io.github.elpideus.diffusicraft.client.mixin.iris;

import io.github.elpideus.diffusicraft.client.compat.iris.IrisFarTerrain;
import net.irisshaders.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers for Distant Horizons where it is not installed, while the shader pack in use was loaded believing that it
 * draws: Iris's uniforms and samplers for Distant Horizons read the far terrain's planes, reach, projection and depth
 * textures instead; see {@link IrisFarTerrain}. Applied only with Iris and without Distant Horizons.
 */
@Mixin(value = DHCompat.class, remap = false)
abstract class DHCompatMixin {
	@Inject(method = "checkFrame", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$checkFrame(CallbackInfoReturnable<Boolean> info) {
		info.setReturnValue(IrisFarTerrain.checkFrame());
	}

	@Inject(method = "hasRenderingEnabled", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$hasRenderingEnabled(CallbackInfoReturnable<Boolean> info) {
		info.setReturnValue(IrisFarTerrain.defined());
	}

	@Inject(method = "getNearPlane", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$getNearPlane(CallbackInfoReturnable<Float> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.nearPlane());
		}
	}

	@Inject(method = "getFarPlane", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$getFarPlane(CallbackInfoReturnable<Float> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.farPlane());
		}
	}

	@Inject(method = "getRenderDistance", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$getRenderDistance(CallbackInfoReturnable<Integer> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.renderDistance());
		}
	}

	@Inject(method = "getProjection()Lorg/joml/Matrix4f;", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$getProjection(CallbackInfoReturnable<Matrix4f> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.projection());
		}
	}

	@Inject(method = "getDepthTex", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$getDepthTex(CallbackInfoReturnable<Integer> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.depthTexture());
		}
	}

	@Inject(method = "getDepthTexNoTranslucent", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$getDepthTexNoTranslucent(CallbackInfoReturnable<Integer> info) {
		if (IrisFarTerrain.defined()) {
			info.setReturnValue(IrisFarTerrain.depthTextureNoWater());
		}
	}
}
