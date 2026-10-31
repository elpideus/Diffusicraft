package io.github.elpideus.diffusicraft.client.mixin.clouds;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.client.clouds.CloudScale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves the end of the clouds' fog out as many times as {@link CloudScale} grows them, so that they fade where vanilla's
 * would. The fog of a boss, set after it, stays.
 */
@Mixin(AtmosphericFogEnvironment.class)
abstract class AtmosphericFogEnvironmentMixin {
	@WrapOperation(method = "setupFog", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/fog/FogData;cloudEnd:F", opcode = Opcodes.PUTFIELD, ordinal = 0))
	private void diffusicraft$growCloudFog(FogData fog, float cloudEnd, Operation<Void> original,
		@Local(argsOnly = true) ClientLevel level) {
		original.call(fog, cloudEnd * CloudScale.of(level));
	}
}
