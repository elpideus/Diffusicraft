package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * {@code diffusicraft:river_falls}: the falls of the rivers, where they step down ({@link RiverFalls}), so that a river
 * pours over each step into the river below rather than standing in a wall of still water: water flowing over the
 * edge at the top of the step and falling below it, as the game's water settles where it runs off a ledge, and a pool
 * of up to {@code deepest_pool} blocks sunk into the bed at the foot.
 *
 * <p>The water is placed as it would settle, so that nothing flows once the chunk is loaded. Only in Diffusicraft
 * worlds, whose rivers it reads, and only where the world still has them as they were laid out: the curtain needs the
 * water above and below it and air between, and the pool digs only into ground that holds its water on every side.
 * Nothing changes where a structure on the surface reaches into the chunk.
 */
public record RiverFallFeature(int deepestPool) implements Feature {
	public static final MapCodec<RiverFallFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.intRange(0, 16).fieldOf("deepest_pool").forGetter(RiverFallFeature::deepestPool)
	).apply(instance, RiverFallFeature::new));

	private static final BlockState EDGE = Fluids.WATER.getFlowing(7, false).createLegacyBlock();
	private static final BlockState FALLING = Fluids.WATER.getFlowing(8, true).createLegacyBlock();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	@Override
	public MapCodec<RiverFallFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, @Nullable ChunkGenerator generator, RandomSource random,
						 BlockPos origin) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		if (world == null) {
			return false;
		}
		int x0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
		int z0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));
		if (Boulders.nearStructure(level, x0, z0, x0 + 15, z0 + 15)) {
			return false;
		}
		RiverFalls falls = RiverFalls.read(Rivers.of(world.field()), world.scale(), x0, z0, deepestPool);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int water = falls.water(x, z), top = falls.curtain[x + 16 * z], sink = falls.pool[x + 16 * z];
				if (top != RiverFalls.NONE) {
					placed |= curtain(level, x0 + x, z0 + z, water, top, pos);
				}
				if (sink > 0) {
					placed |= pool(level, x0 + x, z0 + z, water, sink, pos);
				}
			}
		}
		return placed;
	}

	/**
	 * Hangs a curtain over the column at {@code (x, z)} from {@code top} down to the river's water at {@code water},
	 * if the water is there, the air above it clear and the water at the top of the step beside it.
	 */
	private static boolean curtain(WorldGenLevel level, int x, int z, int water, int top,
								   BlockPos.MutableBlockPos pos) {
		if (!level.getFluidState(pos.set(x, water, z)).is(FluidTags.WATER)) {
			return false;
		}
		for (int y = water + 1; y <= top; y++) {
			if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
				return false;
			}
		}
		boolean fed = false;
		for (int[] d : AROUND) {
			FluidState beside = level.getFluidState(pos.set(x + d[0], top, z + d[1]));
			fed |= beside.isSource() && beside.is(FluidTags.WATER);
		}
		if (!fed) {
			return false;
		}
		level.setBlock(pos.set(x, top, z), EDGE, 2);
		for (int fall = water + 1; fall < top; fall++) {
			level.setBlock(pos.set(x, fall, z), FALLING, 2);
		}
		return true;
	}

	/**
	 * Sinks the river's bed at {@code (x, z)}, under its water at {@code water}, by up to {@code sink} blocks, as far
	 * down as the ground beside and below holds the water, and lays the bed's sediment on the new floor.
	 */
	private static boolean pool(WorldGenLevel level, int x, int z, int water, int sink,
								BlockPos.MutableBlockPos pos) {
		int bed = water;
		while (bed > level.getMinY() && level.getFluidState(pos.set(x, bed, z)).is(FluidTags.WATER)) {
			bed--;
		}
		if (bed == water) {
			return false;
		}
		BlockState sediment = level.getBlockState(pos.set(x, bed, z));
		if (!solid(sediment)) {
			return false;
		}
		int bottom = bed + 1;
		for (int y = bed; y > bed - sink && solid(level.getBlockState(pos.set(x, y, z))); y--) {
			boolean held = true;
			for (int[] d : AROUND) {
				held &= holds(level.getBlockState(pos.set(x + d[0], y, z + d[1])));
			}
			if (!held) {
				break;
			}
			bottom = y;
		}
		if (bottom <= bed && !holds(level.getBlockState(pos.set(x, bottom - 1, z)))) {
			bottom++;
		}
		if (bottom > bed) {
			return false;
		}
		for (int y = bottom; y <= bed; y++) {
			level.setBlock(pos.set(x, y, z), WATER, 2);
		}
		if (solid(level.getBlockState(pos.set(x, bottom - 2, z)))) {
			level.setBlock(pos.set(x, bottom - 1, z), sediment, 2);
		}
		return true;
	}

	/** Whether a block keeps water in: water itself or solid ground. */
	private static boolean holds(BlockState state) {
		return state.getFluidState().is(FluidTags.WATER) || solid(state);
	}

	/** Whether a block is solid ground, which water neither flows into nor washes away. */
	private static boolean solid(BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.WASHED_AWAY_BY_FLUIDS)
			&& !(state.getBlock() instanceof LiquidBlockContainer);
	}
}
