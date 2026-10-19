package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

		for (BiomeClimate c : new BiomeClimate[] {climate(-8, 300), climate(15, 400), climate(28, 200),
			climate(2, 1500)}) {
			assertEquals(Set.of(HollowBiomes.DRIPSTONE_CAVES), shares(Hollows.Kind.GRAND_HOLLOW, c).keySet());
		}
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
	void theSameCavernAlwaysPicksTheSameBiome() {
		BiomeClimate c = climate(12, 1400);
		for (int i = 0; i < 200; i++) {
			long id = GeoNoise.hash(9, i, 0, 0);
			assertEquals(HollowBiomes.pick(Hollows.Kind.ANCIENT_GRAND_HOLLOW, id, c),
				HollowBiomes.pick(Hollows.Kind.ANCIENT_GRAND_HOLLOW, id, climate(12, 1400)));
		}
		assertTrue(shares(Hollows.Kind.GRAND_HOLLOW, c).size() > 1);
	}
}
