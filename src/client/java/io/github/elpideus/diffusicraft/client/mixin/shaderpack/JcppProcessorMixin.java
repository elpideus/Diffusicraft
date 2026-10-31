package io.github.elpideus.diffusicraft.client.mixin.shaderpack;

import io.github.elpideus.diffusicraft.client.compat.iris.BlissClouds;
import io.github.elpideus.diffusicraft.client.compat.iris.ComplementaryClouds;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Raises Bliss's clouds with the dimension's ({@link BlissClouds}), and lets Complementary's be seen as far as they
 * rise ({@link ComplementaryClouds}). Applied only with Iris, whether Distant Horizons is installed or not.
 */
@Mixin(value = JcppProcessor.class, remap = false)
abstract class JcppProcessorMixin {
	@ModifyVariable(method = "glslPreprocessSource", at = @At("HEAD"), argsOnly = true)
	private static String diffusicraft$raiseClouds(String source) {
		return ComplementaryClouds.patch(BlissClouds.patch(source));
	}
}
