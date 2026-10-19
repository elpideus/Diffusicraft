package io.github.elpideus.diffusicraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The silk threads that glowworm larvae (<i>Arachnocampa</i>) hang from cave ceilings, beaded with sticky droplets
 * that shine with the larvae's blue-green light. It hangs and grows down like pale hanging moss, but bone meal does not
 * lengthen it.
 */
public class GlowwormSilkBlock extends HangingMossBlock {
	public GlowwormSilkBlock(Properties properties) {
		super(properties);
	}

	@Override
	public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, BonemealSource source) {
		return false;
	}
}
