package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.SharedContainerData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shares the blocks of sections that world generation filled with a single block, such as deep rock or the sea, when
 * their chunk is complete; see {@link SharedContainerData}. Generation is over by then: the neighbours that place
 * features in a chunk have all placed them before it is lit. Chunks loaded from a save are shared as they are read.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;"
		+ "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
	private void diffusicraft$shareUniformBlocks(CallbackInfo ci) {
		for (LevelChunkSection section : ((LevelChunk) (Object) this).getSections()) {
			if (section.getStates() instanceof SharedContainerData.Container container) {
				container.diffusicraft$shareIfUniform();
			}
		}
	}
}
