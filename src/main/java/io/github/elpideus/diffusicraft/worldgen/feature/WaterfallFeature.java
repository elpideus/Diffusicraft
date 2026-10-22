package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * {@code diffusicraft:waterfall}: now and then, a stream that runs over the edge of a steep face in the chunk and falls
 * to its foot, down the head of a gorge it has cut, into a plunge pool or the water below. {@link Waterfall} lays it
 * out from the heightmaps, which know nothing of caves or of what is built.
 *
 * <p>The wetter the climate, the likelier a chunk is to have one, and the wider it falls: none below {@value #DRY} mm
 * of rain a year, and {@code chance} of the chunks from {@value #WET} mm, where the cliffs are high enough. None where
 * the water would freeze, which would leave the falls without their stream. Only in Diffusicraft worlds, whose climate
 * it reads.
 *
 * <p>The water is placed whole, down to the foot, so that the falls stand in the world from the start rather than
 * pouring down once the chunk is loaded; ticks are scheduled at the lips and at the foot of the falls, so that the
 * game's water takes over from there. Before any of it is placed, the world is checked where the plan cannot see:
 * none is placed where a village or another structure on the surface stands, where the ground cut away would let a
 * cave's water or lava in, or where a cave under the stream or the pool would let their water out.
 */
public record WaterfallFeature(int minDrop, float chance) implements Feature {
	public static final MapCodec<WaterfallFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.intRange(8, 4096).fieldOf("min_drop").forGetter(WaterfallFeature::minDrop),
		Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(WaterfallFeature::chance)
	).apply(instance, WaterfallFeature::new));

	/** Rain below which no waterfall runs, in mm a year. */
	private static final float DRY = 600f;
	/** Rain from which waterfalls run at their full chance, in mm a year. */
	private static final float WET = 2000f;

	@Override
	public MapCodec<WaterfallFeature> codec() {
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
		float rain = ClimateColumn.at(world.field(), world.scale(), x0 + 8, z0 + 8).column.precipitation;
		float wetness = Math.clamp((rain - DRY) / (WET - DRY), 0f, 1f);
		if (random.nextFloat() >= chance * wetness) {
			return false;
		}
		Waterfall fall = Waterfall.plan(new LevelGround(level), x0, z0, minDrop,
			Waterfall.widest(world.scale(), wetness), random);
		if (fall == null || !level.getBiome(fall.lip).value().warmEnoughToRain(fall.lip, level.getSeaLevel())
			|| nearStructure(level, fall) || !holds(level, fall)) {
			return false;
		}
		set(level, fall.cut, Blocks.AIR.defaultBlockState());
		set(level, fall.sources, Blocks.WATER.defaultBlockState());
		set(level, fall.spilling, Fluids.WATER.getFlowing(7, false).createLegacyBlock());
		set(level, fall.falling, Fluids.WATER.getFlowing(8, true).createLegacyBlock());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long tick : fall.ticks) {
			level.scheduleTick(pos.set(tick), Fluids.WATER, 0);
		}
		return true;
	}

	/** Whether a structure on the surface reaches into a chunk the waterfall would change. */
	private static boolean nearStructure(WorldGenLevel level, Waterfall fall) {
		LongOpenHashSet chunks = new LongOpenHashSet();
		for (LongArrayList positions : new LongArrayList[]{fall.cut, fall.sources}) {
			for (long pos : positions) {
				chunks.add(ChunkPos.pack(SectionPos.blockToSectionCoord(BlockPos.getX(pos)),
					SectionPos.blockToSectionCoord(BlockPos.getZ(pos))));
			}
		}
		for (long chunk : chunks) {
			for (Structure structure : level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)).getAllReferences()
				.keySet()) {
				if (structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether the world holds the waterfall where its plan assumed solid ground: no fluid beside the ground cut away,
	 * and nothing the water would run into beside or under the still water and under the foot of each fall.
	 */
	private static boolean holds(WorldGenLevel level, Waterfall fall) {
		LongOpenHashSet cut = new LongOpenHashSet(fall.cut), water = new LongOpenHashSet(fall.sources);
		water.addAll(fall.spilling);
		water.addAll(fall.falling);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (long pos : fall.cut) {
			for (Direction side : AROUND_CUT) {
				long next = BlockPos.offset(pos, side);
				if (!cut.contains(next) && !water.contains(next) && !level.getFluidState(at.set(next)).isEmpty()) {
					return false;
				}
			}
		}
		for (long pos : fall.sources) {
			for (Direction side : AROUND_STILL) {
				if (!holdsWater(level, cut, water, at.set(BlockPos.offset(pos, side)))) {
					return false;
				}
			}
		}
		for (long pos : fall.falling) {
			if (!holdsWater(level, cut, water, at.set(BlockPos.offset(pos, Direction.DOWN)))) {
				return false;
			}
		}
		return true;
	}

	private static final Direction[] AROUND_CUT = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST,
		Direction.EAST};
	private static final Direction[] AROUND_STILL = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
		Direction.EAST};

	/** Whether the block at {@code pos} keeps water out: the waterfall's own water, other water, or solid ground. */
	private static boolean holdsWater(WorldGenLevel level, LongOpenHashSet cut, LongOpenHashSet water,
									  BlockPos pos) {
		long at = pos.asLong();
		if (water.contains(at)) {
			return true;
		}
		if (cut.contains(at)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return state.getFluidState().is(FluidTags.WATER)
			|| !state.is(BlockTags.WASHED_AWAY_BY_FLUIDS) && !(state.getBlock() instanceof LiquidBlockContainer);
	}

	private static void set(WorldGenLevel level, LongArrayList positions, BlockState state) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long position : positions) {
			level.setBlock(pos.set(position), state, 2);
		}
	}

	/** The ground as the chunk's heightmaps have it before the plants and trees grow. */
	private record LevelGround(WorldGenLevel level) implements Waterfall.Ground {
		@Override
		public int top(int x, int z) {
			return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
		}

		@Override
		public int water(int x, int z) {
			int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
			return surface > top(x, z) && level.getFluidState(new BlockPos(x, surface, z)).is(FluidTags.WATER)
				? surface : Waterfall.DRY;
		}
	}
}
