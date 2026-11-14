package io.github.elpideus.diffusicraft.client.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rebuilds what a new chunk lights up to the top of the terrain instead of the top of the world. Every chunk that comes
 * marks nine columns, which under a tall sky is thousands of sections with nothing to build, and a few chunks arriving
 * together took the render thread over 100 ms. What could stay behind is only the mesh of a section that was emptied
 * while the chunk was away, in all nine columns at once.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@WrapOperation(method = "enableChunkLight", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionRangeDirty(IIIIII)V"))
	private void diffusicraft$stopAtTheTerrain(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY,
		int maxZ, Operation<Void> original) {
		int highest = minY - 1;
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk != null) {
					highest = Math.max(highest, level.getSectionYFromSectionIndex(chunk.getHighestFilledSectionIndex()));
				}
			}
		}
		original.call(level, minX, minY, minZ, maxX, Math.min(maxY, highest + 1), maxZ);
	}
}
