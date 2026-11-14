package io.github.elpideus.diffusicraft.mixin.lithostitched;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps Lithostitched from waiting for the chunk under a player. At the start of a level's tick it looks for the
 * structure each player stands in, and that read waits on the server thread until the chunk has its structures, which
 * after a teleport held the server for 16 s. Now a player whose chunk has not arrived is skipped, as Lithostitched
 * skips one in a chunk that is not loaded, until it comes; see {@link UnloadedChunks}.
 */
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.structure.StructureAttributeHandler", remap = false)
abstract class StructureAttributeHandlerMixin {
	@WrapMethod(method = "tick")
	private static void diffusicraft$neverWaitForChunks(ServerLevel level, Operation<Void> original) {
		UnloadedChunks.around(level, () -> original.call(level));
	}
}
