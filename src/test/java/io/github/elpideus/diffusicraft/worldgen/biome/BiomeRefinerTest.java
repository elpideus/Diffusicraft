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
	void tropicalMountainsAboveTheTreeLineAreParamoOrPuna() {
		// 4000 m in Ecuador: 6 °C in every month and 1400 mm of rain.
		BiomeClimate paramo = climate(6f, 0.5f, 1400f, 40f, 4000f, 300f);
		assertEquals(BiomeRefiner.PARAMO, BiomeRefiner.refine("minecraft:meadow", paramo));
		assertEquals(BiomeRefiner.PARAMO, BiomeRefiner.refine("minecraft:jagged_peaks", paramo));
		assertEquals(BiomeRefiner.PARAMO, BiomeRefiner.refine("minecraft:swamp", paramo));
		// The Altiplano by Lake Titicaca, 3800 m: 700 mm, nearly all in the summer. Its summers pass 10 °C.
		BiomeClimate altiplano = climate(8.5f, 1.8f, 700f, 90f, 3800f, 40f);
		assertEquals(BiomeRefiner.PUNA, BiomeRefiner.refine("minecraft:plains", altiplano));
		assertEquals(BiomeRefiner.PUNA, BiomeRefiner.refine("minecraft:savanna", altiplano));
		// Mauna Kea's summit, above the clouds.
		assertEquals(BiomeRefiner.PUNA, BiomeRefiner.refine("minecraft:desert", climate(4f, 1.5f, 350f, 50f, 3700f, 200f)));
		// The cloud forest below the páramo, and the glaciers above it, stay.
		assertEquals(BiomeRefiner.CLOUD_FOREST, BiomeRefiner.refine("minecraft:jungle", climate(12f, 0.5f, 2000f, 40f, 3000f, 300f)));
		assertNull(BiomeRefiner.refine("minecraft:frozen_peaks", climate(-4f, 0.5f, 900f, 40f, 5800f, 400f)));
		// Low ground with seasons as mild is subantarctic tundra, as on Kerguelen.
		assertEquals(BiomeRefiner.TUNDRA, BiomeRefiner.refine("minecraft:plains", climate(4.5f, 1.9f, 800f, 20f, 50f, 30f)));
	}

	@Test
	void seasonalTropicsAreMonsoonForest() {
		// Nagpur, in the middle of India: 1100 mm, nearly all of it in the monsoon. Vanilla puts an oak forest there.
		BiomeClimate india = climate(27f, 4.5f, 1100f, 140f, 310f, 40f);
		assertEquals(BiomeRefiner.MONSOON_FOREST, BiomeRefiner.refine("minecraft:forest", india));
		assertEquals(BiomeRefiner.MONSOON_FOREST, BiomeRefiner.refine("minecraft:birch_forest", india));
		// Its open land stays savanna and plains.
		assertNull(BiomeRefiner.refine("minecraft:savanna", india));
		assertNull(BiomeRefiner.refine("minecraft:plains", india));
		// Central Thailand: 1500 mm with a long dry season, where vanilla puts a jungle.
		assertEquals(BiomeRefiner.MONSOON_FOREST,
			BiomeRefiner.refine("minecraft:jungle", climate(28.5f, 1.5f, 1500f, 85f, 20f, 10f)));
		// The Amazon, rainy all year round, and Mumbai's coast, too wet to go bare, stay jungle.
		assertNull(BiomeRefiner.refine("minecraft:jungle", climate(27f, 0.6f, 2300f, 45f, 50f, 20f)));
		assertNull(BiomeRefiner.refine("minecraft:jungle", climate(27.5f, 1.5f, 2400f, 160f, 20f, 30f)));
	}

	@Test
	void humidSubtropicsAreLaurelForest() {
		// Shanghai: 17 °C over the year, but January averages 5 °C. Vanilla puts a jungle there.
		BiomeClimate shanghai = climate(17f, 8.4f, 1170f, 45f, 5f, 10f);
		assertEquals(BiomeRefiner.LAUREL_FOREST, BiomeRefiner.refine("minecraft:jungle", shanghai));
		assertEquals(BiomeRefiner.LAUREL_FOREST, BiomeRefiner.refine("minecraft:bamboo_jungle", shanghai));
		// Atlanta, inland on the Piedmont.
		assertEquals(BiomeRefiner.LAUREL_FOREST,
			BiomeRefiner.refine("minecraft:sparse_jungle", climate(17f, 7.5f, 1270f, 20f, 300f, 60f)));
		// Hong Kong, frost-free and too wet for the monsoon forest, stays jungle.
		assertNull(BiomeRefiner.refine("minecraft:jungle", climate(23.3f, 4.9f, 2400f, 85f, 30f, 40f)));
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
	void drownedGlacialValleysAreFjords() {
		// The Sognefjord: a valley the ice lowered 400 m, now 300 m under the sea.
		BiomeClimate c = climate(7f, 5f, 2000f, 25f, -300f, 150f);
		c.glaciation = 1f;
		assertEquals(BiomeRefiner.FJORD, BiomeRefiner.refine("minecraft:cold_ocean", c));
		assertEquals(BiomeRefiner.FJORD, BiomeRefiner.refine("minecraft:deep_ocean", c));
		// Greenland's fjords freeze over.
		assertNull(BiomeRefiner.refine("minecraft:frozen_ocean", c));
		// The open sea beyond the mouth was sea before the ice.
		c.elevation = -500f;
		assertNull(BiomeRefiner.refine("minecraft:deep_cold_ocean", c));
		// Where the ice barely reached the sea, the drowned strip is too thin to be a fjord.
		c.elevation = -50f;
		c.glaciation = 0.2f;
		assertNull(BiomeRefiner.refine("minecraft:ocean", c));
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
