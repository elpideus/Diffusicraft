package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.elpideus.diffusicraft.worldgen.WorldSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

/**
 * Respawns players without a bed on the floor of the Ancient Grand Hollow that holds the world spawn, rather than on
 * the surface over it, where vanilla's search for open sky would take them ({@link WorldSpawn#underground}).
 */
@Mixin(PlayerSpawnFinder.class)
abstract class PlayerSpawnFinderMixin {
	@WrapMethod(method = "findSpawn")
	private static CompletableFuture<Vec3> diffusicraft$underground(ServerLevel level, BlockPos suggestion,
																	Operation<CompletableFuture<Vec3>> original) {
		if (!WorldSpawn.isWorldSpawn(level, suggestion)) {
			return original.call(level, suggestion);
		}
		return level.getChunkSource().addTicketAndLoadWithRadius(TicketType.SPAWN_SEARCH, ChunkPos.containing(suggestion), 0)
			.thenComposeAsync(ignored -> {
				Vec3 floor = WorldSpawn.underground(level, suggestion);
				return floor != null ? CompletableFuture.completedFuture(floor) : original.call(level, suggestion);
			}, level.getServer());
	}
}
