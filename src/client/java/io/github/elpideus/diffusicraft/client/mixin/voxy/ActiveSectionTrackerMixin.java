package io.github.elpideus.diffusicraft.client.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyLods;
import me.cortex.voxy.common.world.ActiveSectionTracker;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fills the sections Voxy's store does not hold from Diffusicraft's terrain. Every section Voxy reads, to draw it or to
 * write the chunks it loads into it, comes through {@code ActiveSectionTracker.acquire}, which asks the store for it
 * once and keeps it in memory. Applied only when Voxy is installed.
 */
@Mixin(targets = "me.cortex.voxy.common.world.ActiveSectionTracker", remap = false)
abstract class ActiveSectionTrackerMixin {
	@WrapOperation(method = "acquire(JZ)Lme/cortex/voxy/common/world/WorldSection;", at = @At(value = "INVOKE",
		target = "Lme/cortex/voxy/common/world/ActiveSectionTracker$SectionLoader;load(Lme/cortex/voxy/common/world/WorldSection;)I"))
	private int diffusicraft$fillFromTerrain(ActiveSectionTracker.SectionLoader loader, WorldSection section,
											 Operation<Integer> load) {
		int status = load.call(loader, section);
		WorldEngine engine = ((ActiveSectionTracker) (Object) this).engine;
		return engine == null ? status : VoxyLods.loaded(engine, section, status);
	}
}
