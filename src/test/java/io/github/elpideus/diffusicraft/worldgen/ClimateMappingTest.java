package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that real conditions land in the vanilla parameter bands that pick the expected biomes. Band edges are
 * vanilla's {@code OverworldBiomeBuilder} ones.
 */
class ClimateMappingTest {
	private static TerrainField.Column column(float elevation, float mean, float relief, float temperature, float precipitation) {
		TerrainField.Column c = new TerrainField.Column();
		c.elevation = elevation;
		c.meanElevation = mean;
		c.relief = relief;
		c.temperature = temperature;
		c.precipitation = precipitation;
		c.variant = 0.5f;
		c.coast = TerrainField.COAST_REACH;
		return c;
	}

	private static float[] parameters(TerrainField.Column c) {
		return parameters(WorldScale.STANDARD, c, WorldScale.STANDARD.surfaceY(c.elevation));
	}

	private static float[] parameters(WorldScale scale, TerrainField.Column c, float groundY) {
		float[] out = new float[ClimateMapping.PARAMETERS];
		ClimateMapping.compute(scale, c, groundY, out);
		return out;
	}

	private static boolean isCoast(float[] p) {
		return p[ClimateMapping.CONTINENTALNESS] >= -0.19f && p[ClimateMapping.CONTINENTALNESS] < -0.11f;
	}

	@Test
	void temperateLowlandIsTemperateMidInland() {
		float[] p = parameters(column(200f, 200f, 30f, 11f, 800f));
		assertTrue(p[ClimateMapping.TEMPERATURE] > -0.15f && p[ClimateMapping.TEMPERATURE] < 0.2f);
		assertTrue(p[ClimateMapping.HUMIDITY] > -0.1f, "800 mm at 11 °C is humid");
		assertTrue(p[ClimateMapping.CONTINENTALNESS] > 0.03f && p[ClimateMapping.CONTINENTALNESS] < 0.3f);
		assertTrue(p[ClimateMapping.EROSION] > 0.05f && p[ClimateMapping.EROSION] < 0.45f);
	}

	@Test
	void summerRainCountsForLess() {
		// 340 mm at 6.5 °C, January at -10 °C, mostly in summer: the steppe of Inner Mongolia.
		assertTrue(ClimateMapping.aridityRatio(6.5f, 12f, 340f, 73f) < 1f);
		// With no more than the year-round threshold, the threshold is Köppen's for summer rain.
		assertEquals(260f / (20 * 6.5f + 280), ClimateMapping.aridityRatio(6.5f, 12f, 260f, 73f), 1e-4f);
		// The same rain through the year would keep a forest.
		assertTrue(ClimateMapping.aridityRatio(6.5f, 12f, 340f, 0f) > 1f);
		// The monsoon tropics count it as summer rain too.
		assertEquals(600f / (20 * 26 + 280), ClimateMapping.aridityRatio(26f, 1f, 600f, 90f), 1e-4f);
		// Mild winters may bring Mediterranean rain, so the year-round threshold stands.
		assertEquals(ClimateMapping.aridityRatio(15f, 5f, 500f, 0f), ClimateMapping.aridityRatio(15f, 5f, 500f, 80f));
		// Cold climates keep the 140 mm floor.
		assertEquals(250f / 140f, ClimateMapping.aridityRatio(-9f, 20f, 250f, 80f), 1e-4f);
		// Far over the threshold the ground stays wet whenever the rain falls: a wet taiga, and the peat of the West
		// Siberian Plain and of northern Alberta.
		assertEquals(1200f / 200f, ClimateMapping.aridityRatio(3f, 9f, 1200f, 50f), 1e-4f);
		assertTrue(ClimateMapping.aridityRatio(-2f, 13f, 450f, 45f) > 2f);
		assertTrue(ClimateMapping.aridityRatio(1f, 13f, 450f, 50f) > 2f);
		for (float rain = 100f; rain < 3000f; rain += 10f) {
			assertTrue(ClimateMapping.aridityRatio(6.5f, 12f, rain + 10f, 73f)
				> ClimateMapping.aridityRatio(6.5f, 12f, rain, 73f), "more rain is wetter, at " + rain + " mm");
		}
	}

