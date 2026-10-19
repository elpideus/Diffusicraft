package io.github.elpideus.diffusicraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Selenite, the clear crystals of gypsum that grow into beams many blocks long in still, warm water. A pillar along its
 * crystal's long axis, see-through like glass: the faces between two blocks of it are not drawn, so a beam reads as
 * one crystal.
 */
public class SeleniteBlock extends RotatedPillarBlock {
	public SeleniteBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
		return neighbor.is(this) || super.skipRendering(state, neighbor, direction);
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0f;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}
}
