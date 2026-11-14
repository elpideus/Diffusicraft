package io.github.elpideus.diffusicraft.client.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.height.SectionSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gives the renderer an {@code emptySections} that is cheap to copy (see {@link SectionSet}). */
@Mixin(SectionOcclusionGraph.class)
abstract class SectionOcclusionGraphMixin {
	@WrapOperation(method = "<init>", at = @At(value = "NEW", target = "it/unimi/dsi/fastutil/longs/LongOpenHashSet", ordinal = 0))
	private LongOpenHashSet diffusicraft$emptySections(Operation<LongOpenHashSet> original) {
		return new SectionSet();
	}
}
