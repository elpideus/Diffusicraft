package io.github.elpideus.diffusicraft.worldgen.lod;

import com.mojang.serialization.MapCodec;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeStructure;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.util.valueproviders.WeightedListInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreatTreesTest {
	private static final long SEED = 42;
	/** The first block over the flat ground of the tests. */
	private static final int GROUND = 65;
	private static final int MAX_Y = 1023;
	private static final int MIN_Y = -64;
	/**
	 * Chunks each way that the tests of the planted giants look at. The world plants redwoods in one more all round,
	 * whose crowns reach into them.
	 */
	private static final int CHUNKS = 24;
	/** The data pack's: a giant to every region of 3×3 chunks, in its chunk of four. */
	private static RandomSpreadStructurePlacement PLACEMENT;

	private static Holder<Biome> oakWoods;
	private static Holder<Biome> jungle;
	private static Holder<Biome> desert;
	private static GiantTreeStructure oak;
	private static GiantTreeStructure kapok;
	private static StructureSet set;
	/** The data pack's redwood. */
	private static GiantTreeFeature redwood;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		PLACEMENT = new RandomSpreadStructurePlacement(3, 1, RandomSpreadType.LINEAR, 1529730183);
		oakWoods = biome();
		jungle = biome();
		desert = biome();
		oak = new GiantTreeStructure(new Structure.StructureSettings(HolderSet.direct(oakWoods)),
			GiantTreeFeature.Form.OAK, Blocks.OAK_WOOD.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
			UniformInt.of(48, 80), 1);
		kapok = new GiantTreeStructure(new Structure.StructureSettings(HolderSet.direct(jungle)),
			GiantTreeFeature.Form.KAPOK, Blocks.JUNGLE_WOOD.defaultBlockState(),
			Blocks.JUNGLE_LEAVES.defaultBlockState(), UniformInt.of(80, 130), 0.9f);
		set = new StructureSet(List.of(StructureSet.entry(Holder.direct(oak)), StructureSet.entry(Holder.direct(kapok))),
			PLACEMENT);
		redwood = new GiantTreeFeature(GiantTreeFeature.Form.REDWOOD, Blocks.MANGROVE_WOOD.defaultBlockState(),
			Blocks.SPRUCE_LEAVES.defaultBlockState(), UniformInt.of(110, 230));
	}

	/** A biome whose trees are {@code features}. */
	private static Holder<Biome> biome(PlacedFeature... features) {
		BiomeGenerationSettings.PlainBuilder settings = new BiomeGenerationSettings.PlainBuilder();
		for (PlacedFeature feature : features) {
			settings.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, Holder.direct(feature));
		}
		return Holder.direct(new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.8f)
			.downfall(0.8f)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(settings.build())
			.build());
	}

	private static GreatTrees trees(WorldScale scale, GreatTrees.Chunks chunks) {
		GreatTrees trees = GreatTrees.of(List.of(set), placed -> chunks, List.of(), SEED, scale, MAX_Y);
		assertNotNull(trees);
		return trees;
	}

	/** Flat dry ground everywhere, of one biome. */
	private static GreatTrees.Stand flat(GreatTrees trees, Holder<Biome> biome) {
		return trees.stand((x, z) -> GROUND, (x, y, z) -> biome);
	}

	/** The foot of the giant {@code structure} would grow in the region {@code (regionX, regionZ)}, if it draws one. */
	private static GiantTreeStructure.Sapling sapling(GiantTreeStructure structure, int regionX, int regionZ,
													  WorldScale scale) {
		ChunkPos chunk = PLACEMENT.getPotentialStructureChunk(SEED, regionX * PLACEMENT.spacing(),
			regionZ * PLACEMENT.spacing());
		return structure.sapling(SEED, chunk.x(), chunk.z(), scale);
	}

	/**
	 * Whether a chunk lies in a region of even coordinates: there the giants stand far enough apart that none covers
	 * another's foot.
	 */
	private static boolean apart(int chunkX, int chunkZ) {
		return Math.floorDiv(chunkX, PLACEMENT.spacing()) % 2 == 0 && Math.floorDiv(chunkZ, PLACEMENT.spacing()) % 2 == 0;
	}

	@Test
	void giantsStandWhereTheirStructuresGrowThem() {
		GreatTrees.Stand stand = flat(trees(WorldScale.ULTRA, GreatTreesTest::apart), oakWoods);
		TreeCover.Crown crown = new TreeCover.Crown();
		List<GiantTreeStructure.Sapling> saplings = new ArrayList<>();
		for (int regionZ = 0; regionZ < 8; regionZ += 2) {
			for (int regionX = 0; regionX < 8; regionX += 2) {
				saplings.add(sapling(oak, regionX, regionZ, WorldScale.ULTRA));
			}
		}
		for (GiantTreeStructure.Sapling sapling : saplings) {
			assertTrue(stand.crown(sapling.x(), sapling.z(), GROUND - 1, 1, false, crown), "a crown over " + sapling);
			assertTrue(crown.holdsTrunk, "a trunk at " + sapling);
			assertSame(Blocks.OAK_LEAVES, crown.leaves.getBlock());
			assertSame(Blocks.OAK_WOOD, crown.trunk.getBlock());
			assertTrue(crown.high >= 40, "the oak at " + sapling + " stands " + crown.high + " tall");
		}
		// Every trunk rises at the foot of a giant.
		int trunks = 0;
		for (int z = 0; z < 8 * 48; z++) {
			for (int x = 0; x < 8 * 48; x++) {
				if (stand.crown(x, z, GROUND - 1, 1, false, crown) && crown.holdsTrunk) {
					trunks++;
					int bx = x, bz = z;
					assertTrue(saplings.stream().anyMatch(s -> Math.abs(s.x() - bx) <= 12 && Math.abs(s.z() - bz) <= 12),
						"a trunk at " + x + ", " + z + " far from any foot");
				}
			}
		}
		assertTrue(trunks > saplings.size() * 20, trunks + " columns of trunks");
	}

	@Test
	void onlyTheStructureOfTheBiomeAtTheFootGrows() {
		GreatTrees trees = trees(WorldScale.ULTRA, GreatTreesTest::apart);
		TreeCover.Crown crown = new TreeCover.Crown();
		GreatTrees.Stand jungles = flat(trees, jungle);
		GreatTrees.Stand deserts = flat(trees, desert);
		int kapoks = 0;
		for (int regionZ = 0; regionZ < 16; regionZ += 2) {
			for (int regionX = 0; regionX < 16; regionX += 2) {
				GiantTreeStructure.Sapling sapling = sapling(kapok, regionX, regionZ, WorldScale.ULTRA);
				if (sapling != null) {
					kapoks++;
					assertTrue(jungles.crown(sapling.x(), sapling.z(), GROUND - 1, 1, false, crown));
					assertTrue(crown.holdsTrunk, "a trunk at " + sapling);
					assertSame(Blocks.JUNGLE_LEAVES, crown.leaves.getBlock());
					assertTrue(crown.high >= 60, "the kapok at " + sapling + " stands " + crown.high + " tall");
				}
			}
		}
		// Nine in ten regions grow one.
		assertTrue(kapoks > 48 && kapoks < 64, kapoks + " kapoks");
		for (int z = 0; z < 384; z += 3) {
			for (int x = 0; x < 384; x += 3) {
				assertFalse(deserts.crown(x, z, GROUND - 1, 1, false, crown), "a giant in the desert");
			}
		}
	}

	@Test
	void noGiantGrowsUnderWaterNorOnSteepGround() {
		GreatTrees trees = trees(WorldScale.ULTRA, (x, z) -> true);
		TreeCover.Crown crown = new TreeCover.Crown();
		GreatTrees.Stand sea = trees.stand((x, z) -> Integer.MIN_VALUE, (x, y, z) -> oakWoods);
		GreatTrees.Stand slope = trees.stand((x, z) -> GROUND + 2 * x, (x, y, z) -> oakWoods);
		for (int z = 0; z < 384; z += 3) {
			for (int x = 0; x < 384; x += 3) {
				assertFalse(sea.crown(x, z, 40, 1, false, crown), "a giant under the sea");
				assertFalse(slope.crown(x, z, GROUND - 1 + 2 * x, 1, false, crown), "a giant on a slope");
			}
		}
	}

	@Test
	void chunksTheSetDoesNotPlaceGrowNone() {
		ChunkPos only = PLACEMENT.getPotentialStructureChunk(SEED, 0, 0);
		GreatTrees.Stand stand = flat(trees(WorldScale.ULTRA, (x, z) -> x == only.x() && z == only.z()), oakWoods);
		TreeCover.Crown crown = new TreeCover.Crown();
		GiantTreeStructure.Sapling other = sapling(oak, 4, 4, WorldScale.ULTRA);
		assertFalse(stand.crown(other.x(), other.z(), GROUND - 1, 1, false, crown));
		GiantTreeStructure.Sapling one = sapling(oak, 0, 0, WorldScale.ULTRA);
		assertTrue(stand.crown(one.x(), one.z(), GROUND - 1, 1, false, crown));
	}

	/** A tall oak alone, measured at 1:1 and at 1:32. */
	@Test
	void crownsSpreadWideAndShrinkWithTheWorld() {
		int region = 0;
		while (sapling(oak, region, 0, WorldScale.ULTRA).height() < 70) {
			region++;
		}
		int[] ultra = measure(WorldScale.ULTRA, region);
		int[] vanilla = measure(WorldScale.VANILLA, region);
		// Wider than the 3×3 chunks a feature may write in hold.
		assertTrue(ultra[0] > 50, "the oak at 1:1 is " + ultra[0] + " across");
		assertTrue(ultra[0] >= ultra[1] * 0.8, "the oak at 1:1 is " + ultra[0] + " across, " + ultra[1] + " tall");
		assertTrue(vanilla[0] < ultra[0] * 0.6, "the oak at 1:32 is " + vanilla[0] + " across");
		int height = sapling(oak, region, 0, WorldScale.VANILLA).height();
		assertTrue(Math.abs(vanilla[1] - height) <= 4, "the oak at 1:32 stands " + vanilla[1] + ", not " + height);
		height = sapling(oak, region, 0, WorldScale.ULTRA).height();
		assertTrue(Math.abs(ultra[1] - height) <= 6, "the oak at 1:1 stands " + ultra[1] + ", not " + height);
	}

	/**
	 * How far across and how tall the oak of a region stands, alone, and checks its heights over higher ground: the
	 * leaves stay where they are as the ground rises under them.
	 */
	private static int[] measure(WorldScale scale, int region) {
		ChunkPos only = PLACEMENT.getPotentialStructureChunk(SEED, region * PLACEMENT.spacing(), 0);
		GreatTrees.Stand stand = flat(trees(scale, (x, z) -> x == only.x() && z == only.z()), oakWoods);
		GiantTreeStructure.Sapling sapling = sapling(oak, region, 0, scale);
		TreeCover.Crown crown = new TreeCover.Crown();
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, tallest = 0;
		for (int dz = -64; dz <= 64; dz++) {
			for (int dx = -64; dx <= 64; dx++) {
				int x = sapling.x() + dx, z = sapling.z() + dz;
				if (stand.crown(x, z, GROUND - 1, 1, false, crown)) {
					minX = Math.min(minX, dx);
					maxX = Math.max(maxX, dx);
					tallest = Math.max(tallest, crown.high);
					int high = crown.high, low = crown.low;
					assertEquals(high > 10, stand.crown(x, z, GROUND + 9, 1, false, crown));
					if (high > 10) {
						assertEquals(high - 10, crown.high);
						assertEquals(low - 10, crown.low);
					}
				}
			}
		}
		return new int[]{maxX - minX + 1, tallest};
	}

	@Test
	void aHigherCrownIsKept() {
		GreatTrees.Stand stand = flat(trees(WorldScale.ULTRA, (x, z) -> true), oakWoods);
		GiantTreeStructure.Sapling sapling = sapling(oak, 1, 1, WorldScale.ULTRA);
		TreeCover.Crown crown = new TreeCover.Crown();
		crown.high = 500;
		crown.low = 490;
		crown.leaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
		assertTrue(stand.crown(sapling.x(), sapling.z(), GROUND - 1, 1, true, crown));
		assertEquals(500, crown.high);
		assertSame(Blocks.SPRUCE_LEAVES, crown.leaves.getBlock());
	}

	/** The data pack's placement of redwoods, after {@code chance}: somewhere in the middle of the chunk, on dry land. */
	private static List<PlacementModifier> redwoodPlacement(PlacementModifier chance) {
		return List.of(chance, new OffsetPlacement(UniformInt.of(4, 11), ConstantInt.of(0), UniformInt.of(4, 11)),
			SurfaceWaterDepthFilter.forMaxDepth(0), HeightmapPlacement.onHeightmap(Heightmap.Types.OCEAN_FLOOR),
			BiomeFilter.biome());
	}

	/** A redwood the world plants: its foot, and how tall it is drawn. */
	private record Planted(int x, int z, int height) {
		boolean near(Planted other, int distance) {
			return Math.max(Math.abs(x - other.x), Math.abs(z - other.z)) <= distance;
		}

		/** Whether it stands in the chunks the tests look at, rather than in those around them. */
		boolean inside() {
			return x >= 0 && z >= 0 && x < CHUNKS * 16 && z < CHUNKS * 16;
		}
	}

	/** A feature that takes a redwood's first draw where the world places it, and records it. */
	private static final class Recorder implements Feature {
		final List<Planted> planted = new ArrayList<>();

		@Override
		public MapCodec<? extends Feature> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
			planted.add(new Planted(origin.getX(), origin.getZ(), redwood.height(random, WorldScale.ULTRA)));
			return true;
		}
	}

	/** A generator that only tells the biome filter which features a biome holds. */
	private static final class Generator extends ChunkGenerator {
		Generator(Holder<Biome> biome) {
			super(new FixedBiomeSource(biome));
		}

		@Override
		protected MapCodec<? extends ChunkGenerator> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void spawnOriginalMobs(WorldGenRegion region) {
		}

		@Override
		public int getGenDepth() {
			return MAX_Y - MIN_Y + 1;
		}

		@Override
		public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
														   StructureManager structureManager, BiomeManager biomeManager,
														   @Nullable WorldGenRegion carverBiomeRegion,
														   Set<Holder<Biome>> possibleBiomes) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getSeaLevel() {
			return 63;
		}

		@Override
		public int getMinY() {
			return MIN_Y;
		}

		@Override
		public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor,
								 RandomState randomState) {
			return GROUND;
		}

		@Override
		public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos,
									   SamplerContext samplerContext) {
		}
	}

	/**
	 * Decorates chunks -1 to {@value #CHUNKS} each way as vanilla does, with {@code feature} alone, the feature at
	 * {@code index} in its step, on flat dry ground of {@code biome}.
	 */
	private static void decorate(PlacedFeature feature, int index, Holder<Biome> biome) {
		WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
			new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> switch (method.getName()) {
				case "getMinY" -> MIN_Y;
				// The height of the world, or of a heightmap: the first block over the ground.
				case "getHeight" -> args == null ? MAX_Y - MIN_Y + 1 : GROUND;
				case "getBiome" -> biome;
				default -> throw new UnsupportedOperationException(method.toString());
			});
		FeaturePlacer placer = new FeaturePlacer(level, new Generator(biome));
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		for (int chunkZ = -1; chunkZ <= CHUNKS; chunkZ++) {
			for (int chunkX = -1; chunkX <= CHUNKS; chunkX++) {
				BlockPos origin = new BlockPos(chunkX * 16, MIN_Y, chunkZ * 16);
				long decoration = random.setDecorationSeed(SEED, origin.getX(), origin.getZ());
				random.setFeatureSeed(decoration, index, GenerationStep.Decoration.VEGETAL_DECORATION.ordinal());
				placer.placeWithBiomeCheck(feature, random, origin);
			}
		}
	}

	/**
	 * The redwoods the world plants with {@code chance} on flat dry ground, and the far view of them: the stand the
	 * planted giants in it, on the same ground, which follows them as the second feature of the trees' step.
	 */
	private static List<Planted> plantRedwoods(PlacementModifier chance, GreatTrees.Stand[] stand) {
		Recorder recorder = new Recorder();
		PlacedFeature recorded = new PlacedFeature(Holder.direct(recorder), redwoodPlacement(chance));
		PlacedFeature placed = new PlacedFeature(Holder.direct(redwood), redwoodPlacement(chance));
		Holder<Biome> redwoods = biome(recorded, placed);
		decorate(recorded, 1, redwoods);
		GreatTrees trees = GreatTrees.of(List.of(), s -> (x, z) -> false, List.of(recorded, placed), SEED,
			WorldScale.ULTRA, MAX_Y);
		assertNotNull(trees);
		stand[0] = trees.stand((x, z) -> GROUND, (x, y, z) -> redwoods);
		return recorder.planted;
	}

	@Test
	void plantedGiantsStandWhereTheWorldPlantsThem() {
		GreatTrees.Stand[] stand = new GreatTrees.Stand[1];
		List<Planted> planted = plantRedwoods(RarityFilter.onAverageOnceEvery(6), stand);
		// One chunk in six.
		assertTrue(planted.size() > CHUNKS * CHUNKS / 9 && planted.size() < CHUNKS * CHUNKS / 4,
			planted.size() + " redwoods");
		TreeCover.Crown crown = new TreeCover.Crown();
		int alone = 0;
		for (Planted giant : planted) {
			// One that no other's crown may reach grows wherever it is planted.
			if (!giant.inside() || planted.stream().anyMatch(other -> other != giant
				&& other.near(giant, GiantTreeFeature.MAX_ROOM))) {
				continue;
			}
			alone++;
			assertTrue(stand[0].crown(giant.x(), giant.z(), GROUND - 1, 1, false, crown), "a crown over " + giant);
			assertTrue(crown.holdsTrunk, "a trunk at " + giant);
			assertSame(Blocks.MANGROVE_WOOD, crown.trunk.getBlock());
			assertTrue(Math.abs(crown.high - giant.height()) <= 12, "the redwood at " + giant + " stands "
				+ crown.high + " tall");
		}
		// About one in four, with seven chunks or so in reach of each.
		assertTrue(alone > planted.size() / 8, alone + " redwoods alone");
		assertNoPhantoms(stand[0], planted);
	}

	@Test
	void plantedGiantsNeverStandWhereTheWorldPlantsNone() {
		GreatTrees.Stand[] stand = new GreatTrees.Stand[1];
		// The data pack's redwood forest: in two chunks of three, so that they often stand under one another's crowns.
		List<Planted> planted = plantRedwoods(CountPlacement.of(new WeightedListInt(WeightedList.<IntProvider>builder()
			.add(ConstantInt.of(0), 1).add(ConstantInt.of(1), 2).build())), stand);
		assertTrue(planted.size() > CHUNKS * CHUNKS / 2, planted.size() + " redwoods");
		int trunks = assertNoPhantoms(stand[0], planted);
		// Many grow, though not those under the crown of one planted before them.
		long inside = planted.stream().filter(Planted::inside).count();
		assertTrue(trunks > inside / 3, trunks + " of " + inside + " redwoods grow");
	}

	/** How far the roots of the tallest redwoods spread from their foot, at most. */
	private static int footReach() {
		int[] reach = new int[1];
		for (long seed = 0; seed < 16; seed++) {
			redwood.grow(redwood.maxHeight(WorldScale.ULTRA), GiantTreeFeature.MAX_ROOM, seed, (x, y, z, wood) -> {
				if (wood && y == 0) {
					reach[0] = Math.max(reach[0], Math.max(Math.abs(x), Math.abs(z)));
				}
			});
		}
		return reach[0];
	}

	/** Checks that every trunk the stand shows rises at the foot of a redwood the world plants, and counts them. */
	private static int assertNoPhantoms(GreatTrees.Stand stand, List<Planted> planted) {
		int reach = footReach() + 1;
		assertTrue(reach < GiantTreeFeature.MAX_ROOM, "roots that reach " + reach);
		TreeCover.Crown crown = new TreeCover.Crown();
		int grown = 0;
		for (Planted giant : planted) {
			if (giant.inside() && stand.crown(giant.x(), giant.z(), GROUND - 1, 1, false, crown) && crown.holdsTrunk) {
				grown++;
			}
		}
		for (int z = 0; z < CHUNKS * 16; z++) {
			for (int x = 0; x < CHUNKS * 16; x++) {
				if (stand.crown(x, z, GROUND - 1, 1, false, crown) && crown.holdsTrunk) {
					Planted at = new Planted(x, z, 0);
					assertTrue(planted.stream().anyMatch(giant -> giant.near(at, reach)),
						"a trunk at " + x + ", " + z + " where the world plants no redwood");
				}
			}
		}
		return grown;
	}

	@Test
	void plantedGiantsAreLeftOutOfTheTreeCoverAndOfOtherBiomes() {
		PlacedFeature placed = new PlacedFeature(Holder.direct(redwood),
			redwoodPlacement(RarityFilter.onAverageOnceEvery(2)));
		assertTrue(GreatTrees.plants(placed));
		assertFalse(GreatTrees.plants(new PlacedFeature(Holder.direct(redwood),
			List.of(RarityFilter.onAverageOnceEvery(2)))), "planted wherever a biome of the chunks around holds it");
		TreeCover.Crown crown = new TreeCover.Crown();
		TreeCover cover = TreeCover.of(biome(placed), WorldScale.ULTRA);
		GreatTrees trees = GreatTrees.of(List.of(), s -> (x, z) -> false, List.of(placed), SEED, WorldScale.ULTRA,
			MAX_Y);
		assertNotNull(trees);
		GreatTrees.Stand desert = trees.stand((x, z) -> GROUND, (x, y, z) -> GreatTreesTest.desert);
		for (int z = 0; z < 256; z += 3) {
			for (int x = 0; x < 256; x += 3) {
				assertFalse(cover.crown(SEED, x, z, 1, crown), "a redwood scattered at " + x + ", " + z);
				assertFalse(desert.crown(x, z, GROUND - 1, 1, false, crown), "a redwood in the desert");
			}
		}
	}
}
