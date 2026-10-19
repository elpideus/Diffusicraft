package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFieldTest {
	private static final float LAPSE = -0.0065f;
	private static final float SEA_TEMPERATURE = 18f;
	private static final float COARSE_TEMPERATURE_C = 11f;

	/** Rolling hills with a known formula, a lapse-rate climate, and a coarse map with a formula of its own. */
	private static final class AnalyticSource implements TerrainSource {
		final AtomicInteger samples = new AtomicInteger();

		static float elevation(int i, int j) {
			return 300f + 200f * (float) Math.sin(i / 37.0) + 120f * (float) Math.cos(j / 23.0) + 0.01f * i;
		}

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			samples.incrementAndGet();
			int h = i1 - i0, w = j1 - j0, plane = h * w;
			float[] elevation = new float[plane];
			float[] climate = new float[TerrainSample.CLIMATE_CHANNELS * plane];
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					int px = r * w + c;
					float e = elevation(i0 + r, j0 + c);
					elevation[px] = e;
					climate[TerrainSample.TEMPERATURE * plane + px] = SEA_TEMPERATURE + LAPSE * Math.max(0f, e);
					climate[TerrainSample.TEMPERATURE_SEASONALITY * plane + px] = 600f;
					climate[TerrainSample.PRECIPITATION * plane + px] = 900f;
					climate[TerrainSample.PRECIPITATION_SEASONALITY * plane + px] = 30f;
					climate[TerrainSample.LAPSE_RATE * plane + px] = LAPSE;
				}
			}
			return new TerrainSample(i0, j0, h, w, elevation, climate);
		}

		@Override
		public int coarseScale() {
			return 4;
		}

		/** Elevation {@code 100 ci + cj}, 50 m above its 5th percentile, in a uniform climate. */
		@Override
		public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
			int plane = (ci1 - ci0) * (cj1 - cj0);
			float[] out = new float[COARSE_CHANNELS * plane];
			for (int r = 0; r < ci1 - ci0; r++) {
				for (int c = 0; c < cj1 - cj0; c++) {
					int px = r * (cj1 - cj0) + c;
					float e = 100f * (ci0 + r) + (cj0 + c);
					out[COARSE_ELEVATION * plane + px] = e;
					out[COARSE_LOW_ELEVATION * plane + px] = e - 50f;
					out[COARSE_TEMPERATURE * plane + px] = COARSE_TEMPERATURE_C;
					out[COARSE_TEMPERATURE_SEASONALITY * plane + px] = 600f;
					out[COARSE_PRECIPITATION * plane + px] = 800f;
					out[COARSE_PRECIPITATION_SEASONALITY * plane + px] = 30f;
				}
			}
			return out;
		}
	}

	@Test
	void passesThroughPixelCentres() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		for (int i = -300; i < 600; i += 37) {
			for (int j = -520; j < 700; j += 41) {
				assertEquals(AnalyticSource.elevation(i, j), field.elevation(i + 0.5, j + 0.5), 1e-2, i + "," + j);
			}
		}
	}

	@Test
	void continuousAcrossTileBorders() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		TerrainField.Column before = new TerrainField.Column(), after = new TerrainField.Column();
		for (int border : new int[]{0, TerrainField.TILE, -TerrainField.TILE}) {
			// The tile is chosen by the pixel below the sample point, which changes at border + 0.5.
			for (double j = 10.3; j < 400; j += 53) {
				field.column(border + 0.5 - 1e-6, j, before);
				field.column(border + 0.5 + 1e-6, j, after);
				assertEquals(before.elevation, after.elevation, 1e-2);
				assertEquals(before.meanElevation, after.meanElevation, 1e-2);
				assertEquals(before.relief, after.relief, 1e-2);
				assertEquals(before.temperature, after.temperature, 1e-3);
			}
		}
	}

	@Test
	void gradientIsTheElevationsSlope() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		float[] gradient = new float[2];
		double h = 0.01;
		for (double i = -300.2; i < 600; i += 37.7) {
			for (double j = -200.9; j < 400; j += 41.3) {
				float elevation = field.elevation(i, j, gradient);
				assertEquals(field.elevation(i, j), elevation, 0f);
				assertEquals((field.elevation(i + h, j) - field.elevation(i - h, j)) / (2 * h), gradient[0], 0.02);
				assertEquals((field.elevation(i, j + h) - field.elevation(i, j - h)) / (2 * h), gradient[1], 0.02);
			}
		}
	}

	@Test
	void slopeIsTheRiseAcrossTheHillside() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		float[] slope = new float[2];
		int d = TerrainField.HILLSIDE;
		double run = 2 * d * WorldScale.METERS_PER_PIXEL;
		// Across the corner of four tiles too.
		for (double i = 250.3; i < 262; i += 0.7) {
			for (double j = -7.9; j < 8; j += 1.3) {
				field.slope(i, j, slope);
				assertEquals((field.elevation(i + d, j) - field.elevation(i - d, j)) / run, slope[0], 1e-4);
				assertEquals((field.elevation(i, j + d) - field.elevation(i, j - d)) / run, slope[1], 1e-4);
			}
		}
	}

	@Test
	void theGroundKeepsCloseToTheTerrain() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = field.surface(scale);
			assertTrue(surface == field.surface(scale), "one surface per scale");
			double largest = 0;
			for (int x = -500; x < 500; x += 3) {
				for (int z = -500; z < 500; z += 7) {
					float smooth = scale.surfaceY(field.elevation(scale.toPixel(z), scale.toPixel(x)));
					largest = Math.max(largest, Math.abs(surface.y(x, z) - smooth));
				}
			}
			System.out.printf("%s: the ground strays up to %.2f blocks from the terrain%n", scale, largest);
			// Ledges at every scale, at most 10 blocks; gullies too at Earth 1:1, where the rolling hills are barely
			// steep enough for them.
			assertTrue(largest <= (scale == WorldScale.EARTH ? 18 : 10), scale + ": " + largest);
			if (scale == WorldScale.EARTH) {
				assertTrue(largest > 0, "gullies at Earth 1:1");
			}
		}
	}

	@Test
	void temperatureFollowsTheLapseRate() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		TerrainField.Column c = new TerrainField.Column();
		field.column(123.4, 567.8, c);
		assertEquals(SEA_TEMPERATURE + LAPSE * c.elevation, c.temperature, 1e-3);
		assertEquals(900f, c.precipitation, 1e-3);
		assertTrue(c.relief > 50f && c.relief < 250f, "rolling hills have relief, got " + c.relief);
	}

	@Test
	void evictsTilesAndRecomputesTheSameValues() {
		AnalyticSource source = new AnalyticSource();
		TerrainField field = new TerrainField(1, source, 2);
		float first = field.elevation(10.2, 10.7);
		for (int t = 1; t <= 4; t++) {
			field.elevation(10.2 + t * TerrainField.TILE, 10.7);
		}
		assertTrue(field.cachedTiles() <= 2);
		assertEquals(first, field.elevation(10.2, 10.7));
		assertEquals(6, source.samples.get());
	}

	@Test
	void computesEachTileOnceUnderContention() throws Exception {
		AnalyticSource source = new AnalyticSource();
		TerrainField field = new TerrainField(1, source);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Float>> results = new ArrayList<>();
			for (int k = 0; k < 64; k++) {
				double offset = k * 1.5;
				results.add(pool.submit(() -> field.elevation(100 + offset, 50 + offset)));
			}
			for (int k = 0; k < results.size(); k++) {
				double offset = k * 1.5;
				assertEquals(field.elevation(100 + offset, 50 + offset), results.get(k).get());
			}
		} finally {
			pool.shutdown();
		}
		assertEquals(1, source.samples.get());
	}

	@Test
	void interpolatesTheCoarseMap() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		// Coarse pixel (2, 3) is centred at native (10, 14) with scale 4.
		assertEquals(203f, field.coarseElevation(10, 14), 1e-4);
		assertEquals(253f, field.coarseElevation(12, 14), 1e-4);
		// Across a cached block border (16 coarse pixels).
		assertEquals(100f * 15.5f + 3f, field.coarseElevation(64, 14), 1e-3);
	}

	@Test
	void coarseColumnReadsEveryChannel() {
		TerrainField field = new TerrainField(1, new AnalyticSource());
		TerrainField.Column c = new TerrainField.Column();
		field.coarseColumn(10, 14, c);
		assertEquals(203f, c.elevation, 1e-4);
		assertEquals(203f, c.meanElevation, 1e-4);
		assertEquals(TerrainField.COARSE_RELIEF * 50f, c.relief, 1e-4);
		assertEquals(COARSE_TEMPERATURE_C, c.temperature, 1e-4);
		assertEquals(600f, c.temperatureSeasonality, 1e-4);
		assertEquals(800f, c.precipitation, 1e-4);
		assertEquals(30f, c.precipitationSeasonality, 1e-4);
	}

	@Test
	void variantNoiseDependsOnTheSeed() {
		TerrainField a = new TerrainField(1, new AnalyticSource()), b = new TerrainField(2, new AnalyticSource());
		boolean differs = false;
		for (int k = 0; k < 20; k++) {
			float va = a.variant(k * 1000.0, k * 700.0);
			assertTrue(va >= -1f && va <= 1f);
			differs |= va != b.variant(k * 1000.0, k * 700.0);
		}
		assertTrue(differs);
	}
}
