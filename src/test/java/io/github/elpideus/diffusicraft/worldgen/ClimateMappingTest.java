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
		return c;
	}

	private static float[] parameters(TerrainField.Column c) {
		float[] out = new float[ClimateMapping.PARAMETERS];
		ClimateMapping.compute(WorldScale.STANDARD, c, out);
		return out;
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
	void lowFlatLandNearTheSeaIsCoast() {
		float[] p = parameters(column(10f, 20f, 3f, 15f, 500f));
		assertTrue(p[ClimateMapping.CONTINENTALNESS] >= -0.19f && p[ClimateMapping.CONTINENTALNESS] < -0.11f);
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
	void depthIsMeasuredFromACappedSurface() {
		assertEquals(0f, ClimateMapping.depth(WorldScale.STANDARD, 70f, 70), 1e-6);
		assertEquals(1f, ClimateMapping.depth(WorldScale.STANDARD, 70f, 70 - 128), 1e-6);
		// A 2000-block mountain: depth counts from 60 blocks above the sea.
		assertEquals(0f, ClimateMapping.depth(WorldScale.GRAND, 2000f, 123), 1e-6);
	}
}
