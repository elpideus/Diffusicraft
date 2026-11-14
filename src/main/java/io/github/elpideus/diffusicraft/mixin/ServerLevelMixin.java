package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Runs vanilla's spawners, which read the blocks around a player, without waiting; see {@link UnloadedChunks}. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapMethod(method = "tickCustomSpawners")
	private void diffusicraft$neverWaitForChunks(boolean spawnEnemies, Operation<Void> original) {
		UnloadedChunks.around((ServerLevel) (Object) this, () -> original.call(spawnEnemies));
	}
}
