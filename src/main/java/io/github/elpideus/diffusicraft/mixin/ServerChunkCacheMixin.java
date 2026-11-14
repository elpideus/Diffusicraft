package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers without waiting for a chunk while the server handles a player; see {@link UnloadedChunks}. */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	private ServerLevel level;
	@Shadow
	@Final
	private Thread mainThread;
	/** Stands for every missing chunk, as the client's does: a tall column is too much to make for each read. */
	@Unique
	private volatile @Nullable LevelChunk diffusicraft$empty;

	@Shadow
	public abstract @Nullable LevelChunk getChunkNow(int x, int z);

	@Unique
	private boolean diffusicraft$lenient() {
		return Thread.currentThread() == mainThread && UnloadedChunks.lenient((ServerChunkCache) (Object) this);
	}

	/** Vanilla says yes to a chunk with a ticket, however far from finished, and whoever asked then reads it. */
	@Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$hasChunkNow(int x, int z, CallbackInfoReturnable<Boolean> info) {
		if (diffusicraft$lenient()) {
			info.setReturnValue(getChunkNow(x, z) != null);
		}
	}

	@Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
	private void diffusicraft$answerWithoutWaiting(int x, int z, ChunkStatus status, boolean create,
		CallbackInfoReturnable<ChunkAccess> info) {
		if (!diffusicraft$lenient() || getChunkNow(x, z) != null) {
			return;
		}
		// Asking not to create a chunk waits all the same once it has a ticket, as collisions do at a teleport.
		if (!create && status == ChunkStatus.FULL) {
			info.setReturnValue(null);
			return;
		}
		// Anything else is a read that would go on to compute the terrain here if it got nothing, as biomes would.
		LevelChunk empty = diffusicraft$empty;
		if (empty == null) {
			empty = new EmptyLevelChunk(level, ChunkPos.ZERO,
				level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
			diffusicraft$empty = empty;
		}
		info.setReturnValue(empty);
	}
}
