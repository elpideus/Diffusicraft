package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs vanilla's spawners, which read the blocks around a player, and the functions data packs schedule, which often
 * look at each player's biome, without waiting; see {@link UnloadedChunks}.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapMethod(method = "tickCustomSpawners")
	private void diffusicraft$neverWaitForChunks(boolean spawnEnemies, Operation<Void> original) {
		UnloadedChunks.around((ServerLevel) (Object) this, () -> original.call(spawnEnemies));
	}

	@WrapOperation(method = "tickTime", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/timers/TimerQueue;tick(Ljava/lang/Object;J)V"))
	private <T> void diffusicraft$scheduledFunctionsNeverWait(TimerQueue<T> queue, T context, long time,
		Operation<Void> original) {
		UnloadedChunks.around(((ServerLevel) (Object) this).getServer().overworld(),
			() -> original.call(queue, context, time));
	}
}
