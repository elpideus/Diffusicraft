package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
		assertNull(BiomeRefiner.refine("minecraft:ice_spikes", climate(-25f, 12f, 200f, 30f, 2500f, 40f)));
		assertNull(BiomeRefiner.refine("minecraft:frozen_peaks", climate(-6f, 6f, 400f, 30f, 300f, 120f)));
	}

	@Test
	void polarGroundGrowsNoForest() {
		// The wet southeastern flank of the Greenland ice sheet: vanilla's frozen band has snowy taiga there, and taiga
		// where it is wettest.
		BiomeClimate ice = climate(-12f, 8f, 900f, 25f, 200f, 40f);
		assertEquals(BiomeRefiner.SNOWY_PLAINS, BiomeRefiner.refine("minecraft:snowy_taiga", ice));
		assertEquals(BiomeRefiner.SNOWY_PLAINS, BiomeRefiner.refine("minecraft:taiga", ice));
		assertEquals(BiomeRefiner.SNOWY_SLOPES, BiomeRefiner.refine("minecraft:grove", ice));
		assertEquals(BiomeRefiner.SNOWY_SLOPES, BiomeRefiner.refine("minecraft:windswept_forest", ice));
		// Utqiaġvik: a year of -11 °C and summers of 5 °C, white most of the year.
		BiomeClimate arctic = climate(-11.3f, 11.5f, 115f, 40f, 10f, 10f);
		assertEquals(BiomeRefiner.SNOWY_PLAINS, BiomeRefiner.refine("minecraft:snowy_taiga", arctic));
		assertNull(BiomeRefiner.refine("minecraft:snowy_plains", arctic));
		// The South Shetlands, off the Antarctic Peninsula: a year of -2 °C and summers of 2 °C, in vanilla's cold band.
		assertEquals(BiomeRefiner.SNOWY_PLAINS,
			BiomeRefiner.refine("minecraft:forest", climate(-2.3f, 2.9f, 700f, 20f, 50f, 30f)));
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
	void meadowsKeepToTheSubalpineBeltAndDryGround() {
		// 1000 m in the Vosges, 7 °C, July 16 °C and 1200 mm: the mixed forest of beech and fir, not meadow.
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:meadow", climate(7f, 6.5f, 1200f, 25f, 1000f, 200f)));
		// 1100 m in the Black Forest's south, 9.5 °C on its sunny side and wetter: beech.
		assertEquals(BiomeRefiner.BIRCH_FOREST,
			BiomeRefiner.refine("minecraft:meadow", climate(9.5f, 6.5f, 1500f, 25f, 1100f, 200f)));
		// 1300 m in the Tatras, 3 °C, July 15.5 °C: spruce.
		assertEquals(BiomeRefiner.TAIGA,
			BiomeRefiner.refine("minecraft:meadow", climate(3f, 9f, 900f, 40f, 1300f, 250f)));
		// Just humid enough: a plain forest. With less rain the summers are too dry for closed woods.
		assertEquals(BiomeRefiner.FOREST,
			BiomeRefiner.refine("minecraft:meadow", climate(10f, 7f, 600f, 30f, 900f, 150f)));
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:meadow", climate(10f, 7f, 500f, 30f, 900f, 150f)));
		// The hills of central Anatolia, 380 mm at 11 °C, are too dry for a closed forest: open woods of juniper. With
		// 240 mm, too dry for junipers too.
		assertEquals(BiomeRefiner.JUNIPER_WOODLAND,
			BiomeRefiner.refine("minecraft:meadow", climate(11f, 8f, 380f, 45f, 1100f, 120f)));
		assertNull(BiomeRefiner.refine("minecraft:meadow", climate(11f, 8f, 240f, 45f, 1100f, 120f)));
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
	void ruggedSemiAridGroundGrowsJuniperWoodland() {
		// The foothills around Santa Fe, 360 mm at 10 °C with the summer monsoon, and the hills above Cedar City, 280 mm
		// at 10 °C: pinyon and juniper.
		BiomeClimate santaFe = climate(10.1f, 7.9f, 360f, 45f, 2130f, 80f);
		BiomeClimate cedarHills = climate(9.9f, 9f, 280f, 30f, 1900f, 100f);
		for (String biome : List.of("minecraft:plains", "minecraft:savanna", "minecraft:forest", "minecraft:taiga",
			"minecraft:meadow")) {
			assertEquals(BiomeRefiner.JUNIPER_WOODLAND, BiomeRefiner.refine(biome, santaFe), biome);
			assertEquals(BiomeRefiner.JUNIPER_WOODLAND, BiomeRefiner.refine(biome, cedarHills), biome);
		}
		// The valley floor below Cedar City, as flat as the Great Plains: steppe.
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:plains", climate(9.9f, 9f, 280f, 30f, 1700f, 20f)));
		// The dry hills of Mongolia are too cold for junipers, the hills of the Sahel too hot, and the hills of the
		// Great Basin's driest ranges too dry.
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:taiga", climate(0f, 13f, 200f, 60f, 1500f, 120f)));
		assertNull(BiomeRefiner.refine("minecraft:savanna", climate(26f, 4f, 450f, 100f, 500f, 100f)));
		assertEquals(BiomeRefiner.COLD_DESERT,
			BiomeRefiner.refine("minecraft:plains", climate(10f, 9f, 180f, 30f, 1600f, 100f)));
	}

	@Test
	void drySummersOpenTheWoodsIntoForestSteppeAndSteppe() {
		// Astana, Orenburg and Volgograd: rain enough for woods by the year's mean, too little for their hot summers.
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:forest", climate(3.5f, 12.3f, 330f, 40f, 350f, 20f)));
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:plains", climate(5.2f, 12.4f, 380f, 25f, 100f, 20f)));
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:taiga", climate(8.5f, 10.8f, 380f, 20f, 100f, 20f)));
		// Groves among the meadow-steppe: Kharkiv, Omsk, Edmonton's aspen parkland, Budapest and Harbin.
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:forest", climate(7.7f, 9.4f, 520f, 25f, 150f, 30f)));
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:taiga", climate(1.9f, 12.7f, 400f, 60f, 90f, 20f)));
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:plains", climate(4.2f, 9.9f, 456f, 70f, 670f, 30f)));
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:birch_forest", climate(11.1f, 7.7f, 560f, 20f, 110f, 30f)));
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:taiga", climate(4.8f, 14.5f, 530f, 110f, 150f, 40f)));
		// Fairbanks gets less rain than Astana, but its ground is frozen below: taiga.
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(-2.3f, 14.1f, 280f, 60f, 140f, 40f)));
		// Moscow, with as warm a summer and more rain, stays forest.
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:forest", climate(5.8f, 9f, 710f, 30f, 150f, 30f)));
	}

	@Test
	void hotDesertsGrowScrubButTheDriestAreBareSand() {
		// The Mojave at Las Vegas, the Sonoran Desert at Phoenix, the Nama Karoo at Upington and the desert of
		// Mesopotamia at Baghdad.
		assertEquals(BiomeRefiner.DESERT_SCRUB, BiomeRefiner.refine("minecraft:desert",
			climate(20.3f, 8.5f, 106f, 53f, 620f, 40f)));
		assertEquals(BiomeRefiner.DESERT_SCRUB, BiomeRefiner.refine("minecraft:desert",
			climate(24f, 7.6f, 204f, 54f, 340f, 40f)));
		assertEquals(BiomeRefiner.DESERT_SCRUB, BiomeRefiner.refine("minecraft:desert",
			climate(20.5f, 5.7f, 190f, 70f, 830f, 30f)));
		assertEquals(BiomeRefiner.DESERT_SCRUB, BiomeRefiner.refine("minecraft:desert",
			climate(23f, 8.8f, 150f, 90f, 40f, 20f)));
		// The hyperarid Sahara at Cairo and the Libyan Desert's sand sea at Kufra.
		assertNull(BiomeRefiner.refine("minecraft:desert", climate(22f, 5f, 25f, 100f, 50f, 30f)));
		assertNull(BiomeRefiner.refine("minecraft:desert", climate(23f, 6.4f, 2f, 150f, 380f, 30f)));
		// Badlands stay badlands.
		assertNull(BiomeRefiner.refine("minecraft:badlands", climate(24f, 7.6f, 204f, 54f, 900f, 60f)));
	}

	@Test
	void hemiborealClimatesGrowMixedForest() {
		// Helsinki, Moscow, Stockholm, Ottawa and Tonghua, in the hills of Manchuria: on either side of vanilla's line
		// between taiga and forest.
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:taiga", climate(6f, 7.7f, 680f, 25f, 20f, 20f)));
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:taiga", climate(5.8f, 9f, 710f, 30f, 150f, 30f)));
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:birch_forest", climate(7.4f, 6.7f, 540f, 30f, 30f, 30f)));
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:forest", climate(6.6f, 11f, 920f, 12f, 80f, 40f)));
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:taiga", climate(5.3f, 13.3f, 870f, 90f, 400f, 60f)));
		// Vanilla's variants and open land stay.
		assertNull(BiomeRefiner.refine("minecraft:old_growth_spruce_taiga", climate(6f, 7.7f, 680f, 25f, 20f, 20f)));
		assertNull(BiomeRefiner.refine("minecraft:plains", climate(6f, 7.7f, 680f, 25f, 20f, 20f)));
		// The beeches of Copenhagen, where the winters stay mild, and the broadleaf forest around Berlin.
		assertNull(BiomeRefiner.refine("minecraft:forest", climate(8.8f, 5.8f, 600f, 20f, 10f, 20f)));
		assertNull(BiomeRefiner.refine("minecraft:forest", climate(10f, 6.9f, 580f, 20f, 40f, 20f)));
		// Summers too cool for the oak: Iceland, where only stunted birches grow, and the southern taiga around Kirov.
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", climate(5f, 4.3f, 870f, 20f, 30f, 40f)));
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(2.6f, 10f, 640f, 25f, 160f, 30f)));
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
	void flatBasinFloorsAreSaltFlats() {
		// Bonneville, and the Salar de Uyuni at 3650 m: the lowest ground around.
		BiomeClimate bonneville = climate(11f, 9f, 150f, 30f, 1290f, 1f);
		bonneville.aboveLow = -3f;
		assertEquals(BiomeRefiner.SALT_FLAT, BiomeRefiner.refine("minecraft:desert", bonneville));
		BiomeClimate uyuni = climate(5f, 3f, 100f, 90f, 3650f, 0.5f);
		uyuni.aboveLow = -1f;
		assertEquals(BiomeRefiner.SALT_FLAT, BiomeRefiner.refine("minecraft:plains", uyuni));
		// As flat, but higher than the basin's floor: the water runs off it.
		bonneville.aboveLow = 15f;
		assertNotEquals(BiomeRefiner.SALT_FLAT, BiomeRefiner.refine("minecraft:desert", bonneville));
		// Dunes and rocky desert stay desert.
		BiomeClimate dunes = climate(25f, 7f, 50f, 100f, 300f, 25f);
		dunes.aboveLow = -3f;
		assertNull(BiomeRefiner.refine("minecraft:desert", dunes));
	}

	@Test
	void theTreeLineClimbsHigherOnTheSunnySide() {
		// 1900 m in the Alps, just under the tree line: July around 10.5 °C on flat ground, where the forest thins out.
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", onSlope(0, 2f, 6f, 1200f, 25f, 1900f, 200f)));
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", onSlope(1, 2f, 6f, 1200f, 25f, 1900f, 200f)));
		assertEquals(BiomeRefiner.ALPINE_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", onSlope(-1, 2f, 6f, 1200f, 25f, 1900f, 200f)));
	}

	@Test
	void inADryClimateTheWoodsKeepToTheShadedSlopes() {
		// The foothills of the Rockies: firs, pines and aspens on the slopes facing north, which are cool enough for the
		// mixed forest, open pine woods on flat ground and junipers on the slopes facing south.
		assertEquals(BiomeRefiner.FOREST_STEPPE,
			BiomeRefiner.refine("minecraft:forest", onSlope(0, 10f, 9f, 520f, 30f, 1800f, 200f)));
		assertEquals(BiomeRefiner.MIXED_FOREST,
			BiomeRefiner.refine("minecraft:forest", onSlope(-1, 10f, 9f, 520f, 30f, 1800f, 200f)));
		assertEquals(BiomeRefiner.JUNIPER_WOODLAND,
			BiomeRefiner.refine("minecraft:forest", onSlope(1, 10f, 9f, 520f, 30f, 1800f, 200f)));
		// The plains below them get less rain, most of it in summer: steppe even on the shaded side, as around Denver.
		assertEquals(BiomeRefiner.STEPPE,
			BiomeRefiner.refine("minecraft:forest", onSlope(-1, 10f, 9f, 300f, 30f, 1600f, 30f)));
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
		c.iceCut = 400f;
		assertEquals(BiomeRefiner.FJORD, BiomeRefiner.refine("minecraft:cold_ocean", c));
		assertEquals(BiomeRefiner.FJORD, BiomeRefiner.refine("minecraft:deep_ocean", c));
		// Greenland's fjords freeze over.
		assertNull(BiomeRefiner.refine("minecraft:frozen_ocean", c));
		// The open sea beyond the mouth was sea before the ice.
		c.elevation = -500f;
		assertNull(BiomeRefiner.refine("minecraft:deep_cold_ocean", c));
		// Where the ice barely reached the sea, the drowned strip is too thin to be a fjord.
		c.elevation = -50f;
		c.iceCut = 80f;
		assertNull(BiomeRefiner.refine("minecraft:ocean", c));
	}

	@Test
	void siberianWintersGrowLarchTaiga() {
		// Yakutsk: a year of -9 °C, January near -37 °C, July near 20 °C, 240 mm of rain. Vanilla's frozen band.
		BiomeClimate yakutsk = climate(-8.8f, 20f, 240f, 55f, 100f, 30f);
		assertEquals(BiomeRefiner.LARCH_TAIGA, BiomeRefiner.refine("minecraft:snowy_plains", yakutsk));
		assertEquals(BiomeRefiner.LARCH_TAIGA, BiomeRefiner.refine("minecraft:snowy_taiga", yakutsk));
		assertEquals(BiomeRefiner.LARCH_TAIGA, BiomeRefiner.refine("minecraft:ice_spikes", yakutsk));
		// The Greater Khingan: a year of -4.5 °C and a January of -28 °C, in vanilla's cold band.
		assertEquals(BiomeRefiner.LARCH_TAIGA,
			BiomeRefiner.refine("minecraft:taiga", climate(-4.5f, 17f, 450f, 70f, 600f, 60f)));
		// Its slopes stay vanilla's.
		assertNull(BiomeRefiner.refine("minecraft:grove", yakutsk));
		// Milder winters keep the spruce: the snowy taiga of Labrador, and the taiga around Arkhangelsk.
		assertNull(BiomeRefiner.refine("minecraft:snowy_taiga", climate(-5f, 13f, 800f, 25f, 400f, 40f)));
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(1.5f, 10.5f, 600f, 30f, 20f, 20f)));
		// Verkhoyansk and Oymyakon: the coldest winters of the north, a year of -15 °C, and summers of 15 °C.
		assertEquals(BiomeRefiner.LARCH_TAIGA,
			BiomeRefiner.refine("minecraft:snowy_plains", climate(-14.5f, 22f, 180f, 55f, 140f, 30f)));
		assertEquals(BiomeRefiner.LARCH_TAIGA,
			BiomeRefiner.refine("minecraft:snowy_taiga", climate(-15.5f, 21f, 210f, 55f, 740f, 60f)));
		// Too dry for a forest: vanilla's snow.
		assertNull(BiomeRefiner.refine("minecraft:snowy_plains", climate(-8f, 20f, 100f, 55f, 100f, 30f)));
		// The mires of the frozen lowlands stay bogs.
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:taiga", climate(-4f, 16f, 450f, 70f, 50f, 2f)));
	}

	@Test
	void theTaigaThinsIntoForestTundraBelowTheTreeLine() {
		// Kuujjuarapik on Hudson Bay, Nain in Labrador and Murmansk: summers of 11 to 13 °C.
		BiomeClimate hudsonBay = climate(-4.4f, 10.9f, 650f, 35f, 20f, 30f);
		assertEquals(BiomeRefiner.FOREST_TUNDRA, BiomeRefiner.refine("minecraft:taiga", hudsonBay));
		assertEquals(BiomeRefiner.FOREST_TUNDRA, BiomeRefiner.refine("minecraft:forest", hudsonBay));
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:old_growth_spruce_taiga", climate(-2.4f, 9.7f, 900f, 25f, 50f, 60f)));
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", climate(0.6f, 8.6f, 480f, 35f, 50f, 40f)));
		// Vanilla's frozen band: the snowy taiga and snowy plains of the Ungava Peninsula, a year of -6 °C.
		BiomeClimate ungava = climate(-6f, 12f, 500f, 40f, 150f, 30f);
		assertEquals(BiomeRefiner.FOREST_TUNDRA, BiomeRefiner.refine("minecraft:snowy_taiga", ungava));
		assertEquals(BiomeRefiner.FOREST_TUNDRA, BiomeRefiner.refine("minecraft:snowy_plains", ungava));
		// Akureyri in Iceland, whose mild winters grow mountain birch.
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", climate(3.6f, 5.2f, 500f, 30f, 30f, 40f)));
		// A mountainside just below the tree line.
		assertEquals(BiomeRefiner.FOREST_TUNDRA,
			BiomeRefiner.refine("minecraft:taiga", climate(1f, 7f, 1200f, 25f, 1900f, 300f)));
		// Rovaniemi, on the Arctic Circle, has summers of 15 °C, and closed taiga.
		assertNull(BiomeRefiner.refine("minecraft:taiga", climate(0.9f, 10.2f, 550f, 30f, 100f, 40f)));
		// Too dry for trees: vanilla's snow. Colder winters grow larches, and flat, wet ground bogs.
		assertNull(BiomeRefiner.refine("minecraft:snowy_plains", climate(-7f, 12.5f, 120f, 50f, 100f, 30f)));
		assertEquals(BiomeRefiner.LARCH_TAIGA,
			BiomeRefiner.refine("minecraft:snowy_taiga", climate(-9f, 14f, 300f, 50f, 100f, 30f)));
		assertEquals(BiomeRefiner.BOG, BiomeRefiner.refine("minecraft:taiga", climate(-4.4f, 10.9f, 650f, 35f, 20f, 3f)));
		// Its slopes stay vanilla's.
		assertNull(BiomeRefiner.refine("minecraft:grove", hudsonBay));
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
		// Flat land around San Francisco Bay has cool summers and rain enough, but the summers are dry: no peat grows.
		BiomeClimate bay = climate(14f, 2f, 900f, 70f, 20f, 3f);
		assertEquals(BiomeRefiner.MAQUIS, BiomeRefiner.refine("minecraft:plains", bay));
		assertEquals("minecraft:swamp", BiomeRefiner.wetland(bay));
	}
}
