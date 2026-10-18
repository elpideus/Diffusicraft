package io.github.elpideus.diffusicraft.mixin.dh;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.elpideus.diffusicraft.compat.dh.DistantHorizonsCompat;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps what Distant Horizons reads of a chunk within the {@value DistantHorizonsCompat#MAX_HEIGHT} blocks its LODs
 * can hold. It builds an LOD from a chunk by walking each column down from the higher of its two heightmaps, up to the
 * top of the chunk, and packs the runs it finds into 12 bits; in a taller level the runs above wrap round to the
 * bottom and the LOD comes out garbled. Capping the top, the heightmaps and the highest non-empty block cuts the
 * column off at that height instead: the ground above it shows as a flat top. Applied only when Distant Horizons is
 * installed (see {@code DiffusicraftMixinPlugin}); levels no taller than that are left as they are.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper", remap = false)
abstract class ChunkWrapperMixin {
	@Shadow
	@Final
	private ChunkAccess chunk;

	@ModifyReturnValue(method = {"getExclusiveMaxBuildHeight()I", "getMaxNonEmptyHeight", "getSolidHeightMapValue",
		"getLightBlockingHeightMapValue"}, at = @At("RETURN"))
	private int diffusicraft$withinLods(int y) {
		// The top is exclusive, but Distant Horizons reads the block at it too and starts the run above it there.
		return Math.min(y, chunk.getMinY() + DistantHorizonsCompat.MAX_HEIGHT - 1);
	}
}
