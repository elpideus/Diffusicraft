package io.github.elpideus.diffusicraft.client.mixin.sodium;

import io.github.elpideus.diffusicraft.client.compat.sodium.SodiumTaskHeights;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Gives the task tree the lowest section of its search; see {@link SodiumTaskHeights}. Applied only with Sodium. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.AbstractSectionVisitor", remap = false)
abstract class AbstractSectionVisitorMixin implements SodiumTaskHeights.Search {
	@Shadow
	@Final
	protected int baseOffsetY;

	@Override
	public int diffusicraft$lowestSection() {
		return this.baseOffsetY;
	}
}
