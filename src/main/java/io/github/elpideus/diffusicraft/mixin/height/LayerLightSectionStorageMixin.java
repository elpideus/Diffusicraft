package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops the sections of unloaded columns from all being copied into {@code changedSections}. That loop goes through
 * {@code toRemove} in its hash order, and filling a small hash set in the order of a large one piles the keys up: after
 * a teleport, with a thousand tall columns to drop, it held the render thread for a second. All the set has to do here
 * is stop being empty. {@code swapSectionMap} runs straight after, copies the map and clears it, and nothing between
 * the two reads it.
 */
@Mixin(LayerLightSectionStorage.class)
abstract class LayerLightSectionStorageMixin {
	@WrapOperation(method = "markNewInconsistencies", at = @At(value = "INVOKE",
		target = "Lit/unimi/dsi/fastutil/longs/LongSet;add(J)Z", ordinal = 0))
	private boolean diffusicraft$oneIsEnough(LongSet changed, long section, Operation<Boolean> original) {
		return !changed.isEmpty() || original.call(changed, section);
	}
}
