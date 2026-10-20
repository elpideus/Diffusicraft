package io.github.elpideus.diffusicraft.client.mixin.sodium;

import io.github.elpideus.diffusicraft.client.compat.sodium.SodiumTaskHeights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packs the height of each section waiting to be built from below the camera instead of from y = −2048, and hands
 * that height on to the list of tasks; see {@link SodiumTaskHeights}. Applied only when Sodium is installed.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree", remap = false)
abstract class TaskCollectingTreeMixin {
	/** {@code SECTION_Y_MIN}, inlined. */
	@ModifyConstant(method = "addPendingSection", constant = @Constant(intValue = -128))
	private int diffusicraft$countFromCamera(int sectionYMin) {
		return this.diffusicraft$lowest();
	}

	@Inject(method = "getPendingTaskLists", at = @At("RETURN"))
	private void diffusicraft$handOn(CallbackInfoReturnable<Object> cir) {
		((SodiumTaskHeights.Tasks) cir.getReturnValue()).diffusicraft$countFrom(this.diffusicraft$lowest());
	}

	private int diffusicraft$lowest() {
		return SodiumTaskHeights.lowest(((SodiumTaskHeights.Search) this).diffusicraft$lowestSection());
	}
}
