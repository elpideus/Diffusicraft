package io.github.elpideus.diffusicraft.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Arrays;

/** Sets {@link Boulder}s down in the world, for {@link ErraticFeature} and {@link RockfallFeature}. */
final class Boulders {
	/** Rain from which moss grows on the tops of boulders, in mm a year. */
	private static final float MOSSY = 700f;
	/** Rain from which moss covers half of their tops. */
	private static final float WET = 2200f;

	private Boulders() {
	}

	/** The ground as the chunk's heightmaps have it before the plants and trees grow. */
	static Boulder.Ground ground(WorldGenLevel level) {
		return (x, z) -> level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
	}

	/** How much of the tops of boulders moss covers, where {@code rain} mm fall a year. */
	static float moss(float rain) {
		return 0.5f * Math.clamp((rain - MOSSY) / (WET - MOSSY), 0f, 1f);
	}

	/**
	 * Sets a boulder of {@code rock} down over {@code (x, z)}, sunk a quarter of its height into the ground at its
	 * centre, and fills in the ground that falls away under it. Whether it was set down: it is not where any of
	 * the ground under it is under water, falls away from the centre by more than a quarter of its height or rises by
	 * more than {@code maxRise}, where it would reach out of the chunks that the feature placed in the chunk at
	 * {@code (x0, z0)} may change, or where a village or another structure on the surface stands.
	 */
	static boolean setDown(WorldGenLevel level, Boulder.Ground ground, Boulder boulder, int x, int z, int maxRise,
						   BlockState rock, float moss, int x0, int z0, RandomSource random) {
		int reach = boulder.reach;
		if (x - reach < x0 - 16 || x + reach > x0 + 31 || z - reach < z0 - 16 || z + reach > z0 + 31) {
			return false;
		}
		// The lowest and the highest block of each of the boulder's columns, and the ground under it.
		int side = 2 * reach + 1;
		int[] lowest = new int[side * side], highest = new int[side * side];
		Arrays.fill(lowest, Integer.MAX_VALUE);
		Arrays.fill(highest, Integer.MIN_VALUE);
		for (long block : boulder.blocks) {
			int at = (BlockPos.getX(block) + reach) * side + BlockPos.getZ(block) + reach;
			lowest[at] = Math.min(lowest[at], BlockPos.getY(block));
			highest[at] = Math.max(highest[at], BlockPos.getY(block));
		}
		int centre = ground.top(x, z);
		int[] under = new int[side * side];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int at = 0; at < under.length; at++) {
			if (lowest[at] == Integer.MAX_VALUE) {
				continue;
			}
			int cx = x + at / side - reach, cz = z + at % side - reach;
			under[at] = ground.top(cx, cz);
			if (centre - under[at] > boulder.halfHeight / 2 + 1 || under[at] - centre > maxRise
				|| !level.getFluidState(pos.set(cx, under[at] + 1, cz)).isEmpty()) {
				return false;
			}
		}
		if (nearStructure(level, x - reach, z - reach, x + reach, z + reach)) {
			return false;
		}
		int y = centre + Math.round(boulder.halfHeight / 2);
		for (long block : boulder.blocks) {
			set(level, pos.set(x + BlockPos.getX(block), y + BlockPos.getY(block), z + BlockPos.getZ(block)), rock);
		}
		for (int at = 0; at < under.length; at++) {
			if (lowest[at] == Integer.MAX_VALUE) {
				continue;
			}
			// Where the ground falls away from the centre, up to its height at the centre: higher, under the bulge of
			// the boulder's sides, the gap is as it would be on level ground.
			int cx = x + at / side - reach, cz = z + at % side - reach;
			for (int fill = under[at] + 1; fill < Math.min(y + lowest[at], centre + 1); fill++) {
				set(level, pos.set(cx, fill, cz), rock);
			}
			if (random.nextFloat() < moss && level.getBlockState(pos.set(cx, y + highest[at], cz)).equals(rock)
				&& level.isEmptyBlock(pos.move(0, 1, 0))) {
				level.setBlock(pos, Blocks.MOSS_CARPET.defaultBlockState(), 2);
			}
		}
		return true;
	}

	/** Sets a block of the boulder where nothing that must stay is: no water, nothing built and no tree. */
	private static void set(WorldGenLevel level, BlockPos pos, BlockState rock) {
		BlockState state = level.getBlockState(pos);
		if (state.getFluidState().isEmpty() && !state.hasBlockEntity() && !state.is(BlockTags.FEATURES_CANNOT_REPLACE)
			&& !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
			level.setBlock(pos, rock, 2);
		}
	}

	/** Whether a structure on the surface reaches into a chunk under the square from (x1, z1) to (x2, z2). */
	static boolean nearStructure(WorldGenLevel level, int x1, int z1, int x2, int z2) {
		for (int cx = SectionPos.blockToSectionCoord(x1); cx <= SectionPos.blockToSectionCoord(x2); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(z1); cz <= SectionPos.blockToSectionCoord(z2); cz++) {
				for (Structure structure : level.getChunk(cx, cz).getAllReferences().keySet()) {
					if (structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
