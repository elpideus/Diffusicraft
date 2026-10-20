package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlaciationTest {
	private static final float LAPSE = -0.0065f;

	/**
	 * A mountain coast along {@code i = 0}: the sea 200 m deep to the north, and land rising inland {@code rise} m per
	 * pixel, cut by valleys every 200 pixels across, at {@code j = 0}, between ridges 800 m high. West of {@code j = 0}
	 * the land may be a plain {@code plain} m high instead. Or the land holds one broad valley instead, whose flat
	 * floor fills the coarse pixels at {@code 0 <= j < TILE} between walls rising to 800 m. The coarse pixels are a
	 * tile wide, as in the game.
	 */
	private static final class CoastSource implements TerrainSource {
		private final float seaTemperature;
		private final float rise;
		private final float plain;
		private final boolean broad;

		private CoastSource(float seaTemperature, float rise, float plain, boolean broad) {
			this.seaTemperature = seaTemperature;
			this.rise = rise;
			this.plain = plain;
			this.broad = broad;
		}

		CoastSource(float seaTemperature, float rise, float plain) {
			this(seaTemperature, rise, plain, false);
		}

		static CoastSource broadValley(float seaTemperature, float rise) {
			return new CoastSource(seaTemperature, rise, Float.NaN, true);
		}

		CoastSource(float seaTemperature, float rise) {
			this(seaTemperature, rise, Float.NaN);
		}

		CoastSource(float seaTemperature) {
			this(seaTemperature, 2f);
		}

		float elevation(double i, double j) {
			if (i < 0) {
				return -200f;
			}
			if (broad) {
				double beyond = Math.max(0, Math.max(-j, j - TerrainField.TILE));
				return (float) (rise * i + Math.min(800, 8 * beyond));
			}
			return j < 0 && !Float.isNaN(plain) ? plain
				: (float) (rise * i + 400 * (1 - Math.cos(2 * Math.PI * j / 200)));
		}

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			int h = i1 - i0, w = j1 - j0, plane = h * w;
			float[] elevation = new float[plane];
			float[] climate = new float[TerrainSample.CLIMATE_CHANNELS * plane];
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					int px = r * w + c;
					float e = elevation(i0 + r + 0.5, j0 + c + 0.5);
					elevation[px] = e;
					climate[TerrainSample.TEMPERATURE * plane + px] = seaTemperature + LAPSE * Math.max(0f, e);
					climate[TerrainSample.TEMPERATURE_SEASONALITY * plane + px] = 500f;
					climate[TerrainSample.PRECIPITATION * plane + px] = 2000f;
					climate[TerrainSample.PRECIPITATION_SEASONALITY * plane + px] = 25f;
					climate[TerrainSample.LAPSE_RATE * plane + px] = LAPSE;
				}
			}
			return new TerrainSample(i0, j0, h, w, elevation, climate);
		}

		@Override
		public int coarseScale() {
			return TerrainField.TILE;
		}

		/** The mean and the 5th percentile of 16² samples in each coarse pixel. */
		@Override
		public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
			int w = cj1 - cj0, plane = (ci1 - ci0) * w, n = 16, scale = coarseScale();
			float[] out = new float[COARSE_CHANNELS * plane];
			float[] samples = new float[n * n];
			for (int r = 0; r < ci1 - ci0; r++) {
				for (int c = 0; c < w; c++) {
					double sum = 0;
					for (int a = 0; a < n; a++) {
						for (int b = 0; b < n; b++) {
							float e = elevation((ci0 + r + (a + 0.5) / n) * scale, (cj0 + c + (b + 0.5) / n) * scale);
							samples[a * n + b] = e;
							sum += e;
						}
					}
					Arrays.sort(samples);
					int px = r * w + c;
					float mean = (float) (sum / samples.length);
					out[COARSE_ELEVATION * plane + px] = mean;
					out[COARSE_LOW_ELEVATION * plane + px] = samples[samples.length / 20];
					out[COARSE_TEMPERATURE * plane + px] = seaTemperature + LAPSE * Math.max(0f, mean);
					out[COARSE_TEMPERATURE_SEASONALITY * plane + px] = 500f;
					out[COARSE_PRECIPITATION * plane + px] = 2000f;
					out[COARSE_PRECIPITATION_SEASONALITY * plane + px] = 25f;
				}
			}
			return out;
		}
	}

	@Test
	void deepeningKeepsHeightsInOrder() {
		for (float floor : new float[] {0f, 300f, 800f}) {
			for (float cut : new float[] {100f, 400f, Glaciation.FULL_CUT}) {
				for (float mountainous : new float[] {0f, 0.5f, 1f}) {
					for (float e = -4000f; e < 2000f; e += 0.5f) {
						assertTrue(Glaciation.deepen(e + 0.5f, floor, cut, mountainous)
								> Glaciation.deepen(e, floor, cut, mountainous),
							"at " + e + " m, floor " + floor + " m, cut " + cut + " m, mountainous " + mountainous);
					}
				}
			}
		}
	}

	@Test
	void valleyFloorsSinkAndTheHighGroundAndDeepSeaStay() {
		assertEquals(-350f, Glaciation.deepen(50f, 0f, 400f, 1f));
		assertEquals(-150f, Glaciation.deepen(50f, 0f, 200f, 1f));
		// A valley floor 300 m up, cut down to 400 m under the sea.
		assertEquals(-400f, Glaciation.deepen(300f, 300f, 700f, 1f));
		assertEquals(Glaciation.SHOULDER, Glaciation.deepen(Glaciation.SHOULDER, 0f, 400f, 1f));
		assertEquals(300f + Glaciation.SHOULDER, Glaciation.deepen(300f + Glaciation.SHOULDER, 300f, 700f, 1f));
		assertEquals(-1200f, Glaciation.deepen(-1200f, 0f, 400f, 1f));
		assertEquals(50f, Glaciation.deepen(50f, 0f, 0f, 1f));
		assertTrue(Glaciation.drowned(Glaciation.deepen(1f, 0f, 400f, 1f), 400f));
		assertTrue(Glaciation.drowned(Glaciation.deepen(301f, 300f, 700f, 1f), 700f));
		assertFalse(Glaciation.drowned(Glaciation.deepen(-1f, 0f, 400f, 1f), 400f));
		// On a plain, the ice cut only the ground within a few meters of the floor: half way up, half as deep.
		float half = Glaciation.PLAIN_SHOULDER / 2;
		assertEquals(-400f, Glaciation.deepen(0f, 0f, 400f, 0f));
		assertEquals(half - 200f, Glaciation.deepen(half, 0f, 400f, 0f));
		assertEquals(50f, Glaciation.deepen(50f, 0f, 400f, 0f));
	}

	@Test
	void theTroughClimbsInlandAndSparesHighValleys() {
		assertEquals(Glaciation.DEPTH, Glaciation.cut(0f, 0f));
		assertEquals(0f, Glaciation.cut(0f, Glaciation.LENGTH));
		// Half way, the trough lies 200 m under the sea.
		assertEquals(500f, Glaciation.cut(300f, Glaciation.LENGTH / 2), 1e-3f);
		assertEquals(0f, Glaciation.cut(Glaciation.MAX_CUT, 0f));
	}

	@Test
	void coldMountainCoastsDrownTheirValleys() {
		CoastSource coast = new CoastSource(5f);
		TerrainField field = new TerrainField(1, coast);
		TerrainField.Column column = new TerrainField.Column();
		// A valley floor 50 m up, 750 m inland, sank by the whole cut, to the trough some 400 m under the sea.
		field.column(25, 0, column);
		assertEquals(50f - column.iceCut, column.elevation, 5f);
		assertEquals(-Glaciation.DEPTH, column.elevation, 50f);
		assertTrue(Glaciation.drowned(column.elevation, column.iceCut));
		// The ridge above it stands as it was.
		field.column(25, 100, column);
		assertEquals(coast.elevation(25, 100), column.elevation, 1f);
		// The open sea, deepened too, was sea before.
		field.column(-100, 50, column);
		assertEquals(Glaciation.deepen(-200f, 0f, column.iceCut, 1f), column.elevation, 1f);
		assertFalse(Glaciation.drowned(column.elevation, column.iceCut));
		// So is the coarse map's.
		field.coarseColumn(-128, 128, column);
		assertEquals(Glaciation.deepen(-200f, 0f, Glaciation.DEPTH, 1f), column.elevation, 1f);
		assertEquals(Glaciation.DEPTH, column.iceCut, 1e-3f);
		// Valleys climbing this steeply leave the trough behind within a few tiles: 2.5 km up, they are dry.
		field.column(5 * TerrainField.TILE, 0, column);
		assertEquals(coast.elevation(5 * TerrainField.TILE, 0), column.elevation, 5f);
		assertEquals(0f, column.iceCut);
	}

	@Test
	void gentleValleysDrownFarInland() {
		CoastSource coast = new CoastSource(5f, 0.2f);
		TerrainField field = new TerrainField(1, coast);
		TerrainField.Column column = new TerrainField.Column();
		// Six tiles inland, 46 km in the game, a valley floor 300 m up is a fjord.
		int i = 6 * TerrainField.TILE;
		field.column(i, 0, column);
		assertTrue(Glaciation.drowned(column.elevation, column.iceCut), column.elevation + " m, cut " + column.iceCut);
		// Its walls still stand.
		field.column(i, 100, column);
		assertEquals(coast.elevation(i, 100), column.elevation, 1f);
		// Beyond the ice's reach, the valleys are dry.
		i = (Glaciation.SEA_REACH + 1) * TerrainField.TILE;
		field.column(i, 0, column);
		assertEquals(coast.elevation(i, 0), column.elevation, 5f);
		assertEquals(0f, column.iceCut);
	}

	@Test
	void broadValleysDrownToo() {
		TerrainField field = new TerrainField(1, CoastSource.broadValley(5f, 0.05f));
		TerrainField.Column column = new TerrainField.Column();
		// The coarse pixel is flat, but the walls on both sides make its floor a glacier's. As on plains, the ice cut
		// only the lowest ground: the floor, all but level.
		int i = 2 * TerrainField.TILE + TerrainField.TILE / 2;
		field.column(i, TerrainField.TILE / 2, column);
		assertTrue(Glaciation.drowned(column.elevation, column.iceCut), column.elevation + " m, cut " + column.iceCut);
	}

	@Test
	void plainsBesideTheMountainsKeepTheirGround() {
		TerrainField field = new TerrainField(1, new CoastSource(5f, 2f, 40f));
		TerrainField.Column column = new TerrainField.Column();
		// A plain 40 m up, by the sea and a tile from the mountains: the ice reached it but spread over it.
		field.column(25, -TerrainField.TILE / 2, column);
		assertTrue(column.iceCut > 0f);
		assertEquals(40f, column.elevation, 1f);
		// The valley beside it drowned.
		field.column(25, 200, column);
		assertTrue(Glaciation.drowned(column.elevation, column.iceCut), column.elevation + " m, cut " + column.iceCut);
	}

	@Test
	void mildCoastsKeepTheirValleys() {
		TerrainField field = new TerrainField(1, new CoastSource(15f));
		TerrainField.Column column = new TerrainField.Column();
		field.column(25, 0, column);
		assertEquals(50f, column.elevation, 5f);
		assertEquals(0f, column.iceCut);
	}

	@Test
	void overlappingSamplesAgreeToTheBit() {
		Glaciation glaciation = new Glaciation(new CoastSource(5f));
		TerrainSample a = glaciation.sample(-300, -300, 20, 20), b = glaciation.sample(-100, -100, 220, 220);
		for (int i = -100; i < 20; i++) {
			for (int j = -100; j < 20; j++) {
				assertEquals(a.elevation(i, j), b.elevation(i, j), 0f);
				assertEquals(a.climate(TerrainSample.TEMPERATURE, i, j), b.climate(TerrainSample.TEMPERATURE, i, j), 0f);
			}
		}
	}
}