	@Test
	void hotDesertNeedsDryAir() {
		float[] desert = parameters(column(300f, 300f, 20f, 25f, 100f));
		assertTrue(desert[ClimateMapping.TEMPERATURE] >= 0.55f, "dry heat is the desert band");
		float[] jungle = parameters(column(300f, 300f, 20f, 25f, 2500f));
		assertTrue(jungle[ClimateMapping.TEMPERATURE] < 0.55f, "wet heat is not");
		assertTrue(jungle[ClimateMapping.HUMIDITY] > 0.3f);
	}

	@Test
	void snowOnlyWhereItIsCold() {
		// A rugged peak at 2 °C: no snow, bare stone (stony peaks need temperature band 3).
		float[] cool = parameters(column(2500f, 1800f, 300f, 2f, 900f));
		assertEquals(0.3f, cool[ClimateMapping.TEMPERATURE], 1e-6);
		// A rounded summit at 4 °C, 160 m above the land around it, keeps the woods of its own band: forced into the
		// warm band, it would get vanilla's jungle wherever vanilla does not pick a peak biome.
		float[] rounded = parameters(column(1160f, 1000f, 150f, 4f, 2000f));
		assertTrue(rounded[ClimateMapping.TEMPERATURE] < -0.15f);
		assertTrue(rounded[ClimateMapping.EROSION] >= -0.3f);
		// A rugged slope at 12 °C: pushed out of the snowy mountain erosion bands.
		float[] warm = parameters(column(1500f, 1500f, 300f, 12f, 900f));
		assertTrue(warm[ClimateMapping.EROSION] >= -0.3f);
		// A rugged peak at -8 °C keeps vanilla's frozen and jagged peaks.
		float[] cold = parameters(column(4000f, 3000f, 400f, -8f, 600f));
		assertTrue(cold[ClimateMapping.TEMPERATURE] < -0.45f);
		assertTrue(cold[ClimateMapping.EROSION] < -0.375f);
	}

	@Test
	void oceanDepthSetsContinentalness() {
		assertTrue(parameters(column(-50f, -50f, 5f, 15f, 800f))[ClimateMapping.CONTINENTALNESS] > -0.455f, "shallow sea");
		assertTrue(parameters(column(-4000f, -4000f, 50f, 15f, 800f))[ClimateMapping.CONTINENTALNESS] < -0.455f, "deep ocean");
	}

	@Test
	void lowLandAlongTheCoastlineIsCoast() {
		TerrainField.Column c = column(10f, 20f, 3f, 15f, 500f);
		c.coast = 30f;
		assertTrue(isCoast(parameters(c)));
		c.coast = 130f;
		assertFalse(isCoast(parameters(c)), "past the widest beach");
		c.coast = TerrainField.COAST_REACH;
		assertFalse(isCoast(parameters(c)), "low land far from the sea");
	}

	@Test
	void theCoastIsAsWideAsRealBeaches() {
		TerrainField.Column c = column(1f, 1f, 3f, 15f, 500f);
		c.coast = 60f;
		c.variant = -1f;
		assertFalse(isCoast(parameters(WorldScale.EARTH, c, 64f)), "a narrow beach is 45 m wide");
		c.variant = 1f;
		assertTrue(isCoast(parameters(WorldScale.EARTH, c, 64f)), "a wide one 120 m");
		c.variant = -1f;
		assertTrue(isCoast(parameters(WorldScale.STANDARD, c, 63.5f)), "5 blocks at least");
	}

	@Test
	void theTopOfACliffIsNotCoast() {
		TerrainField.Column c = column(1f, 1f, 3f, 15f, 500f);
		c.coast = 20f;
		assertTrue(isCoast(parameters(WorldScale.EARTH, c, 64f)), "the beach at its foot");
		assertFalse(isCoast(parameters(WorldScale.EARTH, c, 90f)));
		assertTrue(isCoast(parameters(WorldScale.STANDARD, c, 65f)), "2.5 blocks at least");
	}

	@Test
	void lowLandFlushWithTheSeaPastTheBeachIsMarsh() {
		TerrainField.Column c = column(0f, 0f, 1f, 15f, 1200f);
		c.coast = 200f;
		assertEquals(0.75f, parameters(c)[ClimateMapping.EROSION], 1e-6);
		c.coast = 20f;
		assertTrue(parameters(c)[ClimateMapping.EROSION] < 0.75f, "the beach stays a beach");
	}

