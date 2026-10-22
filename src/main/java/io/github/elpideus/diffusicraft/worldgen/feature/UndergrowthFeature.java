package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * {@code diffusicraft:undergrowth}: the thick growth of a rainforest's floor, which vanilla's jungles leave as open
 * grass under the trees. Thickets alternate with clearings over the floor ({@link Thickets}). In the thickets, shrubs
 * grow: mounds of leaves on the ground, up to four blocks high and as wide as nine, the taller ones around a stem of
 * {@code stem}. Between them and in the clearings, large ferns, ferns, grass, bushes and lone leaves cover the ground,
 * thickest in the thickets.
 *
 * <p>{@code cover} sets how thick it all grows, from 0 to 1: the old-growth jungle's floor is nearly closed, a sparse
 * jungle's half open. The leaves are persistent, so that the shrubs without a stem stay. Each chunk is placed once,
 * from its own columns, and a shrub reaches at most four blocks out of it. Nothing grows where a structure on the
 * surface reaches in.
 */
public record UndergrowthFeature(float cover, BlockState stem, BlockState leaves) implements Feature {
	public static final MapCodec<UndergrowthFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.floatRange(0f, 1f).fieldOf("cover").forGetter(UndergrowthFeature::cover),
		BlockState.CODEC.fieldOf("stem").forGetter(UndergrowthFeature::stem),
		BlockState.CODEC.fieldOf("leaves").forGetter(UndergrowthFeature::leaves)
	).apply(instance, UndergrowthFeature::new));

	/** Shrubs tried per chunk where the cover is full; a try in a clearing grows none. */
	private static final int SHRUBS = 40;
	/** Widest a shrub grows, from its middle to its rim, in blocks. */
	static final float WIDEST = 4f;
	/** No ground in a column. */
	private static final int NONE = Integer.MIN_VALUE;

	@Override
	public MapCodec<UndergrowthFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, @Nullable ChunkGenerator generator, RandomSource random,
						 BlockPos origin) {
		int x0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
		int z0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));
		int reach = (int) Math.ceil(WIDEST);
		if (Boulders.nearStructure(level, x0 - reach, z0 - reach, x0 + 15 + reach, z0 + 15 + reach)) {
			return false;
		}
		long seed = level.getSeed();
		BlockState leaf = leaves.trySetValue(LeavesBlock.PERSISTENT, true);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int i = Math.round(SHRUBS * cover); i > 0; i--) {
			int x = x0 + random.nextInt(16), z = z0 + random.nextInt(16);
			if (random.nextFloat() < Thickets.at(seed, x, z)) {
				placed |= shrub(level, x, z, leaf, random, pos);
			}
		}
		for (int x = x0; x < x0 + 16; x++) {
			for (int z = z0; z < z0 + 16; z++) {
				if (random.nextFloat() < cover * (0.25f + 0.75f * Thickets.at(seed, x, z))) {
					placed |= plant(level, x, z, leaf, random, pos);
				}
			}
		}
		return placed;
	}

	/**
	 * A shrub over the column at {@code (x, z)}: a mound of leaves that follows the ground, highest over the middle
	 * and a block high at its ragged rim, around a stem if it is three blocks high or more. Whether it grew.
	 */
	private boolean shrub(WorldGenLevel level, int x, int z, BlockState leaf, RandomSource random,
						  BlockPos.MutableBlockPos pos) {
		int ground = ground(level, x, z, pos);
		if (ground == NONE) {
			return false;
		}
		float u = random.nextFloat();
		float radius = 1.2f + (WIDEST - 1.2f) * u * u;
		int height = 1 + random.nextInt(radius > 2.5f ? 4 : 2);
		if (height >= 3 || random.nextInt(3) == 0) {
			for (int y = ground + 1; y < ground + height; y++) {
				set(level, pos.set(x, y, z), stem);
			}
		}
		int reach = (int) Math.ceil(radius);
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				float d = (float) Math.sqrt(dx * dx + dz * dz) / radius;
				// The rim is ragged: a column of it in three is left out.
				if (d > 1f || d > 0.7f && random.nextInt(3) == 0) {
					continue;
				}
				int under = ground(level, x + dx, z + dz, pos);
				if (under == NONE || Math.abs(under - ground) > 2) {
					continue;
				}
				int top = under + Math.max(1, Math.round(height * (float) Math.sqrt(1f - d * d)
					+ 0.6f * (random.nextFloat() - 0.5f)));
				for (int y = under + 1; y <= top; y++) {
					set(level, pos.set(x + dx, y, z + dz), leaf);
				}
				clearHalf(level, pos.set(x + dx, top + 1, z + dz));
			}
		}
		return true;
	}

	/** A plant of the floor at {@code (x, z)}, if the block over the ground is free and it would stand there. */
	private static boolean plant(WorldGenLevel level, int x, int z, BlockState leaf, RandomSource random,
								 BlockPos.MutableBlockPos pos) {
		int ground = ground(level, x, z, pos);
		if (ground == NONE || !level.isEmptyBlock(pos.set(x, ground + 1, z))) {
			return false;
		}
		float pick = random.nextFloat();
		if (pick < 0.3f) {
			BlockState fern = Blocks.LARGE_FERN.defaultBlockState();
			if (level.isEmptyBlock(pos.set(x, ground + 2, z)) && fern.canSurvive(level, pos.set(x, ground + 1, z))) {
				DoublePlantBlock.placeAt(level, fern, pos, 2);
				return true;
			}
			return false;
		}
		BlockState plant = pick < 0.6f ? Blocks.FERN.defaultBlockState()
			: pick < 0.75f ? Blocks.SHORT_GRASS.defaultBlockState()
			: pick < 0.9f ? Blocks.BUSH.defaultBlockState() : leaf;
		if (plant != leaf && !plant.canSurvive(level, pos.set(x, ground + 1, z))) {
			return false;
		}
		level.setBlock(pos.set(x, ground + 1, z), plant, 2);
		return true;
	}

	/** The height of the soil at the top of a column, under any leaves, or {@link #NONE} if it is not soil. */
	private static int ground(WorldGenLevel level, int x, int z, BlockPos.MutableBlockPos pos) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		return level.getBlockState(pos.set(x, y, z)).is(BlockTags.SUBSTRATE_OVERWORLD) ? y : NONE;
	}

	/** Sets a block of a shrub where there is air or a plant that a tree would grow through. */
	private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
		BlockState there = level.getBlockState(pos);
		if (there.isAir() || there.is(BlockTags.REPLACEABLE_BY_TREES) && there.getFluidState().isEmpty()) {
			level.setBlock(pos, state, 2);
		}
	}

	/** Clears the top half of a tall plant whose bottom half a shrub took, which would float otherwise. */
	private static void clearHalf(WorldGenLevel level, BlockPos pos) {
		BlockState there = level.getBlockState(pos);
		if (there.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
			&& there.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		}
	}
}
