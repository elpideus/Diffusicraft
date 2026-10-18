package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldScaleTest {
	@Test
	void seaLevelIsZeroMeters() {
		for (WorldScale scale : WorldScale.values()) {
			assertEquals(scale.seaLevel(), scale.surfaceY(0f), 1e-4, scale.name());
		}
	}

	@Test
	void surfaceRisesWithElevationAndStaysInBounds() {
		for (WorldScale scale : WorldScale.values()) {
			float previous = Float.NEGATIVE_INFINITY;
			for (float e = -11000f; e <= 9000f; e += 25f) {
				float y = scale.surfaceY(e);
				assertTrue(y >= previous, scale + " at " + e);
				assertTrue(y >= scale.minSurfaceY() && y <= scale.maxSurfaceY(), scale + " at " + e);
				previous = y;
			}
		}
	}

	@Test
	void ordinaryTerrainKeepsItsProportions() {
		// Below the knee a hill is exactly its height in meters over the vertical meters per block.
		assertEquals(63 + 50, WorldScale.STANDARD.surfaceY(500f), 1e-3);
		assertEquals(63 + 200, WorldScale.GRAND.surfaceY(800f), 1e-3);
		assertEquals(63 + 1000, WorldScale.EARTH.surfaceY(1000f), 1e-3);
	}

	@Test
	void easingIsContinuousAtTheKnee() {
		double below = WorldScale.ease(99.999, 100, 240), above = WorldScale.ease(100.001, 100, 240);
		assertEquals(below, above, 1e-2);
		assertTrue(WorldScale.ease(1e6, 100, 240) <= 240);
	}

	@Test
	void blockCentresMapToPixels() {
		// 30 m pixels and 15 m blocks: blocks 0 and 1 share pixel 0.
		assertEquals(0.25, WorldScale.STANDARD.toPixel(0), 1e-9);
		assertEquals(0.75, WorldScale.STANDARD.toPixel(1), 1e-9);
		assertEquals(-0.25, WorldScale.STANDARD.toPixel(-1), 1e-9);
		assertEquals(1.0 / 60, WorldScale.EARTH.toPixel(0), 1e-9);
	}
}
