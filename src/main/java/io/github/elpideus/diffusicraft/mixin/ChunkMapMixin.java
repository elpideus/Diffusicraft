package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands each chunk of a Diffusicraft world that vanilla saves to its far terrain ({@link LodStore}), so that what
 * players built and dug shows from afar. The store reads the copy vanilla takes to save the chunk, off the server
 * thread.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyExpressionValue(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/"
			+ "ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/"
			+ "SerializableChunkData;"))
	private SerializableChunkData diffusicraft$keepForFarTerrain(SerializableChunkData data) {
		LodStore store = LodStore.of(level);
		if (store != null) {
			store.saved(data);
		}
		return data;
	}
}
