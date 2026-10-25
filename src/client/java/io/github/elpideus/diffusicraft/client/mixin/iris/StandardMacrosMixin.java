package io.github.elpideus.diffusicraft.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.elpideus.diffusicraft.client.compat.iris.IrisFarTerrain;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Defines {@code DISTANT_HORIZONS} for the shader pack being loaded while the far terrain is on, so that the pack takes
 * the far terrain for Distant Horizons'; see {@link IrisFarTerrain}. Applied only with Iris and without Distant
 * Horizons.
 */
@Mixin(value = StandardMacros.class, remap = false)
abstract class StandardMacrosMixin {
	@ModifyReturnValue(method = "createStandardEnvironmentDefines", at = @At("RETURN"))
	private static ImmutableList<StringPair> diffusicraft$defineFarTerrain(ImmutableList<StringPair> defines) {
		if (!IrisFarTerrain.define()) {
			return defines;
		}
		return ImmutableList.<StringPair>builder().addAll(defines).add(new StringPair("DISTANT_HORIZONS", "")).build();
	}
}
