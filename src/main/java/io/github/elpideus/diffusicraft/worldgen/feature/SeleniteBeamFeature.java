package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.MapCodec;
import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code diffusicraft:selenite_beam}: a few beams of selenite, the clear crystals of gypsum, growing out of a cave floor
 * or ceiling at the origin, like those of the Cave of the Crystals at Naica. They grew for half a million years in
 * still, warm water saturated with calcium sulfate, and every one points its own way: most lean far from upright,
 * so that they cross the cavern from wall to wall.
 *
 * <p>Each beam is a rod up to 22 blocks long and a block or three thick, set two blocks deep into the rock, that tapers
 * to a point in its last few blocks. It fills only open space beyond its root, so a beam that runs into a wall stays
 * buried in it. Its blocks lie along whichever axis the beam runs closest to. Beams reach at most 15 blocks sideways,
 * which keeps them in the chunks a feature may write to.
 */
public record SeleniteBeamFeature() implements Feature {
	public static final MapCodec<SeleniteBeamFeature> CODEC = MapCodec.unit(SeleniteBeamFeature::new);

	private static final int ROOT = 2;
	private static final double MAX_REACH = 13;

	@Override
	public MapCodec<SeleniteBeamFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!level.isEmptyBlock(origin)) {
			return false;
		}
		int up;
		if (level.getBlockState(origin.below()).isFaceSturdy(level, origin.below(), Direction.UP)) {
			up = 1;
		} else if (level.getBlockState(origin.above()).isFaceSturdy(level, origin.above(), Direction.DOWN)) {
			up = -1;
		} else {
			return false;
		}
		int beams = 1 + random.nextInt(3);
		boolean placed = false;
		for (int i = 0; i < beams; i++) {
			// The first beam is the longest; the others are its offshoots.
			double length = i == 0 ? 8 + random.nextInt(15) : 5 + random.nextInt(8);
			placed |= beam(level, random, origin, up, length);
		}
		return placed;
	}

	private boolean beam(WorldGenLevel level, RandomSource random, BlockPos origin, int up, double length) {
		double tilt = Math.toRadians(15 + random.nextDouble() * 65);
		double turn = random.nextDouble() * Math.PI * 2;
		double dx = Math.sin(tilt) * Math.cos(turn);
		double dz = Math.sin(tilt) * Math.sin(turn);
		double dy = Math.cos(tilt) * up;
		length = Math.min(length, MAX_REACH / Math.max(Math.sin(tilt), 1e-3));
		double radius = 0.5 + length * 0.05;
		// From two blocks inside the rock, so the beam grows out of it rather than standing on it.
		double sx = origin.getX() + 0.5 - dx * ROOT;
		double sy = origin.getY() + 0.5 - dy * ROOT;
		double sz = origin.getZ() + 0.5 - dz * ROOT;
		double total = length + ROOT;
		double ex = sx + dx * total;
		double ey = sy + dy * total;
		double ez = sz + dz * total;
		BlockState state = DiffusicraftBlocks.SELENITE.defaultBlockState().setValue(RotatedPillarBlock.AXIS,
			axis(dx, dy, dz));
		int minX = Mth.floor(Math.min(sx, ex) - radius);
		int maxX = Mth.floor(Math.max(sx, ex) + radius);
		int minY = Math.max(Mth.floor(Math.min(sy, ey) - radius), level.getMinY());
		int maxY = Math.min(Mth.floor(Math.max(sy, ey) + radius), level.getMaxY());
		int minZ = Mth.floor(Math.min(sz, ez) - radius);
		int maxZ = Mth.floor(Math.max(sz, ez) + radius);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					double px = x + 0.5 - sx;
					double py = y + 0.5 - sy;
					double pz = z + 0.5 - sz;
					double along = px * dx + py * dy + pz * dz;
					if (along < 0 || along > total) {
						continue;
					}
					// Full thickness, then a point over the last quarter.
					double t = along / total;
					double r = t < 0.75 ? radius : radius * Math.max(0.25, (1 - t) / 0.25);
					double ox = px - dx * along;
					double oy = py - dy * along;
					double oz = pz - dz * along;
					if (ox * ox + oy * oy + oz * oz > r * r) {
						continue;
					}
					BlockState old = level.getBlockState(pos.set(x, y, z));
					boolean root = along < ROOT + 1;
					if (old.isAir() || old.is(Blocks.WATER)
						|| root && old.is(BlockTags.BASE_STONE_OVERWORLD)) {
						level.setBlock(pos, state, 2);
						placed = true;
					}
				}
			}
		}
		return placed;
	}

	private static Direction.Axis axis(double dx, double dy, double dz) {
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);
		if (ay >= ax && ay >= az) {
			return Direction.Axis.Y;
		}
		return ax >= az ? Direction.Axis.X : Direction.Axis.Z;
	}
}
