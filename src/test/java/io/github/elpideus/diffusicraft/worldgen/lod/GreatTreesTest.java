package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeStructure;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
	/** The data pack's: a giant to every region of 3×3 chunks, in its chunk of four. */
	private static RandomSpreadStructurePlacement PLACEMENT;

	private static Holder<Biome> oakWoods;
	private static Holder<Biome> jungle;
	private static Holder<Biome> desert;
	private static GiantTreeStructure oak;
	private static GiantTreeStructure kapok;
	private static StructureSet set;

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
	}

	private static Holder<Biome> biome() {
		return Holder.direct(new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.8f)
			.downfall(0.8f)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build());
	}

	private static GreatTrees trees(WorldScale scale, GreatTrees.Chunks chunks) {
		GreatTrees trees = GreatTrees.of(List.of(set), placed -> chunks, SEED, scale, MAX_Y);
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
}
