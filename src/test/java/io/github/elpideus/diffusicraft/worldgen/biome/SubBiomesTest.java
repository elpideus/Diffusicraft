package io.github.elpideus.diffusicraft.worldgen.biome;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubBiomesTest {
	private static final long SEED = 42;

	/** Level ground of a cool, wet temperate climate. */
	private static BiomeClimate temperate() {
		BiomeClimate c = new BiomeClimate();
		c.temperature = 8f;
		c.seasonality = 6f;
		c.precipitation = 800f;
		c.precipitationSeasonality = 20f;
		c.elevation = 200f;
		c.meanElevation = 200f;
		c.relief = 20f;
		return c;
	}

	/** The boreal forest: summers of 17 °C, winters of −17 °C. */
	private static BiomeClimate boreal() {
		BiomeClimate c = temperate();
		c.temperature = 0f;
		c.seasonality = 12f;
		c.precipitation = 600f;
		return c;
	}

	/** {@code c} in the middle of the patch that {@code sibling} takes in {@code biome}'s family. */
	private static BiomeClimate inPatchOf(String biome, String sibling, BiomeClimate c) {
		float from = 0f;
		for (SubBiomes.Sibling s : SubBiomes.FAMILIES.get(biome)) {
			if (s.biome().equals(sibling)) {
				c.patch = from + s.share() / 2f;
				c.patchDepth = 0.5f;
				return c;
			}
			from += s.share();
		}
		throw new AssertionError(sibling + " is no sibling of " + biome);
	}

	@Test
	void everySiblingIsALandBiomeTheSourceKnows() {
		for (Map.Entry<String, List<SubBiomes.Sibling>> family : SubBiomes.FAMILIES.entrySet()) {
			assertTrue(isLand(family.getKey()), family.getKey());
			float share = 0f;
			for (SubBiomes.Sibling sibling : family.getValue()) {
				assertTrue(isLand(sibling.biome()), sibling.biome() + " in " + family.getKey());
				assertTrue(!sibling.biome().equals(family.getKey()), family.getKey() + " is its own sibling");
				share += sibling.share();
			}
			assertTrue(share > 0f && share <= 0.6f, family.getKey() + " gives its siblings " + share);
		}
		assertTrue(SubBiomes.targets().contains(BiomeRefiner.BOG));
	}

	private static boolean isLand(String biome) {
		BiomeRefiner.Kind kind = BiomeRefiner.kindOf(biome);
		if (kind == null) {
			return BiomeRefiner.TARGETS.contains(biome) && !biome.equals(BiomeRefiner.FJORD)
				|| List.of(BiomeRefiner.REDWOOD_FOREST, BiomeRefiner.ANCIENT_WOODLAND, BiomeRefiner.OLD_GROWTH_JUNGLE)
				.contains(biome);
		}
		return kind != BiomeRefiner.Kind.FIXED && kind != BiomeRefiner.Kind.SEA && kind != BiomeRefiner.Kind.PEAK;
	}

	@Test
	void patchesAreAFewHundredBlocksAcross() {
		BiomeClimate c = new BiomeClimate();
		int inside = 0, columns = 0, runs = 0;
		for (int z = -4096; z < 4096; z += 16) {
			boolean was = false;
			for (int x = -4096; x < 4096; x += 4) {
				SubBiomes.locate(SEED, x, z, c);
				boolean in = c.patchDepth > 0f;
				inside += in ? 1 : 0;
				runs += in && !was ? 1 : 0;
				was = in;
				columns++;
			}
		}
		float share = inside / (float) columns;
		assertTrue(share > 0.35f && share < 0.65f, "patches cover " + share);
		// The mean length of a line through a patch.
		float across = inside * 4f / runs;
		assertTrue(across > 100f && across < 320f, "a patch is " + across + " blocks across");
	}

	@Test
	void aFifthOfAForestIsOtherWoodsAndGlades() {
		BiomeClimate c = temperate();
		Map<String, Integer> found = new HashMap<>();
		int columns = 0, varied = 0;
		for (int z = -4096; z < 4096; z += 32) {
			for (int x = -4096; x < 4096; x += 32) {
				SubBiomes.locate(SEED, x, z, c);
				String sibling = SubBiomes.vary("minecraft:forest", c);
				columns++;
				if (sibling != null) {
					varied++;
					found.merge(sibling, 1, Integer::sum);
				}
			}
		}
		float share = varied / (float) columns;
		assertTrue(share > 0.15f && share < 0.4f, "siblings take " + share + " of the forest");
		assertEquals(7, found.size(), "siblings found: " + found);
		assertTrue(found.get("minecraft:birch_forest") > found.get("minecraft:old_growth_birch_forest"));
	}

	@Test
	void bogsKeepToFlatHollows() {
		BiomeClimate flat = inPatchOf("minecraft:taiga", BiomeRefiner.BOG, boreal());
		flat.relief = 5f;
		flat.elevation = flat.meanElevation - 3f;
		assertEquals(BiomeRefiner.BOG, SubBiomes.vary("minecraft:taiga", flat));

		BiomeClimate rise = inPatchOf("minecraft:taiga", BiomeRefiner.BOG, boreal());
		rise.relief = 5f;
		rise.elevation = rise.meanElevation + 15f;
		assertNull(SubBiomes.vary("minecraft:taiga", rise), "a rise drains");

		BiomeClimate rugged = inPatchOf("minecraft:taiga", BiomeRefiner.BOG, boreal());
		rugged.relief = 60f;
		rugged.elevation = rugged.meanElevation - 20f;
		assertNull(SubBiomes.vary("minecraft:taiga", rugged), "rugged ground drains");

		// Peat needs cool summers: the summers of a warm temperate forest are too warm and dry.
		BiomeClimate warm = inPatchOf("minecraft:taiga", BiomeRefiner.BOG, temperate());
		warm.temperature = 14f;
		warm.relief = 5f;
		assertNull(SubBiomes.vary("minecraft:taiga", warm));
	}

	@Test
	void pinesKeepToTheRisesAndOldSpruceToTheValleys() {
		BiomeClimate rise = inPatchOf("minecraft:taiga", "minecraft:old_growth_pine_taiga", boreal());
		rise.elevation = rise.meanElevation + 20f;
		assertEquals("minecraft:old_growth_pine_taiga", SubBiomes.vary("minecraft:taiga", rise));
		rise.elevation = rise.meanElevation - 20f;
		assertNull(SubBiomes.vary("minecraft:taiga", rise));

		BiomeClimate valley = inPatchOf("minecraft:taiga", "minecraft:old_growth_spruce_taiga", boreal());
		valley.elevation = valley.meanElevation - 20f;
		assertEquals("minecraft:old_growth_spruce_taiga", SubBiomes.vary("minecraft:taiga", valley));
		valley.elevation = valley.meanElevation + 20f;
		assertNull(SubBiomes.vary("minecraft:taiga", valley));
	}

	@Test
	void giantsKeepToLevelLandAndRedwoodsToMildWetClimates() {
		BiomeClimate level = inPatchOf("minecraft:jungle", BiomeRefiner.OLD_GROWTH_JUNGLE, temperate());
		level.relief = 20f;
		assertEquals(BiomeRefiner.OLD_GROWTH_JUNGLE, SubBiomes.vary("minecraft:jungle", level));
		level.relief = 80f;
		assertNull(SubBiomes.vary("minecraft:jungle", level), "too rugged for giants");

		BiomeClimate coast = inPatchOf("minecraft:taiga", BiomeRefiner.REDWOOD_FOREST, boreal());
		coast.temperature = 6f;
		coast.seasonality = 5f;
		coast.precipitation = 1200f;
		assertEquals(BiomeRefiner.REDWOOD_FOREST, SubBiomes.vary("minecraft:taiga", coast));
		BiomeClimate inland = inPatchOf("minecraft:taiga", BiomeRefiner.REDWOOD_FOREST, boreal());
		inland.seasonality = 18f;
		inland.precipitation = 1200f;
		assertNull(SubBiomes.vary("minecraft:taiga", inland), "winters of -25 °C");
	}

	@Test
	void noTreesGrowInPatchesBeyondTheTreeLine() {
		BiomeClimate ice = inPatchOf("minecraft:snowy_plains", "minecraft:snowy_taiga", boreal());
		ice.temperature = -20f;
		ice.seasonality = 10f;
		ice.elevation = ice.meanElevation - 10f;
		assertNull(SubBiomes.vary("minecraft:snowy_plains", ice), "summers of -6 °C");

		BiomeClimate boreal = inPatchOf("minecraft:snowy_plains", "minecraft:snowy_taiga", boreal());
		boreal.elevation = boreal.meanElevation - 10f;
		assertEquals("minecraft:snowy_taiga", SubBiomes.vary("minecraft:snowy_plains", boreal));
	}

	@Test
	void otherBiomesAndColumnsOutsideThePatchesStay() {
		BiomeClimate c = inPatchOf("minecraft:forest", "minecraft:birch_forest", temperate());
		assertNotNull(SubBiomes.vary("minecraft:forest", c));
		for (String biome : List.of("minecraft:river", "minecraft:beach", "minecraft:ocean", "minecraft:frozen_peaks",
			"biomesoplenty:maple_woods", BiomeRefiner.FJORD, BiomeRefiner.SALT_FLAT)) {
			assertNull(SubBiomes.vary(biome, c), biome);
		}
		c.patchDepth = -0.1f;
		assertNull(SubBiomes.vary("minecraft:forest", c));
		c.patchDepth = 0.5f;
		c.patch = 0.99f;
		assertNull(SubBiomes.vary("minecraft:forest", c), "a draw past every share keeps the forest");
	}

	@Test
	void theSameSeedGivesTheSamePatchesAndAnotherOthers() {
		BiomeClimate a = new BiomeClimate(), b = new BiomeClimate(), other = new BiomeClimate();
		int differ = 0;
		for (int k = 0; k < 200; k++) {
			int x = k * 97 - 9000, z = 5000 - k * 131;
			SubBiomes.locate(SEED, x, z, a);
			SubBiomes.locate(SEED, x, z, b);
			SubBiomes.locate(SEED + 1, x, z, other);
			assertEquals(a.patch, b.patch);
			assertEquals(a.patchDepth, b.patchDepth);
			differ += a.patch != other.patch ? 1 : 0;
		}
		assertTrue(differ > 150, "another seed moves the patches");
	}

	@Test
	void settingTheClimateLeavesNoPatchBehind() {
		BiomeClimate c = inPatchOf("minecraft:forest", "minecraft:birch_forest", temperate());
		c.set(new io.github.elpideus.diffusicraft.worldgen.TerrainField.Column());
		assertNull(SubBiomes.vary("minecraft:forest", c));
	}
}