	@Test
	void flatWetLowlandIsSwamp() {
		float[] p = parameters(column(40f, 40f, 3f, 15f, 1200f));
		assertEquals(0.75f, p[ClimateMapping.EROSION], 1e-6);
		float[] dry = parameters(column(40f, 40f, 3f, 15f, 200f));
		assertTrue(dry[ClimateMapping.EROSION] < 0.45f);
	}

	@Test
	void erosionNeverReachesTheWindsweptBand() {
		for (float relief = 0f; relief < 1000f; relief += 5f) {
			float e = ClimateMapping.erosion(relief);
			assertFalse(e > 0.45f && e < 0.55f, "relief " + relief);
		}
	}

	@Test
	void weirdnessStaysOutOfTheRiverBand() {
		for (float z = -5f; z <= 5f; z += 0.1f) {
			float pv = ClimateMapping.peaksAndValleys(z * 100f, 0f, 100f);
			for (float variant : new float[]{-1f, 1f}) {
				float w = ClimateMapping.weirdness(pv, variant);
				assertTrue(Math.abs(w) >= 0.05f, "z " + z);
				// Vanilla's PV formula recovers the value.
				assertEquals(pv, 1f - Math.abs(3f * Math.abs(w) - 2f), 1e-5);
			}
		}
	}

	@Test
	void slopesFacingTheSunAreWarmerAndDrier() {
		assertEquals(0f, ClimateMapping.exposure(0f, 0f), 0f);
		// 45°, falling toward the south and toward the north, and facing west.
		assertEquals(Math.sqrt(0.5), ClimateMapping.exposure(0f, -1f), 1e-6);
		assertEquals(-Math.sqrt(0.5), ClimateMapping.exposure(0f, 1f), 1e-6);
		assertEquals(0f, ClimateMapping.exposure(1f, 0f), 0f);
		// Only the part of the slope that faces the sun counts.
		assertEquals(0.5, ClimateMapping.exposure(1f, -1f), 0.08);

		float steep = (float) Math.tan(Math.toRadians(30));
		TerrainField.Column south = column(1500f, 1500f, 100f, 6f, 600f);
		ClimateMapping.expose(south, ClimateMapping.exposure(0f, -steep));
		assertEquals(6f + 0.5f * ClimateMapping.EXPOSURE_WARMING, south.temperature, 1e-4);
		assertEquals(600f * (1f - 0.5f * ClimateMapping.EXPOSURE_DRYING), south.precipitation, 1e-2);
		TerrainField.Column north = column(1500f, 1500f, 100f, 6f, 600f);
		ClimateMapping.expose(north, ClimateMapping.exposure(0f, steep));
		assertEquals(6f - 0.5f * ClimateMapping.EXPOSURE_WARMING, north.temperature, 1e-4);
		assertEquals(600f * (1f + 0.5f * ClimateMapping.EXPOSURE_DRYING), north.precipitation, 1e-2);
		assertTrue(parameters(south)[ClimateMapping.HUMIDITY] < parameters(north)[ClimateMapping.HUMIDITY]);

		// On a mountainside at 0 °C, the shaded side is snowy and the sunny side is not.
		TerrainField.Column cold = column(2500f, 2500f, 300f, 0f, 900f);
		TerrainField.Column shaded = column(2500f, 2500f, 300f, 0f, 900f);
		TerrainField.Column sunny = column(2500f, 2500f, 300f, 0f, 900f);
		ClimateMapping.expose(shaded, ClimateMapping.exposure(0f, steep));
		ClimateMapping.expose(sunny, ClimateMapping.exposure(0f, -steep));
		assertTrue(parameters(shaded)[ClimateMapping.TEMPERATURE] < parameters(cold)[ClimateMapping.TEMPERATURE]);
		assertTrue(parameters(shaded)[ClimateMapping.EROSION] < -0.375f, "snowy slopes");
		assertTrue(parameters(sunny)[ClimateMapping.EROSION] >= -0.3f, "no snow");
	}

	@Test
	void depthIsMeasuredFromACappedSurface() {
		assertEquals(0f, ClimateMapping.depth(WorldScale.STANDARD, 70f, 70), 1e-6);
		assertEquals(1f, ClimateMapping.depth(WorldScale.STANDARD, 70f, 70 - 128), 1e-6);
		// A 2000-block mountain: depth counts from 60 blocks above the sea.
		assertEquals(0f, ClimateMapping.depth(WorldScale.GRAND, 2000f, 123), 1e-6);
	}
}
