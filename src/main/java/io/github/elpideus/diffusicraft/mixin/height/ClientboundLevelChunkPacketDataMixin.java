package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Raises the block data limit of a chunk packet from 2 to 8 MiB: a 32k-tall column of mixed blocks needs more. */
@Mixin(ClientboundLevelChunkPacketData.class)
abstract class ClientboundLevelChunkPacketDataMixin {
	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 2097152))
	private static int diffusicraft$chunkDataLimit(int vanilla) {
		return ExtendedHeight.CHUNK_PACKET_LIMIT;
	}
}
