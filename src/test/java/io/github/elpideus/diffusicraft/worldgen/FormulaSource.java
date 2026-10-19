package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;

/**
 * Terrain from a formula, in a climate that is the same everywhere at sea level and cools 6.5 °C per kilometre above
 * it. The coarse map reads the formula at the centre of each coarse pixel; its scale is not the tile's, so the world
 * has no rivers.
 */
public final class FormulaSource implements TerrainSource {
	private static final float LAPSE = -0.0065f;
	private static final int COARSE_SCALE = 4;

	/** Elevation in meters at the centre of native pixel {@code (i, j)}. */
	public interface Elevation {
		float at(double i, double j);
	}

	private final Elevation elevation;
	private final float seaTemperature;
	private final float precipitation;

	public FormulaSource(Elevation elevation, float seaTemperature, float precipitation) {
		this.elevation = elevation;
		this.seaTemperature = seaTemperature;
		this.precipitation = precipitation;
	}

	/** A temperate, humid climate: 15 °C at sea level and 1,000 mm of rain. */
	public FormulaSource(Elevation elevation) {
		this(elevation, 15f, 1000f);
	}

	@Override
	public TerrainSample sample(int i0, int j0, int i1, int j1) {
		int h = i1 - i0, w = j1 - j0, plane = h * w;
		float[] elevations = new float[plane];
		float[] climate = new float[TerrainSample.CLIMATE_CHANNELS * plane];
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				int px = r * w + c;
				float e = elevation.at(i0 + r, j0 + c);
				elevations[px] = e;
				climate[TerrainSample.TEMPERATURE * plane + px] = seaTemperature + LAPSE * Math.max(0f, e);
				climate[TerrainSample.TEMPERATURE_SEASONALITY * plane + px] = 600f;
				climate[TerrainSample.PRECIPITATION * plane + px] = precipitation;
				climate[TerrainSample.PRECIPITATION_SEASONALITY * plane + px] = 30f;
				climate[TerrainSample.LAPSE_RATE * plane + px] = LAPSE;
			}
		}
		return new TerrainSample(i0, j0, h, w, elevations, climate);
	}

	@Override
	public int coarseScale() {
		return COARSE_SCALE;
	}

	@Override
	public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
		int w = cj1 - cj0, plane = (ci1 - ci0) * w;
		float[] out = new float[COARSE_CHANNELS * plane];
		for (int r = 0; r < ci1 - ci0; r++) {
			for (int c = 0; c < w; c++) {
				int px = r * w + c;
				float e = elevation.at((ci0 + r + 0.5) * COARSE_SCALE - 0.5, (cj0 + c + 0.5) * COARSE_SCALE - 0.5);
				out[COARSE_ELEVATION * plane + px] = e;
				out[COARSE_LOW_ELEVATION * plane + px] = e;
				out[COARSE_TEMPERATURE * plane + px] = seaTemperature + LAPSE * Math.max(0f, e);
				out[COARSE_TEMPERATURE_SEASONALITY * plane + px] = 600f;
				out[COARSE_PRECIPITATION * plane + px] = precipitation;
				out[COARSE_PRECIPITATION_SEASONALITY * plane + px] = 30f;
			}
		}
		return out;
	}
}
