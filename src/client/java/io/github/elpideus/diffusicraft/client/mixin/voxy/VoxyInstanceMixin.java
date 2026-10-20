package io.github.elpideus.diffusicraft.client.mixin.voxy;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyLods;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells Diffusicraft which level each of Voxy's worlds holds as Voxy opens it. Applied only when Voxy is installed. */
@Mixin(targets = "me.cortex.voxy.commonImpl.VoxyInstance", remap = false)
abstract class VoxyInstanceMixin {
	@Inject(method = "createWorld", at = @At("RETURN"))
	private void diffusicraft$opened(WorldIdentifier identifier, CallbackInfoReturnable<WorldEngine> cir) {
		VoxyLods.opened(cir.getReturnValue(), identifier.key);
	}
}
