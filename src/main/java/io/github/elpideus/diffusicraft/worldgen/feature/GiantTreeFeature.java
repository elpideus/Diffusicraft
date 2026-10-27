package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * {@code diffusicraft:giant_tree}: a tree a hundred blocks tall or more, of one of the {@link Form forms}: a redwood,
 * an ancient oak or a kapok. {@link GiantTree} gives its shape.
 *
 * <p>It grows from the ground under the origin, looking down through the leaves and plants of smaller trees, whose
 * crowns its trunk and limbs then grow through, as an emergent rises through the canopy. It needs soil, and level
 * ground around its foot: the roots fill down to the ground where it falls away a little, but not off a cliff, nor
 * into water. It does not grow under another giant's crown, nor against another's foot.
 *
 * <p>A feature writes only in the 3×3 chunks around its own, so the crown reaches at most as far as the nearest edge
 * of those chunks, and {@link GiantTree#MAX_REACH} blocks: about 40 blocks across. Placed nearer the middle of its
 * chunk, a tree has more room. The height is cut to fit under the top of the world. That is room enough for the narrow
 * crowns of the redwoods; the broadleaf giants of the data pack grow as structures instead ({@link
 * GiantTreeStructure}), which this feature still places in the LOD mods' stand-in world and the game tests.
 *
 * <p>The heights of the data pack are a 1:1 world's. The smaller the world, the smaller the giants, though much less
 * than the land: see {@link GiantTree#size}. Outside a Diffusicraft world they grow to their full height.
 */
public record GiantTreeFeature(Form form, BlockState wood, BlockState leaves, IntProvider height) implements Feature {
	public static final MapCodec<GiantTreeFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Form.CODEC.fieldOf("form").forGetter(GiantTreeFeature::form),
		BlockState.CODEC.fieldOf("wood").forGetter(GiantTreeFeature::wood),
		BlockState.CODEC.fieldOf("leaves").forGetter(GiantTreeFeature::leaves),
		IntProviders.POSITIVE_CODEC.fieldOf("height").forGetter(GiantTreeFeature::height)
	).apply(instance, GiantTreeFeature::new));

	/** Lowest a giant of full size grows: less room than this under the top of the world, and it does not grow. */
	static final int MIN_HEIGHT = 40;

	/** Room kept free over the top of the tree, in blocks. */
	static final int HEADROOM = 6;
	/** Leaves and plants over the ground through which a giant still finds it, in blocks: higher is another's crown. */
	private static final int UNDERSTOREY = 32;
	/** Most the ground around the foot may rise or fall, in blocks. */
	static final int UNEVEN = 6;
	/** Deepest the roots reach down to the ground, in blocks. */
	static final int ROOTS = 12;
	/** Farthest any part of a giant lies from its trunk's axis, in blocks: see {@link #room}. */
	public static final int MAX_ROOM = GiantTree.MAX_REACH;

	/** The forms a giant takes. */
	public enum Form implements StringRepresentable {
		REDWOOD("redwood"),
		OAK("oak"),
		KAPOK("kapok");

		public static final Codec<Form> CODEC = StringRepresentable.fromEnum(Form::values);

		private final String name;

		Form(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	@Override
	public MapCodec<GiantTreeFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, @Nullable ChunkGenerator generator, RandomSource random,
						 BlockPos origin) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		// Outside a Diffusicraft world, at the height of a 1:1 one.
		return place(level, random, origin, world == null ? WorldScale.ULTRA : world.scale());
	}

	/**
	 * Grows a giant at {@code origin} as it grows in a world of {@code scale}. For the LOD mods' stand-in world, which
	 * cannot say how large the world is.
	 */
	public boolean place(WorldGenLevel level, RandomSource random, BlockPos origin, WorldScale scale) {
		BlockPos base = ground(level, origin);
		if (base == null) {
			return false;
		}
		int height = fit(height(random, scale), base.getY(), level.getMaxY(), scale);
		if (height == 0) {
			return false;
		}
		GiantTree.Blocks tree = GiantTree.grow(form, height, room(origin.getX(), origin.getZ()), random);
		if (!levelAround(level, base, tree.footReach + 2)) {
			return false;
		}
		int chunkX = SectionPos.blockToSectionCoord(origin.getX());
		int chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
		write(level, base, tree, wood, leaves, pos -> writable(level, pos, chunkX, chunkZ));
		return true;
	}

	/**
	 * Draws how tall a giant grows in a world of {@code scale}, before the top of the world cuts it: the first draw of a
	 * giant that has found its ground.
	 */
	public int height(RandomSource random, WorldScale scale) {
		return (int) Math.round(this.height.sample(random) * GiantTree.size(scale));
	}

	/**
	 * How tall a giant drawn {@code height} blocks tall grows with its foot at {@code base}, in a world of
	 * {@code scale}: cut to fit under {@code maxY}, the top of the world, or {@code 0} if it is then too short to grow
	 * at all.
	 */
	public static int fit(int height, int base, int maxY, WorldScale scale) {
		height = Math.min(height, maxY - base - HEADROOM);
		return height < MIN_HEIGHT * GiantTree.size(scale) ? 0 : height;
	}

	/** The lowest a giant of a world of {@code scale} grows, before the top of the world cuts it. */
	public int minHeight(WorldScale scale) {
		return (int) Math.round(height.minInclusive() * GiantTree.size(scale));
	}

	/** The highest a giant of a world of {@code scale} grows. */
	public int maxHeight(WorldScale scale) {
		return (int) Math.round(height.maxInclusive() * GiantTree.size(scale));
	}

	/**
	 * Lays the whole of a giant {@code height} blocks tall, no block of it farther than {@code room} from its trunk's
	 * axis, drawn from {@code seed}, into {@code out}.
	 */
	public void grow(int height, int room, long seed, GiantTreeStructure.BlockSink out) {
		GiantTreeStructure.lay(GiantTree.grow(form, height, room, RandomSource.create(seed)), out);
	}

	/** The first block over the soil under {@code origin}, or {@code null} if there is none to grow from. */
	private @Nullable BlockPos ground(WorldGenLevel level, BlockPos origin) {
		BlockPos.MutableBlockPos pos = origin.mutable();
		for (int through = 0; pos.getY() > level.getMinY(); through++) {
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.LOGS) || through > UNDERSTOREY) {
				return null;
			}
			if (solid(state)) {
				return state.is(BlockTags.SUBSTRATE_OVERWORLD) && level.getFluidState(pos.above()).isEmpty()
					? pos.above().immutable() : null;
			}
			pos.move(0, -1, 0);
		}
		return null;
	}

	/**
	 * Whether the ground {@code distance} blocks around {@code base} lies within {@value #UNEVEN} blocks of it, dry,
	 * and clear of another giant's foot.
	 */
	private boolean levelAround(WorldGenLevel level, BlockPos base, int distance) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
		for (int k = 0; k < 8; k++) {
			double angle = k * Math.PI / 4;
			int x = base.getX() + (int) Math.round(Math.cos(angle) * distance);
			int z = base.getZ() + (int) Math.round(Math.sin(angle) * distance);
			int y = base.getY() + UNEVEN + 1;
			if (solid(level.getBlockState(pos.set(x, y, z)))) {
				return false;
			}
			for (y--; y >= base.getY() - UNEVEN - 1; y--) {
				BlockState state = level.getBlockState(pos.set(x, y, z));
				if (state.is(wood.getBlock()) || !state.getFluidState().isEmpty()) {
					return false;
				}
				if (solid(state)) {
					break;
				}
			}
			if (y < base.getY() - UNEVEN - 1) {
				return false;
			}
			low = Math.min(low, y);
			high = Math.max(high, y);
		}
		return high - low <= UNEVEN;
	}

	/** Whether a block is the ground: neither air, nor a tree, nor a plant or water that a tree grows through. */
	private static boolean solid(BlockState state) {
		return !state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.canBeReplaced();
	}

	/**
	 * How far a tree at block {@code (x, z)} may reach: to the nearest edge of the chunks around its own, less a block,
	 * and {@value #MAX_ROOM} blocks at most.
	 */
	public static int room(int x, int z) {
		x &= 15;
		z &= 15;
		return Math.min(MAX_ROOM, Math.min(Math.min(x + 16, 31 - x), Math.min(z + 16, 31 - z)) - 1);
	}

	/**
	 * Writes a tree's blocks with its foot at {@code base}, those of them that are {@code writable}, through the plants
	 * and smaller trees there.
	 */
	static void write(WorldGenLevel level, BlockPos base, GiantTree.Blocks tree, BlockState wood, BlockState leaves,
					  Predicate<BlockPos> writable) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long relative : tree.wood) {
			at(pos, base, relative);
			if (writable.test(pos) && grownThrough(level.getBlockState(pos), true)) {
				level.setBlock(pos, wood, 2);
			}
		}
		// The roots, down to the ground where it falls away under the foot.
		for (long relative : tree.foot) {
			at(pos, base, relative);
			for (int depth = 1; depth <= ROOTS && writable.test(pos.move(0, -1, 0)); depth++) {
				BlockState state = level.getBlockState(pos);
				if (!grownThrough(state, true) || state.is(wood.getBlock())) {
					break;
				}
				level.setBlock(pos, wood, 2);
			}
		}
		Long2IntOpenHashMap distances = tree.distances();
		for (long relative : tree.leaves) {
			at(pos, base, relative);
			if (writable.test(pos) && grownThrough(level.getBlockState(pos), false)) {
				// Leaves too far from the wood to live on it are kept alive.
				int distance = distances.getOrDefault(relative, LeavesBlock.DECAY_DISTANCE);
				BlockState leaf = leaves.hasProperty(LeavesBlock.DISTANCE) ? leaves
					.setValue(LeavesBlock.DISTANCE, distance)
					.setValue(LeavesBlock.PERSISTENT, distance >= LeavesBlock.DECAY_DISTANCE) : leaves;
				level.setBlock(pos, leaf, 2);
			}
		}
	}

	private static void at(BlockPos.MutableBlockPos pos, BlockPos base, long relative) {
		pos.set(base.getX() + BlockPos.getX(relative), base.getY() + BlockPos.getY(relative),
			base.getZ() + BlockPos.getZ(relative));
	}

	private static boolean writable(WorldGenLevel level, BlockPos pos, int chunkX, int chunkZ) {
		return pos.getY() >= level.getMinY() && pos.getY() <= level.getMaxY()
			&& Math.abs(SectionPos.blockToSectionCoord(pos.getX()) - chunkX) <= 1
			&& Math.abs(SectionPos.blockToSectionCoord(pos.getZ()) - chunkZ) <= 1;
	}

	/**
	 * Whether the tree grows through a block: air, plants, and smaller trees' leaves, and their wood too for its own
	 * wood. Its leaves stay out of water.
	 */
	private static boolean grownThrough(BlockState state, boolean asWood) {
		if (state.isAir() || state.is(BlockTags.LEAVES)) {
			return true;
		}
		if (asWood && state.is(BlockTags.LOGS)) {
			return true;
		}
		return state.canBeReplaced() && (asWood || state.getFluidState().isEmpty());
	}
}
