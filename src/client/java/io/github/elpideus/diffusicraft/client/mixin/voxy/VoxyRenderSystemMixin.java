package io.github.elpideus.diffusicraft.client.mixin.voxy;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyHeights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps Voxy's renderer within the heights it can hold. Voxy fills the view with its coarsest sections from the bottom
 * of the level to its top; in a level taller than {@link VoxyHeights} allows, the sections past that wrap round and
 * Voxy stops the game with "Position not valid at all levels". Applied only when Voxy is installed (see
 * {@code DiffusicraftMixinPlugin}); levels within those heights are left as they are.
 */
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
abstract class VoxyRenderSystemMixin {
	private static final String TRACKER = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;"
		+ "<init>(IIILjava/util/function/LongConsumer;Ljava/util/function/LongConsumer;)V";

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = TRACKER), index = 1)
	private int diffusicraft$lowestSection(int minSection) {
		return Math.max(minSection, VoxyHeights.MIN_TOP_SECTION);
	}

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = TRACKER), index = 2)
	private int diffusicraft$highestSection(int maxSection) {
		return Math.min(maxSection, VoxyHeights.MAX_TOP_SECTION);
	}
}
