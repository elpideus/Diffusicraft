package io.github.elpideus.diffusicraft.client.mixin.sodium;

import io.github.elpideus.diffusicraft.client.compat.sodium.SodiumTaskHeights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Unpacks the height of each section waiting to be built from the section the tree that listed it counted from; see
 * {@link SodiumTaskHeights}. Applied only when Sodium is installed.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList", remap = false)
abstract class DeferredTaskListMixin implements SodiumTaskHeights.Tasks {
	/** Sodium's own until the tree that made the list sets it. */
	@Unique
	private int diffusicraft$lowest = -128;

	@Override
	public void diffusicraft$countFrom(int sectionY) {
		this.diffusicraft$lowest = sectionY;
	}

	/** {@code TaskCollectingTree.SECTION_Y_MIN}, inlined. */
	@ModifyConstant(method = "dequeueNextSectionPos", constant = @Constant(intValue = -128))
	private int diffusicraft$countFromCamera(int sectionYMin) {
		return this.diffusicraft$lowest;
	}
}
