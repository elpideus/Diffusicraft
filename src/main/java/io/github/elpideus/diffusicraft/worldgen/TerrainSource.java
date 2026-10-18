package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;

/**
 * Where a {@link TerrainField} gets its data: the Terrain Diffusion pipeline in the game, or an analytic source in
 * tests. Implementations must be thread-safe and deterministic.
 */
public interface TerrainSource extends AutoCloseable {
	/** Coarse channels, in the coarse model's order. */
	int COARSE_ELEVATION = 0;
	/** The 5th percentile of the elevation inside the coarse pixel, in meters. */
	int COARSE_LOW_ELEVATION = 1;
	int COARSE_TEMPERATURE = 2;
	int COARSE_TEMPERATURE_SEASONALITY = 3;
	int COARSE_PRECIPITATION = 4;
	int COARSE_PRECIPITATION_SEASONALITY = 5;
	int COARSE_CHANNELS = 6;

	/** Elevation and climate over native pixels {@code [i0, i1) x [j0, j1)}, as {@link TerrainSample} describes. */
	TerrainSample sample(int i0, int j0, int i1, int j1);

	/** Native pixels per coarse pixel. */
	int coarseScale();

	/**
	 * The coarse map over the coarse pixels {@code [ci0, ci1) x [cj0, cj1)}: {@link #COARSE_CHANNELS} row-major planes.
	 * Elevations are in meters; the climate channels have {@link TerrainSample}'s units, the temperature being the
	 * one at the pixel's mean elevation. Much cheaper than {@link #sample}: it needs no full-resolution inference.
	 */
	float[] coarse(int ci0, int cj0, int ci1, int cj1);

	@Override
	default void close() {
	}
}
