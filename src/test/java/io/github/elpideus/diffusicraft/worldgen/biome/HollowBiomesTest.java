package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowBiomesTest {
	private static final int CAVERNS = 4000;

	private static BiomeClimate climate(float temperature, float precipitation) {
		BiomeClimate c = new BiomeClimate();
		c.temperature = temperature;
		c.precipitation = precipitation;
		c.seasonality = 5f;
		return c;
	}

	/** The share of each biome over many caverns of a kind under one climate. */
	private static Map<String, Double> shares(Hollows.Kind kind, BiomeClimate c) {
		Map<String, Double> shares = new HashMap<>();
		for (int i = 0; i < CAVERNS; i++) {
			String biome = HollowBiomes.pick(kind, GeoNoise.hash(7, i, 0, 0), c);
			shares.merge(String.valueOf(biome), 1.0 / CAVERNS, Double::sum);
		}
		return shares;
	}

	@Test
	void theDeepGrandHollowsKeepVanillasBiomes() {
		for (int i = 0; i < 100; i++) {
			assertNull(HollowBiomes.pick(Hollows.Kind.DEEP_GRAND_HOLLOW, GeoNoise.hash(7, i, 0, 0), climate(25, 3000)));
		}
	}

	@Test
	void grandHollowsFollowTheClimate() {
		Map<String, Double> tropics = shares(Hollows.Kind.GRAND_HOLLOW, climate(26, 2500));
		assertEquals(Set.of(HollowBiomes.LUSH_CAVES, HollowBiomes.DRIPSTONE_CAVES), tropics.keySet());
		assertEquals(0.7, tropics.get(HollowBiomes.LUSH_CAVES), 0.03);

		Map<String, Double> mild = shares(Hollows.Kind.GRAND_HOLLOW, climate(12, 1400));
		assertEquals(Set.of(HollowBiomes.GLOWWORM_GROTTO, HollowBiomes.DRIPSTONE_CAVES), mild.keySet());
		assertEquals(0.7, mild.get(HollowBiomes.GLOWWORM_GROTTO), 0.03);

		Map<String, Double> frozen = shares(Hollows.Kind.GRAND_HOLLOW, climate(-8, 300));
		assertEquals(Set.of(HollowBiomes.ICE_CAVES, HollowBiomes.DRIPSTONE_CAVES), frozen.keySet());
		assertEquals(0.7, frozen.get(HollowBiomes.ICE_CAVES), 0.03);

		for (BiomeClimate c : new BiomeClimate[] {climate(15, 400), climate(28, 200), climate(2, 1500)}) {
			assertEquals(Set.of(HollowBiomes.DRIPSTONE_CAVES), shares(Hollows.Kind.GRAND_HOLLOW, c).keySet());
		}
	}

	@Test
	void saltBedsHaveSaltCaverns() {
		BiomeClimate desert = climate(24, 150);
		desert.saline = true;
		Map<String, Double> grand = shares(Hollows.Kind.GRAND_HOLLOW, desert);
		assertEquals(Set.of(HollowBiomes.SALT_CAVERNS, HollowBiomes.DRIPSTONE_CAVES), grand.keySet());
		assertEquals(0.7, grand.get(HollowBiomes.SALT_CAVERNS), 0.03);
		// The selenite keeps the Ancient ones; the salt takes most of the rest.
		Map<String, Double> ancient = shares(Hollows.Kind.ANCIENT_GRAND_HOLLOW, desert);
		assertEquals(0.75, ancient.get(HollowBiomes.SELENITE_CAVERNS), 0.03);
		assertEquals(0.25 * 0.7, ancient.get(HollowBiomes.SALT_CAVERNS), 0.03);
	}

	@Test
	void ancientGrandHollowsGrowCrystalsUnderDryLandAndMushroomsUnderWetLand() {
		Map<String, Double> dry = shares(Hollows.Kind.ANCIENT_GRAND_HOLLOW, climate(24, 250));
		assertEquals(0.75, dry.get(HollowBiomes.SELENITE_CAVERNS), 0.03);
		assertEquals(0.25, dry.get(HollowBiomes.DRIPSTONE_CAVES), 0.03);

		Map<String, Double> wet = shares(Hollows.Kind.ANCIENT_GRAND_HOLLOW, climate(12, 1400));
		assertEquals(0.55, wet.get(HollowBiomes.MUSHROOM_FOREST), 0.03);
		assertEquals(0.2, wet.get(HollowBiomes.SELENITE_CAVERNS), 0.03);
		assertEquals(0.25 * 0.7, wet.get(HollowBiomes.GLOWWORM_GROTTO), 0.03);
	}

	@Test
	void sulfurousGroundHasSulfurCavesWhateverTheClimate() {
		for (BiomeClimate c : new BiomeClimate[] {climate(26, 2500), climate(12, 1400), climate(24, 250),
			climate(-8, 300)}) {
			BiomeClimate plain = climate(c.temperature, c.precipitation);
			c.sulfurous = true;
			Map<String, Double> grand = shares(Hollows.Kind.GRAND_HOLLOW, c);
			assertEquals(0.7, grand.get(HollowBiomes.SULFUR_CAVES), 0.03);
			Map<String, Double> ancient = shares(Hollows.Kind.ANCIENT_GRAND_HOLLOW, c);
			assertEquals(0.5, ancient.get(HollowBiomes.SULFUR_CAVES), 0.03);
			// The rest share out as elsewhere.
			for (Map.Entry<String, Double> e : shares(Hollows.Kind.GRAND_HOLLOW, plain).entrySet()) {
				assertEquals(0.3 * e.getValue(), grand.getOrDefault(e.getKey(), 0.0), 0.03, e.getKey());
			}
			assertFalse(shares(Hollows.Kind.GRAND_HOLLOW, plain).containsKey(HollowBiomes.SULFUR_CAVES));
			assertNull(HollowBiomes.pick(Hollows.Kind.DEEP_GRAND_HOLLOW, 1, c));
		}
	}

	@Test
	void theSameCavernAlwaysPicksTheSameBiome() {
		BiomeClimate c = climate(12, 1400);
		for (int i = 0; i < 200; i++) {
			long id = GeoNoise.hash(9, i, 0, 0);
			assertEquals(HollowBiomes.pick(Hollows.Kind.ANCIENT_GRAND_HOLLOW, id, c),
				HollowBiomes.pick(Hollows.Kind.ANCIENT_GRAND_HOLLOW, id, climate(12, 1400)));
		}
		assertTrue(shares(Hollows.Kind.GRAND_HOLLOW, c).size() > 1);
	}

	@Test
	void onlyWetLandFillsTheBasins() {
		BiomeClimate wet = climate(12, 1400);
		assertTrue(HollowBiomes.holdsLake(HollowBiomes.DRIPSTONE_CAVES, wet));
		assertTrue(HollowBiomes.holdsLake(HollowBiomes.MUSHROOM_FOREST, wet));
		assertTrue(HollowBiomes.holdsLake(HollowBiomes.LUSH_CAVES, climate(26, 2500)));
		// Frozen solid in the ice caves, however dry the land.
		assertTrue(HollowBiomes.holdsLake(HollowBiomes.ICE_CAVES, climate(-12, 150)));
		assertTrue(HollowBiomes.frozen(HollowBiomes.ICE_CAVES));
		assertFalse(HollowBiomes.frozen(HollowBiomes.DRIPSTONE_CAVES));
		// Drained, dry, frozen, or left to vanilla.
		assertFalse(HollowBiomes.holdsLake(HollowBiomes.SELENITE_CAVERNS, wet));
		assertFalse(HollowBiomes.holdsLake(HollowBiomes.SALT_CAVERNS, climate(24, 150)));
		assertFalse(HollowBiomes.holdsLake(HollowBiomes.DRIPSTONE_CAVES, climate(24, 250)));
		assertFalse(HollowBiomes.holdsLake(HollowBiomes.DRIPSTONE_CAVES, climate(-4, 900)));
		assertFalse(HollowBiomes.holdsLake(null, wet));
	}
}
