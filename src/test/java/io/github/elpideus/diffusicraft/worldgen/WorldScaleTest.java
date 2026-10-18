package io.github.elpideus.diffusicraft.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
	void theShallowestSeaHoldsWater() {
		for (WorldScale scale : WorldScale.values()) {
			// The floor is solid below the surface, so its top block is under the first block of water.
			assertTrue(scale.surfaceY(-0.01f) <= scale.seaLevel() - 1, scale.name());
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
	void surfaceRateIsTheSurfacesSlope() {
		for (WorldScale scale : WorldScale.values()) {
			for (float e = -11000f; e <= 9000f; e += 37f) {
				double h = Math.max(0.5, Math.abs(e) * 1e-4);
				if (Math.abs(e) < 2 * h) {
					continue;
				}
				double slope = (scale.surfaceY((float) (e + h)) - scale.surfaceY((float) (e - h))) / (2 * h);
				assertEquals(slope, scale.surfaceRate(e), 2e-3, scale + " at " + e);
			}
			assertEquals(0, scale.surfaceRate(-0.5f), scale.name());
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
	void realMountainsKeepTheirHeight() {
		// Mont Blanc stands at its full height in Earth 1:1, and at a quarter of it in Grand.
		assertEquals(63 + 4808, WorldScale.EARTH.surfaceY(4808f), 1e-3);
		assertEquals(63 + 1202, WorldScale.GRAND.surfaceY(4808f), 1e-3);
		// Only the last kilometre is eased: Everest loses less than 200 m in Earth 1:1.
		assertTrue(WorldScale.EARTH.surfaceY(8849f) - 63 > 8650);
	}

	@Test
	void theHighestPeaksFitTheirWorld() throws IOException {
		for (WorldScale scale : List.of(WorldScale.GRAND, WorldScale.EARTH)) {
			String path = "/data/diffusicraft/dimension_type/" + scale.getSerializedName() + ".json";
			JsonObject type;
			try (Reader reader = new InputStreamReader(WorldScaleTest.class.getResourceAsStream(path),
				StandardCharsets.UTF_8)) {
				type = JsonParser.parseReader(reader).getAsJsonObject();
			}
			int top = type.get("min_y").getAsInt() + type.get("height").getAsInt();
			// Room for the tallest trees above the highest surface, under the top of the world.
			assertTrue(scale.maxSurfaceY() + 20 < top, scale + " tops out at " + top);
		}
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
