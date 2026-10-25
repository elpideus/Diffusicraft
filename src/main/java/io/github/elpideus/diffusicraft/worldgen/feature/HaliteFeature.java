package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code diffusicraft:halite}: a cluster of cubes of salt, {@code state}, grown out of a cave floor or ceiling at the
 * origin. Salt crystallises in cubes, and in the brine of a salt cave they grow into one another, as in the Crystal
 * Grottoes of Wieliczka; here they grow larger than anywhere on Earth, up to seven blocks on a side.
 *
 * <p>The first cube stands at the origin, two to six more around it within five blocks, each on the floor or under the
 * ceiling it finds there, often on or against the cubes before it. Each is sunk up to half its size into the rock it
 * grows from, and fills the open space and the rock there, so it stands out of the ground rather than on it.
 */
public record HaliteFeature(BlockState state) implements Feature {
	public static final MapCodec<HaliteFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BlockState.CODEC.fieldOf("state").forGetter(HaliteFeature::state)
	).apply(instance, HaliteFeature::new));

	private static final int SPREAD = 5;
	/** How far up or down a cube looks for the floor or ceiling it grows from. */
	private static final int SEARCH = 6;

	@Override
	public MapCodec<HaliteFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!open(level.getBlockState(origin))) {
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
		int cubes = 3 + random.nextInt(5);
		boolean placed = false;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int i = 0; i < cubes; i++) {
			int size = i == 0 ? 3 + random.nextInt(5) : 1 + random.nextInt(4);
			at.set(origin);
			if (i > 0) {
				at.move(random.nextInt(2 * SPREAD + 1) - SPREAD, 0, random.nextInt(2 * SPREAD + 1) - SPREAD);
				if (!ground(level, at, up)) {
					continue;
				}
			}
			placed |= cube(level, at, up, size, random.nextInt(size / 2 + 1));
		}
		return placed;
	}

	/** Moves {@code pos} to the open block on the floor ({@code up} = 1) or under the ceiling below or above it. */
	private static boolean ground(WorldGenLevel level, BlockPos.MutableBlockPos pos, int up) {
		// Out of the rock, or the salt, it starts in, then towards the surface it grows from.
		for (int i = 0; i < SEARCH && !open(level.getBlockState(pos)); i++) {
			pos.move(0, up, 0);
		}
		for (int i = 0; i < SEARCH; i++) {
			if (!open(level.getBlockState(pos))) {
				return false;
			}
			if (!open(level.getBlockState(pos.move(0, -up, 0)))) {
				pos.move(0, up, 0);
				return true;
			}
		}
		return false;
	}

	/** A cube of {@code size} with its base on the face next to {@code pos}, {@code sink} blocks into the rock. */
	private boolean cube(WorldGenLevel level, BlockPos pos, int up, int size, int sink) {
		int x0 = pos.getX() - size / 2;
		int z0 = pos.getZ() - size / 2;
		int y0 = up > 0 ? pos.getY() - sink : pos.getY() + sink - size + 1;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int y = Math.max(y0, level.getMinY()); y < Math.min(y0 + size, level.getMaxY() + 1); y++) {
			for (int z = z0; z < z0 + size; z++) {
				for (int x = x0; x < x0 + size; x++) {
					BlockState old = level.getBlockState(at.set(x, y, z));
					if (open(old) || old.is(BlockTags.BASE_STONE_OVERWORLD)) {
						level.setBlock(at, state, 2);
						placed = true;
					}
				}
			}
		}
		return placed;
	}

	private static boolean open(BlockState state) {
		return state.isAir() || state.is(Blocks.WATER);
	}
}
