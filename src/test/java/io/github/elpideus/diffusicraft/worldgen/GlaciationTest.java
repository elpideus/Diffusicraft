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
	 * A mountain coast along {@code i = 0}: the sea 200 m deep to the north, and land rising inland 2 m per pixel, cut
	 * by valleys every 200 pixels across, at {@code j = 0}, between ridges 800 m high. The coarse pixels are a tile
	 * wide, as in the game.
	 */
	private static final class CoastSource implements TerrainSource {
		private final float seaTemperature;

		CoastSource(float seaTemperature) {
			this.seaTemperature = seaTemperature;
		}

		static float elevation(double i, double j) {
			return i < 0 ? -200f : (float) (2 * i + 400 * (1 - Math.cos(2 * Math.PI * j / 200)));
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
		for (float g : new float[] {0.25f, 0.5f, 1f}) {
			for (float e = -2000f; e < 1000f; e += 0.5f) {
				assertTrue(Glaciation.deepen(e + 0.5f, g) > Glaciation.deepen(e, g), "at " + e + " m, g " + g);
			}
		}
	}

	@Test
	void valleyFloorsSinkAndTheHighGroundAndDeepSeaStay() {
		assertEquals(-350f, Glaciation.deepen(50f, 1f));
		assertEquals(-150f, Glaciation.deepen(50f, 0.5f));
		assertEquals(Glaciation.SHOULDER, Glaciation.deepen(Glaciation.SHOULDER, 1f));
		assertEquals(-Glaciation.SEA_FADE, Glaciation.deepen(-Glaciation.SEA_FADE, 1f));
		assertEquals(50f, Glaciation.deepen(50f, 0f));
		assertTrue(Glaciation.drowned(Glaciation.deepen(1f, 1f), 1f));
		assertFalse(Glaciation.drowned(Glaciation.deepen(-1f, 1f), 1f));
	}

	@Test
	void coldMountainCoastsDrownTheirValleys() {
		TerrainField field = new TerrainField(1, new CoastSource(5f));
		TerrainField.Column column = new TerrainField.Column();
		// A valley floor 50 m up, 750 m inland, is now 350 m under the sea.
		field.column(25, 0, column);
		assertEquals(-350f, column.elevation, 5f);
		assertEquals(1f, column.glaciation, 1e-3f);
		assertTrue(Glaciation.drowned(column.elevation, column.glaciation));
		// The ridge above it stands as it was.
		field.column(25, 100, column);
		assertEquals(CoastSource.elevation(25, 100), column.elevation, 1f);
		// The open sea, deepened too, was sea before.
		field.column(-100, 50, column);
		assertEquals(Glaciation.deepen(-200f, 1f), column.elevation, 1f);
		assertFalse(Glaciation.drowned(column.elevation, column.glaciation));
		// So is the coarse map's.
		field.coarseColumn(-128, 128, column);
		assertEquals(Glaciation.deepen(-200f, 1f), column.elevation, 1f);
		assertEquals(1f, column.glaciation, 1e-3f);
		// Far inland, beyond the ice's reach, the valleys are dry.
		field.column(5 * TerrainField.TILE, 0, column);
		assertEquals(CoastSource.elevation(5 * TerrainField.TILE, 0), column.elevation, 5f);
		assertEquals(0f, column.glaciation);
	}

	@Test
	void mildCoastsKeepTheirValleys() {
		TerrainField field = new TerrainField(1, new CoastSource(15f));
		TerrainField.Column column = new TerrainField.Column();
		field.column(25, 0, column);
		assertEquals(50f, column.elevation, 5f);
		assertEquals(0f, column.glaciation);
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
