package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.WorldSpawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the spawn of a new Diffusicraft world in the kind of place its seed drew ({@link WorldSpawn}). */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	@Inject(method = "setInitialSpawn", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$placeSpawn(ServerLevel level, ServerLevelData data, boolean bonusChest,
												boolean isDebug, LevelLoadListener listener, CallbackInfo ci) {
		if (!isDebug && WorldSpawn.place(level, data, bonusChest, listener)) {
			ci.cancel();
		}
	}
}
