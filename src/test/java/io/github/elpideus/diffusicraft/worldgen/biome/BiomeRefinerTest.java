package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real places, roughly, and what the refiner makes of vanilla's picks there. */
class BiomeRefinerTest {
	private static BiomeClimate climate(float temperature, float seasonality, float precipitation, float cv,
										 float elevation, float relief) {
		BiomeClimate c = new BiomeClimate();
		c.temperature = temperature;
		c.seasonality = seasonality;
		c.precipitation = precipitation;
		c.precipitationSeasonality = cv;
		c.elevation = elevation;
		c.relief = relief;
		return c;
	}

	/** {@link #climate} on a 30° slope facing the south ({@code facing} 1) or the north (-1), or on flat ground (0). */
	private static BiomeClimate onSlope(int facing, float temperature, float seasonality, float precipitation,
										float cv, float elevation, float relief) {
		TerrainField.Column column = new TerrainField.Column();
		column.temperature = temperature;
		column.temperatureSeasonality = seasonality * 100f;
		column.precipitation = precipitation;
		column.precipitationSeasonality = cv;
		column.elevation = elevation;
		column.meanElevation = elevation;
		column.relief = relief;
		float rise = (float) Math.tan(Math.toRadians(30));
		ClimateMapping.expose(column, ClimateMapping.exposure(0f, -facing * rise));
		return new BiomeClimate().set(column);
	}

	@Test
	void modBiomesAndFixedOnesAreLeftAlone() {
		BiomeClimate tundra = climate(-8f, 10f, 250f, 40f, 50f, 20f);
		assertNull(BiomeRefiner.refine("biomesoplenty:tundra", tundra));
		assertNull(BiomeRefiner.refine("minecraft:ocean", tundra));
		assertNull(BiomeRefiner.refine("minecraft:river", tundra));
		assertNull(BiomeRefiner.refine("minecraft:lush_caves", tundra));
	}

	@Test
	void arcticLowlandIsTundra() {
		// The Canadian Arctic coast: summers of 6 °C.
		BiomeClimate c = climate(-8f, 10f, 250f, 40f, 50f, 20f);
		assertEquals(BiomeRefiner.TUNDRA, BiomeRefiner.refine("minecraft:snowy_plains", c));
		assertEquals(BiomeRefiner.TUNDRA, BiomeRefiner.refine("minecraft:taiga", c));
	}

	@Test
	void permanentIceKeepsVanillaSnow() {
		// Inland Greenland, and Svalbard, whose warmest month stays below 3 °C.
		assertNull(BiomeRefiner.refine("minecraft:snowy_plains", climate(-25f, 12f, 200f, 30f, 2500f, 40f)));
		assertNull(BiomeRefiner.refine("minecraft:snowy_slopes", climate(-6f, 6f, 400f, 30f, 300f, 120f)));
	}

	@Test
	void alpsAboveTheTreeLineAreAlpineTundra() {
		// 2500 m in the Alps: July around 7 °C.
		BiomeClimate c = climate(-1f, 6f, 1500f, 25f, 2500f, 300f);
		assertEquals(BiomeRefiner.ALPINE_TUNDRA, BiomeRefiner.refine("minecraft:meadow", c));
		assertEquals(BiomeRefiner.ALPINE_TUNDRA, BiomeRefiner.refine("minecraft:jagged_peaks", c));
		// Below the tree line the meadow stays.
		assertNull(BiomeRefiner.refine("minecraft:meadow", climate(4f, 6.5f, 1500f, 25f, 1800f, 300f)));
	}

	@Test
	void tropicalMountainsideIsCloudForest() {
		// Monteverde, Costa Rica.
		BiomeClimate c = climate(18f, 1f, 2500f, 60f, 1500f, 200f);
		assertEquals(BiomeRefiner.CLOUD_FOREST, BiomeRefiner.refine("minecraft:jungle", c));
		assertNull(BiomeRefiner.refine("minecraft:stony_peaks", c), "summits stay bare");
	}

	@Test
	void oceanicRainyCoastIsTemperateRainforest() {
		// The Olympic Peninsula.
		BiomeClimate c = climate(10f, 4.5f, 3000f, 60f, 100f, 60f);
		assertEquals(BiomeRefiner.TEMPERATE_RAINFOREST, BiomeRefiner.refine("minecraft:old_growth_spruce_taiga", c));
		assertEquals(BiomeRefiner.TEMPERATE_RAINFOREST, BiomeRefiner.refine("minecraft:forest", c));
	}

	@Test
	void mediterraneanCoastIsMaquis() {
		// Provence: dry summers, mild winters.
		BiomeClimate c = climate(15f, 6f, 550f, 50f, 100f, 60f);
		assertEquals(BiomeRefiner.MAQUIS, BiomeRefiner.refine("minecraft:plains", c));
		// The same temperatures with rain all year round are not Mediterranean.
		assertNull(BiomeRefiner.refine("minecraft:plains", climate(15f, 6f, 900f, 15f, 100f, 60f)));
	}

	@Test
	void coolDrylandsAreColdDesertOrSteppe() {
		// The Gobi, and the Kazakh steppe.
		assertEquals(BiomeRefiner.COLD_DESERT, BiomeRefiner.refine("minecraft:desert", climate(4f, 14f, 100f, 80f, 1200f, 40f)));
		assertEquals(BiomeRefiner.STEPPE, BiomeRefiner.refine("minecraft:plains", climate(6f, 13f, 220f, 50f, 300f, 20f)));
		// A hot desert stays a desert.
		assertNull(BiomeRefiner.refine("minecraft:desert", climate(25f, 7f, 50f, 100f, 300f, 40f)));
	}

