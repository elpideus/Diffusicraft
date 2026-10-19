package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's tests of where a player can stand, for the world spawn of a Diffusicraft world. */
@Mixin(PlayerSpawnFinder.class)
public interface PlayerSpawnFinderAccessor {
	/** The block over the ground of a column, if it is dry and not under a roof of leaves or the like. */
	@Invoker("getLevelRespawnPos")
	static @Nullable BlockPos diffusicraft$levelRespawnPos(ServerLevel level, int x, int z) {
		throw new AssertionError();
	}

	/** The floor under the first open space at or over a position. */
	@Invoker("fixupSpawnHeight")
	static Vec3 diffusicraft$fixupSpawnHeight(CollisionGetter level, BlockPos pos) {
		throw new AssertionError();
	}
}
