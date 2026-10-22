package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code diffusicraft:lianas}: lets the vines that hang from the trees of a chunk grow down towards the floor, as they
 * would over the years: vanilla's trees hang theirs four blocks or so below a leaf, and the free ones it scatters
 * through jungles stay where they are, so that its forests show open air under their crowns.
 *
 * <p>Each hanging vine with a face on a side grows, with chance {@code chance}, at least halfway down to whatever is
 * below it, and as far as the floor at most. The vines it adds keep the faces on the sides of the vine they hang from,
 * which is what holds them: as in vanilla, a vine with a face on a side stays wherever the vine over it has that face.
 * Each chunk is placed once, from its own columns, so the feature writes to its own chunk only.
 */
public record LianaFeature(float chance) implements Feature {
	public static final MapCodec<LianaFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(LianaFeature::chance)
	).apply(instance, LianaFeature::new));

	/** The blocks of one column, by height. */
	interface Column {
		BlockState get(int y);

		void set(int y, BlockState state);
	}

	@Override
	public MapCodec<LianaFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int x0 = SectionPos.blockToSectionCoord(origin.getX()) << 4, z0 = SectionPos.blockToSectionCoord(origin.getZ()) << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int x = x0; x < x0 + 16; x++) {
			for (int z = z0; z < z0 + 16; z++) {
				int cx = x, cz = z;
				Column column = new Column() {
					@Override
					public BlockState get(int y) {
						return level.getBlockState(pos.set(cx, y, cz));
					}

					@Override
					public void set(int y, BlockState state) {
						level.setBlock(pos.set(cx, y, cz), state, 2);
					}
				};
				// Vines block nothing, and a vine hangs beside the leaf or the log that holds it, often outside the
				// crown: so the column's top is the highest of its own and its neighbours', and the ground stops trunks.
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				for (Direction side : Direction.Plane.HORIZONTAL) {
					top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + side.getStepX(), z + side.getStepZ()));
				}
				placed |= grow(column, top - 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), chance,
					random);
			}
		}
		return placed;
	}

	/**
	 * Grows the hanging vines of a column, from {@code top} down to the floor at {@code ground}, the first block over
	 * the solid ground. Whether any grew.
	 */
	static boolean grow(Column column, int top, int ground, float chance, RandomSource random) {
		boolean grew = false;
		for (int y = top; y > ground; y--) {
			BlockState vine = column.get(y);
			if (!vine.is(Blocks.VINE) || !hangs(vine) || !column.get(y - 1).isAir()) {
				continue;
			}
			int floor = y - 1;
			while (floor > ground && column.get(floor - 1).isAir()) {
				floor--;
			}
			// The air from just below the vine down to the floor: it grows into the upper half or more.
			int gap = y - floor;
			if (random.nextFloat() >= chance || gap < 2) {
				y = floor;
				continue;
			}
			int end = floor + random.nextInt(gap / 2 + 1);
			BlockState below = Blocks.VINE.defaultBlockState();
			for (Direction side : Direction.Plane.HORIZONTAL) {
				below = below.setValue(VineBlock.getPropertyForFace(side), vine.getValue(VineBlock.getPropertyForFace(side)));
			}
			for (int v = y - 1; v >= end; v--) {
				column.set(v, below);
			}
			grew = true;
			y = end;
		}
		return grew;
	}

	/** Whether a vine has a face on a side, from which another can hang. */
	private static boolean hangs(BlockState vine) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (vine.getValue(VineBlock.getPropertyForFace(side))) {
				return true;
			}
		}
		return false;
	}
}