	@Test
	void coolWetUplandsAreMoorland() {
		// Dartmoor at 450 m, and the Pennines.
		BiomeClimate dartmoor = climate(8f, 4f, 1800f, 30f, 450f, 40f);
		assertEquals(BiomeRefiner.MOORLAND, BiomeRefiner.refine("minecraft:dark_forest", dartmoor));
		assertEquals(BiomeRefiner.MOORLAND, BiomeRefiner.refine("minecraft:windswept_hills", dartmoor));
		assertEquals(BiomeRefiner.MOORLAND, BiomeRefiner.refine("minecraft:plains", climate(7.5f, 4.2f, 1200f, 25f, 500f, 50f)));
		// The rugged, rainy Olympics at the same height stay forest.
		assertEquals(BiomeRefiner.TEMPERATE_RAINFOREST, BiomeRefiner.refine("minecraft:old_growth_spruce_taiga",
			climate(8f, 4f, 3000f, 60f, 450f, 200f)));
		// Uplands with warm summers stay forest.
		assertNull(BiomeRefiner.refine("minecraft:forest", climate(9f, 6.5f, 1000f, 20f, 500f, 50f)));
	}

	@Test
	void oceanicLowlandHasPatchesOfHeath() {
		// The Lüneburg Heath.
		BiomeClimate c = climate(9f, 5.8f, 750f, 15f, 80f, 15f);
		c.variant = -0.5f;
		assertEquals(BiomeRefiner.HEATH, BiomeRefiner.refine("minecraft:birch_forest", c));
		assertEquals(BiomeRefiner.HEATH, BiomeRefiner.refine("minecraft:plains", c));
		c.variant = 0.2f;
		assertNull(BiomeRefiner.refine("minecraft:birch_forest", c), "woods between the heaths");
	}

	@Test
	void flattestDesertFloorsAreSaltFlats() {
		// Bonneville, and the Salar de Uyuni at 3650 m.
		assertEquals(BiomeRefiner.SALT_FLAT, BiomeRefiner.refine("minecraft:desert", climate(11f, 9f, 150f, 30f, 1290f, 1f)));
		assertEquals(BiomeRefiner.SALT_FLAT, BiomeRefiner.refine("minecraft:plains", climate(5f, 3f, 100f, 90f, 3650f, 0.5f)));
		// Dunes and rocky desert stay desert.
		assertNull(BiomeRefiner.refine("minecraft:desert", climate(25f, 7f, 50f, 100f, 300f, 25f)));
	}

	@Test
	void theTreeLineClimbsHigherOnTheSunnySide() {
		// 1900 m in the Alps, just under the tree line: July around 10.5 °C on flat ground.
		assertNull(BiomeRefiner.refine("minecraft:taiga", onSlope(0, 2f, 6f, 1200f, 25f, 1900f, 200f)));
		assertNull(BiomeRefiner.refine("minecraft:taiga", onSlope(1, 2f, 6f, 1200f, 25f, 1900f, 200f)));
		assertEquals(BiomeRefiner.ALPINE_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", onSlope(-1, 2f, 6f, 1200f, 25f, 1900f, 200f)));
	}

	@Test
	void inADryClimateTheWoodsKeepToTheShadedSlopes() {
		// The foothills of the Rockies: pines on the slopes facing north, grass on those facing south.
		assertNull(BiomeRefiner.refine("minecraft:forest", onSlope(0, 10f, 9f, 375f, 30f, 1800f, 200f)));
		assertNull(BiomeRefiner.refine("minecraft:forest", onSlope(-1, 10f, 9f, 375f, 30f, 1800f, 200f)));
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:forest", onSlope(1, 10f, 9f, 375f, 30f, 1800f, 200f)));
	}

	@Test
	void wetlandsFollowTheClimate() {
		// The Sundarbans, and Louisiana's bayous.
		assertEquals(BiomeRefiner.MANGROVE_SWAMP, BiomeRefiner.refine("minecraft:swamp", climate(26f, 3f, 1800f, 80f, 3f, 5f)));
		assertEquals(BiomeRefiner.SWAMP, BiomeRefiner.refine("minecraft:mangrove_swamp", climate(20f, 6f, 1600f, 30f, 3f, 5f)));
		assertNull(BiomeRefiner.refine("minecraft:swamp", climate(20f, 6f, 1600f, 30f, 3f, 5f)));
		// The Hudson Bay Lowlands, whose summers stay cool, and the edge of the Arctic, beyond the tree line.
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:swamp", climate(-3f, 13f, 650f, 35f, 50f, 3f)));
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:mangrove_swamp", climate(-3f, 13f, 650f, 35f, 3f, 3f)));
		assertEquals(BiomeRefiner.TUNDRA, BiomeRefiner.refine("minecraft:swamp", climate(-4.5f, 9.5f, 400f, 40f, 20f, 3f)));
	}

	@Test
	void flatLandWithCoolWetSummersIsBog() {
		// Finland's lowland mires, the West Siberian Plain and the Bog of Allen.
		BiomeClimate finland = climate(3f, 8.5f, 600f, 25f, 100f, 3f);
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:taiga", finland));
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:plains", finland));
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:taiga", climate(-2f, 13f, 500f, 40f, 120f, 2f)));
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:plains", climate(9.5f, 3.7f, 900f, 15f, 70f, 4f)));
		// Rolling land in the same climate drains, and stays forest; so does flat land with less rain.
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(3f, 8.5f, 600f, 25f, 100f, 30f)));
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(3f, 8.5f, 300f, 25f, 100f, 3f)));
		// Flat land with hot summers, as on the Illinois prairie, is no bog.
		assertNull(BiomeRefiner.refine("minecraft:plains", climate(10f, 10f, 950f, 30f, 200f, 3f)));
		// Hills and slopes are not land the bog takes.
		assertNull(BiomeRefiner.refine("minecraft:grove", finland));
	}
}
