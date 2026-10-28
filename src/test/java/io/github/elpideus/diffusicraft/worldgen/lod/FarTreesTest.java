package io.github.elpideus.diffusicraft.worldgen.lod;

import com.mojang.serialization.MapCodec;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarTreesTest {
	private static final long SEED = 42;
	/** The top block of the flat ground of the tests. */
	private static final int GROUND = 64;
	private static final int MIN_Y = -64;
	private static final int SEA_LEVEL = 63;
	private static final LevelHeightAccessor HEIGHTS = LevelHeightAccessor.create(MIN_Y, 384);
	private static final FarSketch.Ground FLAT = (x, z) -> FarSketch.Land.dry(GROUND);
	private static final FarSketch.Soil GRASS = (x, z, land) -> Blocks.GRASS_BLOCK.defaultBlockState();
	/** Blocks each way, from block 0, that the tests look at. */
	private static final int SIZE = 128;
	/** Where the oak woods give way to the desert. */
	private static final int EDGE = 80;

	private static BlockState rock;
	/** The oaks of the woods, the second feature of their step. */
	private static PlacedFeature oaks;
	/** A feature that grows nothing, the first of the step. */
	private static PlacedFeature nothing;
	private static Holder<Biome> woods;
	private static Holder<Biome> desert;
	/** The woods west of {@link #EDGE} and the desert east of it. */
	private static BiomeResolver biomes;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// No data pack is loaded, so the tags that an oak asks of its ground and of its own blocks are bound here.
		BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(
			BlockTags.SUPPORTS_VEGETATION, holders(Blocks.GRASS_BLOCK, Blocks.DIRT),
			BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.SHORT_GRASS),
			BlockTags.LOGS, holders(Blocks.OAK_LOG),
			BlockTags.LEAVES, holders(Blocks.OAK_LEAVES),
			BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP, holders(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.OAK_LOG,
				Blocks.OAK_LEAVES),
			BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP_NO_LEAVES, holders(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE,
				Blocks.OAK_LOG)))).apply();
		rock = Blocks.STONE.defaultBlockState();
		// Vanilla's oak, three to a chunk where an oak sapling would grow.
		TreeFeature oak = new TreeFeature.Builder(BlockStateProvider.of(Blocks.OAK_LOG),
			new StraightTrunkPlacer(4, 2, 0), BlockStateProvider.of(Blocks.OAK_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
			new TwoLayersFeatureSize(1, 0, 1), Holder.direct(BlockStateProvider.of(Blocks.DIRT))).ignoreVines().build();
		oaks = new PlacedFeature(Holder.direct(oak),
			VegetationPlacements.treePlacement(CountPlacement.of(3), Blocks.OAK_SAPLING));
		nothing = new PlacedFeature(Holder.direct(new Nothing()), List.of());
		woods = biome(nothing, oaks);
		desert = biome();
		biomes = (x, y, z) -> x < EDGE / 4 ? woods : desert;
	}

	private static List<Holder<Block>> holders(Block... blocks) {
		return Arrays.stream(blocks).<Holder<Block>>map(Block::builtInRegistryHolder).toList();
	}

	private static Holder<Biome> biome(PlacedFeature... features) {
		BiomeGenerationSettings.PlainBuilder settings = new BiomeGenerationSettings.PlainBuilder();
		for (PlacedFeature feature : features) {
			settings.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, Holder.direct(feature));
		}
		return Holder.direct(new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.7f)
			.downfall(0.8f)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(settings.build())
			.build());
	}

	/** A feature that grows nothing. */
	private static final class Nothing implements Feature {
		@Override
		public MapCodec<? extends Feature> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
			return false;
		}
	}

	private static FarTrees trees() {
		FarTrees trees = FarTrees.of(null, new GreatTreesTest.Generator(woods), HEIGHTS, SEA_LEVEL, SEED,
			List.of(nothing, oaks), WorldScale.ULTRA);
		assertNotNull(trees);
		return trees;
	}

	private static FarTrees.Crowns grow(FarTrees trees, int minX, int minZ, int width, int step) {
		return trees.grow(minX, minZ, width, step, FLAT, GRASS, rock, biomes);
	}

	/**
	 * The columns where the world's oaks stand over blocks 0 to {@link #SIZE} each way, every chunk that reaches them
	 * decorated one after the other as the chunk generator decorates it, on the same ground: each oak keeps clear of
	 * those grown before it, in its chunk or in another.
	 */
	private static Set<Long> worldTrunks() {
		FarSketch world = new FarSketch(null, HEIGHTS, SEA_LEVEL, SEED, FLAT, GRASS, rock, biomes);
		FeaturePlacer placer = new FeaturePlacer(world, new GreatTreesTest.Generator(woods));
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		for (int chunkZ = -1; chunkZ <= SIZE / 16; chunkZ++) {
			for (int chunkX = -1; chunkX <= SIZE / 16; chunkX++) {
				BlockPos origin = new BlockPos(chunkX * 16, MIN_Y, chunkZ * 16);
				long decoration = random.setDecorationSeed(SEED, origin.getX(), origin.getZ());
				random.setFeatureSeed(decoration, 1, GenerationStep.Decoration.VEGETAL_DECORATION.ordinal());
				placer.placeWithBiomeCheck(oaks, random, origin);
			}
		}
		Set<Long> trunks = new HashSet<>();
		world.blocks().forEach((pos, state) -> {
			int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
			if (state.is(Blocks.OAK_LOG) && BlockPos.getY(pos) == GROUND + 1 && x >= 0 && z >= 0 && x < SIZE
				&& z < SIZE) {
				trunks.add(BlockPos.asLong(x, 0, z));
			}
		});
		return trunks;
	}

	@Test
	void treesStandWhereTheWorldGrowsThem() {
		Set<Long> planted = worldTrunks();
		// Three in each chunk of the woods, but for those that would grow under another's crown.
		assertTrue(planted.size() > 2 * (SIZE / 16) * (EDGE / 16), planted.size() + " oaks");
		FarTrees.Crowns crowns = grow(trees(), 0, 0, SIZE, 1);
		TreeCover.Crown crown = new TreeCover.Crown();
		Set<Long> drawn = new HashSet<>();
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				if (crowns.crown(x, z, crown)) {
					assertSame(Blocks.OAK_LEAVES, crown.leaves.getBlock());
					assertTrue(crown.low >= 2 && crown.high <= 8, "an oak from " + crown.low + " to " + crown.high);
					if (crown.holdsTrunk) {
						assertSame(Blocks.OAK_LOG, crown.trunk.getBlock());
						// None takes root in the desert, though a crown may reach over it.
						assertFalse(x >= EDGE + 4, "a trunk in the desert at " + x + ", " + z);
						drawn.add(BlockPos.asLong(x, 0, z));
					}
				}
			}
		}
		Set<Long> missing = new HashSet<>(planted);
		missing.removeAll(drawn);
		Set<Long> phantoms = new HashSet<>(drawn);
		phantoms.removeAll(planted);
		// Each chunk grows its oaks on the bare ground: an oak that the world keeps from growing under the crown of one
		// of another chunk still shows, and the trees after it in its chunk may stand elsewhere.
		assertTrue(missing.size() <= planted.size() / 20, missing.size() + " of " + planted.size() + " oaks missing");
		assertTrue(phantoms.size() <= planted.size() / 8, phantoms.size() + " oaks that the world never grows, "
			+ planted.size() + " that it does");
	}

	@Test
	void everyGridDrawsTheSameTreesWhereItsColumnsMeet() {
		FarTrees.Crowns fine = grow(trees(), 0, 0, SIZE, 1);
		// Another grid of the same step, shifted and grown again.
		assertSameCrowns(fine, grow(trees(), 40, 24, 64, 1), 40, 24, 64, 1);
		for (int step = 2; step <= FarTrees.MAX_STEP; step *= 2) {
			assertSameCrowns(fine, grow(trees(), 0, 0, SIZE / step, step), 0, 0, SIZE / step, step);
		}
	}

	/**
	 * Checks that the grid of {@code crowns}, {@code width}² columns {@code step} blocks apart from block
	 * {@code (minX, minZ)}, draws in each column the crown that {@code fine}, a grid a block apart from block 0, does.
	 * Only a trunk as wide as the step between them shows.
	 */
	private static void assertSameCrowns(FarTrees.Crowns fine, FarTrees.Crowns crowns, int minX, int minZ, int width,
										 int step) {
		TreeCover.Crown expected = new TreeCover.Crown();
		TreeCover.Crown actual = new TreeCover.Crown();
		int covered = 0;
		for (int b = 0; b < width; b++) {
			for (int a = 0; a < width; a++) {
				int x = crowns.block(minX, a), z = crowns.block(minZ, b);
				boolean grown = fine.crown(x, z, expected);
				assertEquals(grown, crowns.crown(a, b, actual), "a crown at " + x + ", " + z + " " + step + " apart");
				if (!grown) {
					continue;
				}
				covered++;
				assertSame(expected.leaves, actual.leaves);
				assertEquals(expected.low, actual.low);
				assertEquals(expected.high, actual.high);
				assertEquals(expected.holdsTrunk && step == 1, actual.holdsTrunk, "a trunk at " + x + ", " + z);
			}
		}
		assertTrue(covered > width * width / 20, covered + " of " + width * width + " columns under a crown");
	}
}
