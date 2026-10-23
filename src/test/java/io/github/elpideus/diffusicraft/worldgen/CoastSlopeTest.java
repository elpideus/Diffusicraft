package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The envelope under which {@link CoastSlope} eases the land down to the sea. */
class CoastSlopeTest {
	private static final double SLOPE = 0.4;

	@Test
	void groundWellUnderTheEnvelopeKeepsItsHeight() {
		// 10 m of land 100 m inland, under an envelope of 68 m.
		assertEquals(10f, CoastSlope.ease(10f, 100, 0f, SLOPE, null), 0.2f);
		// A gentle coast, 0.2 m per meter, the whole way in.
		for (int d = 10; d < 360; d += 10) {
			float h = 0.2f * d;
			assertEquals(h, CoastSlope.ease(h, d, 0f, SLOPE, null), 0.03f * h + 0.1f, d + " m inland");
		}
	}

	@Test
	void steepGroundIsHeldNearTheEnvelope() {
		// A plateau of 100 m right at the coast: 34 m at 60 m inland, 76 m at 120 m.
		assertEquals(CoastSlope.envelope(SLOPE, 60), CoastSlope.ease(100f, 60, 0f, SLOPE, null), 1.5);
		double envelope = CoastSlope.envelope(SLOPE, 120);
		assertEquals(envelope, CoastSlope.ease(100f, 120, 0f, SLOPE, null), 0.15 * envelope);
		// Steeper coasts climb faster.
		assertTrue(CoastSlope.ease(100f, 60, 0f, CoastSlope.SLOPE_MAX, null)
			> CoastSlope.ease(100f, 60, 0f, CoastSlope.SLOPE_MIN, null) + 10);
	}

	@Test
	void theEnvelopeNeverFallsUnderItsLeast() {
		assertEquals(CoastSlope.LEAST, CoastSlope.ease(50f, 1, 0f, SLOPE, null), 0.1);
		assertEquals(CoastSlope.LEAST, CoastSlope.ease(50f, 3, 0f, SLOPE, null), 0.3);
	}

	@Test
	void theEasingFadesOutByTheReach() {
		assertEquals(500f, CoastSlope.ease(500f, 359.9, 0f, SLOPE, null), 1f);
		assertEquals(500f, CoastSlope.ease(500f, 400, 0f, SLOPE, null), 0f);
		assertEquals(500f, CoastSlope.ease(500f, 0, 0f, SLOPE, null), 0f);
		// Between, the eased ground only ever climbs inland: no hollow for the rivers to fill.
		float previous = 0;
		for (int d = 1; d <= 360; d++) {
			float eased = CoastSlope.ease(300f, d, 0f, SLOPE, null);
			assertTrue(eased >= previous - 1e-3f, d + " m inland: " + eased + " after " + previous);
			previous = eased;
		}
	}

	@Test
	void higherGroundStaysHigher() {
		for (int d = 10; d <= 350; d += 20) {
			float previous = 0;
			for (int h = 1; h <= 1000; h += 7) {
				float eased = CoastSlope.ease(h, d, 0f, SLOPE, null);
				assertTrue(eased >= previous, h + " m at " + d + " m inland: " + eased + " after " + previous);
				previous = eased;
			}
		}
	}

	@Test
	void theFjordsKeepSteeperWalls() {
		float plain = CoastSlope.ease(200f, 60, 0f, SLOPE, null), fjord = CoastSlope.ease(200f, 60, 150f, SLOPE, null);
		assertEquals(CoastSlope.envelope(CoastSlope.SLOPE_ICE, 60), fjord, 2);
		assertTrue(fjord > plain + 10, fjord + " against " + plain);
		// Still no wall: the fjord's side climbs at the ice's slope, not straight up.
		assertTrue(fjord < 60);
	}

	@Test
	void theSeaAndTheGroundBeyondTheReachAreLeftAlone() {
		assertEquals(-5f, CoastSlope.ease(-5f, 60, 0f, SLOPE, null));
		assertEquals(0f, CoastSlope.ease(0f, 60, 0f, SLOPE, null));
		assertEquals(900f, CoastSlope.ease(900f, 360, 0f, SLOPE, null));
	}

	@Test
	void theRatesMatchTheDifferences() {
		double[] rates = new double[2];
		for (float h : new float[]{5f, 30f, 60f, 150f, 400f}) {
			// Not at 5 m, where the envelope leaves its least and the rate has a corner.
			for (double d : new double[]{8, 40, 90, 200, 300, 350}) {
				for (float ice : new float[]{0f, 80f}) {
					float eased = CoastSlope.ease(h, d, ice, SLOPE, rates);
					double byHeight = (CoastSlope.ease(h + 0.5f, d, ice, SLOPE, null) - CoastSlope.ease(h - 0.5f, d, ice, SLOPE, null));
					double byDistance = (CoastSlope.ease(h, d + 0.5, ice, SLOPE, null) - CoastSlope.ease(h, d - 0.5, ice, SLOPE, null));
					String at = h + " m at " + d + " m, ice " + ice + " (" + eased + ")";
					assertEquals(byHeight, rates[0], 0.02, "by height " + at);
					assertEquals(byDistance, rates[1], 0.02 + 0.02 * Math.abs(byDistance), "by distance " + at);
				}
			}
		}
	}
}
