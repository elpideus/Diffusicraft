package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * {@code diffusicraft:giant_tree}, the structure: a giant tree whose crown spreads wider than a feature may write. A
 * feature writes only in the 3×3 chunks around its own, which held a crown to some 40 blocks across; a structure is
 * written a chunk at a time as each chunk is decorated ({@link GiantTreePiece}), so the broadleaf giants, the ancient
 * oaks and the kapoks, spread their crowns as wide as their forms give them. The redwoods, whose crowns are narrow,
 * stay features ({@link GiantTreeFeature}).
 *
 * <p>Where a structure set has a chunk for it, a giant grows there with the structure's {@code chance}. The tree is
 * drawn before the chunk has any blocks, from the land the generator gives: it needs the structure's biome at its foot,
 * dry ground, and ground around its foot within a few blocks of it. It grows after every smaller tree, as a feature
 * giant does, through their crowns. Only in Diffusicraft worlds, at the size of the world: see {@link GiantTree#size}.
 */
public final class GiantTreeStructure extends Structure {
	public static final MapCodec<GiantTreeStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		settingsCodec(instance),
		GiantTreeFeature.Form.CODEC.fieldOf("form").forGetter(structure -> structure.form),
		BlockState.CODEC.fieldOf("wood").forGetter(structure -> structure.wood),
		BlockState.CODEC.fieldOf("leaves").forGetter(structure -> structure.leaves),
		IntProviders.POSITIVE_CODEC.fieldOf("height").forGetter(structure -> structure.height),
		Codec.floatRange(0, 1).optionalFieldOf("chance", 1f).forGetter(structure -> structure.chance)
	).apply(instance, GiantTreeStructure::new));
	public static final StructureType<GiantTreeStructure> TYPE = () -> CODEC;

	private final GiantTreeFeature.Form form;
	private final BlockState wood;
	private final BlockState leaves;
	/** The heights it grows to in a 1:1 world. */
	private final IntProvider height;
	/** The chance that a chunk the structure set gives it grows one. */
	private final float chance;

	public GiantTreeStructure(StructureSettings settings, GiantTreeFeature.Form form, BlockState wood,
							  BlockState leaves, IntProvider height, float chance) {
		super(settings);
		this.form = form;
		this.wood = wood;
		this.leaves = leaves;
		this.height = height;
		this.chance = chance;
	}

	/**
	 * Where in a chunk a giant would grow, how tall, and the seed of its shape: the chunk's draws, before the tree meets
	 * the land. {@code x} and {@code z} are the block of its foot.
	 */
	public record Sapling(int x, int z, int height, long seed) {
	}

	/**
	 * The sapling the chunk at {@code chunkX}, {@code chunkZ} of a world of {@code seed} and {@code scale} draws, or
	 * {@code null} if it draws none. The same draws every time, so that the LODs foresee the tree.
	 */
	public @Nullable Sapling sapling(long seed, int chunkX, int chunkZ, WorldScale scale) {
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		if (random.nextFloat() >= chance) {
			return null;
		}
		int x = SectionPos.sectionToBlockCoord(chunkX, random.nextInt(16));
		int z = SectionPos.sectionToBlockCoord(chunkZ, random.nextInt(16));
		int height = (int) Math.round(this.height.sample(random) * GiantTree.size(scale));
		return new Sapling(x, z, height, random.nextLong());
	}

	@Override
	protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) context.randomState()).diffusicraft$biomeWorld();
		if (world == null) {
			return Optional.empty();
		}
		ChunkPos chunk = context.chunkPos();
		Sapling sapling = sapling(context.seed(), chunk.x(), chunk.z(), world.scale());
		if (sapling == null) {
			return Optional.empty();
		}
		ChunkGenerator generator = context.chunkGenerator();
		LevelHeightAccessor heights = context.heightAccessor();
		RandomState state = context.randomState();
		int x = sapling.x(), z = sapling.z();
		int ground = dryGround(generator, x, z, heights, state);
		if (ground == Integer.MIN_VALUE || !context.validBiome().test(context.biomeResolver()
			.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(ground), QuartPos.fromBlock(z)))) {
			return Optional.empty();
		}
		int height = Math.min(sapling.height(), heights.getMaxY() - ground - GiantTreeFeature.HEADROOM);
		if (height < GiantTreeFeature.MIN_HEIGHT * GiantTree.size(world.scale())) {
			return Optional.empty();
		}
		GiantTree plan = GiantTree.plan(form, height, GiantTree.MAX_REACH, RandomSource.create(sapling.seed()));
		int footReach = plan.blocks(new BoundingBox(-plan.reach, 0, -plan.reach, plan.reach, 0, plan.reach)).footReach;
		if (!levelAround(generator, x, ground, z, footReach + 2, heights, state)) {
			return Optional.empty();
		}
		BlockPos base = new BlockPos(x, ground, z);
		BoundingBox box = new BoundingBox(x - plan.reach, ground - GiantTreeFeature.ROOTS, z - plan.reach,
			x + plan.reach, ground + plan.top(), z + plan.reach);
		GiantTreePiece piece = new GiantTreePiece(form, wood, leaves, base, height, plan.reach, sapling.seed(), box);
		return Optional.of(new GenerationStub(base, builder -> builder.addPiece(piece)));
	}

	/** The first block over the ground of a column, or {@link Integer#MIN_VALUE} if water covers it. */
	private static int dryGround(ChunkGenerator generator, int x, int z, LevelHeightAccessor heights,
								 RandomState state) {
		int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, state);
		return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, state) == ground
			? ground : Integer.MIN_VALUE;
	}

	/**
	 * Whether the ground {@code distance} blocks around the foot lies within {@value GiantTreeFeature#UNEVEN} blocks of
	 * it, and dry, as {@link GiantTreeFeature} asks of its own.
	 */
	private static boolean levelAround(ChunkGenerator generator, int x, int ground, int z, int distance,
									   LevelHeightAccessor heights, RandomState state) {
		int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
		for (int k = 0; k < 8; k++) {
			double angle = k * Math.PI / 4;
			int y = dryGround(generator, x + (int) Math.round(Math.cos(angle) * distance),
				z + (int) Math.round(Math.sin(angle) * distance), heights, state);
			if (y == Integer.MIN_VALUE || Math.abs(y - ground) > GiantTreeFeature.UNEVEN) {
				return false;
			}
			low = Math.min(low, y);
			high = Math.max(high, y);
		}
		return high - low <= GiantTreeFeature.UNEVEN;
	}

	@Override
	public StructureType<?> type() {
		return TYPE;
	}
}
