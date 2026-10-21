package io.github.elpideus.diffusicraft.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

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
	void sizesHalveFromOneToOneToOneToThirtyTwo() {
		WorldScale[] sizes = WorldScale.values();
		for (int k = 0; k < sizes.length; k++) {
			assertEquals(32 >> k, sizes[k].ratio(), sizes[k].name());
		}
		assertEquals(WorldScale.ULTRA, WorldScale.DEFAULT);
	}

	@Test
	void ordinaryTerrainKeepsItsShape() {
		// Below the knee a hill is exactly its height in meters over the vertical meters per block: tripled at 1:32,
		// doubled at 1:4, half as tall again at 1:1.
		assertEquals(63 + 500 * 3.0 / 32, WorldScale.VANILLA.surfaceY(500f), 1e-3);
		assertEquals(63 + 400, WorldScale.GRAND.surfaceY(800f), 1e-3);
		assertEquals(63 + 1000 / 0.65, WorldScale.ULTRA.surfaceY(1000f), 1e-2);
	}

	@Test
	void mountainsAreDramatic() {
		// Mont Blanc stands linearly at 1:1 and at 1:4, under their knees.
		assertEquals(63 + 4808 / 0.65, WorldScale.ULTRA.surfaceY(4808f), 1e-2);
		assertEquals(63 + 2404, WorldScale.GRAND.surfaceY(4808f), 1e-3);
		// Everest towers above y = 13,000 at 1:1, and stays under what Distant Horizons can hold at 1:4.
		assertTrue(WorldScale.ULTRA.surfaceY(8849f) > 13_000);
		assertTrue(WorldScale.GRAND.surfaceY(8849f) < 3838);
	}

	@Test
	void eachSizeMatchesItsDimensionType() throws IOException {
		for (WorldScale scale : WorldScale.values()) {
			String name = scale.getSerializedName();
			String type = read("/data/diffusicraft/worldgen/world_preset/" + name + ".json")
				.getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").get("type").getAsString();
			int minY, height;
			if (type.equals("minecraft:overworld")) {
				minY = -64;
				height = 384;
			} else {
				assertEquals("diffusicraft:" + name, type);
				JsonObject dimension = read("/data/diffusicraft/dimension_type/" + name + ".json");
				minY = dimension.get("min_y").getAsInt();
				height = dimension.get("height").getAsInt();
				// Room for the tallest trees above the highest surface, under the top of the world.
				assertTrue(scale.maxSurfaceY() + 20 < minY + height, scale + " tops out at " + (minY + height));
			}
			assertEquals(minY, scale.minY(), name);
			assertEquals(height, scale.height(), name);
			// The density fades to air over the 16 blocks under the top of the world.
			assertTrue(scale.maxSurfaceY() + 16 <= scale.maxY(), name);
		}
		// The tallest world fills the tallest dimension, and the 1:4 one what Distant Horizons and Voxy hold whole.
		assertEquals(16_367, WorldScale.ULTRA.maxY());
		assertEquals(3839, WorldScale.GRAND.maxY());
	}

	private static JsonObject read(String path) throws IOException {
		try (Reader reader = new InputStreamReader(WorldScaleTest.class.getResourceAsStream(path),
			StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
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
		// 30 m pixels and 16 m blocks: blocks 0 and 1 share pixel 0.
		assertEquals(8.0 / 30, WorldScale.MEDIUM.toPixel(0), 1e-9);
		assertEquals(24.0 / 30, WorldScale.MEDIUM.toPixel(1), 1e-9);
		assertEquals(-8.0 / 30, WorldScale.MEDIUM.toPixel(-1), 1e-9);
		assertEquals(1.0 / 60, WorldScale.ULTRA.toPixel(0), 1e-9);
	}
}
