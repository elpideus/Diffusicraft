package io.github.elpideus.diffusicraft.benchmark.mixin;

import io.github.elpideus.diffusicraft.benchmark.Recorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the end of each frame, whatever draws it: a mod's hook into the frame could be skipped by another mod. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void diffusicraft$frameEnded(boolean advanceGameTime, CallbackInfo info) {
		Recorder.frame();
	}
}
