package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;

/**
 * Mountains taller and steeper than the real ones. Diffusicraft is inspired by real terrain, not a simulation of it,
 * and the model's mountains rise as gently as real ones do. This wraps the model's {@link TerrainSource} and raises
 * the ground above its valley floors, so the ranges stand higher over their valleys without lifting the valleys, the
 * plateaus or the plains.
 *
 * <p>Ground {@code h} meters above its valley floor rises by {@link #lift (GAIN − 1)(h − SOFT (1 − e^(−h / SOFT)))}:
 * nothing at the floor, a few meters on low hills, and on the mountains {@code GAIN − 1} times their height above the
 * floor, less {@value #SOFT} m: a peak 2 km over its valleys rises 1 km more. The lift grows with the height, never
 * faster than {@code GAIN − 1} times it and not at all at the floor, so the floors, and the rivers and lakes on them,
 * keep their levels. The valley floor is the coarse map's 5th percentile of the elevation, or sea level where that is
 * lower, interpolated bilinearly between the centres of the coarse pixels. It changes slowly, but where it falls away
 * beside high, level ground, as beside a gorge through a plateau, the lift tilts that ground up towards the drop: a
 * river there keeps its course if it falls at least three-eighths as fast as the floor. Ground below the sea stays
 * where it is.
 *
 * <p>Raised ground is colder by the local lapse rate. On the coarse map, the pixel's mean rises as ground that high
 * above its own 5th percentile would, and its temperature falls by {@value #COARSE_LAPSE} °C a meter. The 5th
 * percentile stays where it is, so the rivers, which route over it, run where they ran.
 *
 * <p>This runs first, before {@link Glaciation} cuts the fjords, so the ice reads the mountains as the world has them.
 */
public final class Uplift implements TerrainSource {
	/** How many times higher than the model has them mountains stand over their valley floors, at most. */
	static final float GAIN = 1.6f;
	/**
	 * Height above the valley floor over which the lift grows in, in meters: hills this high rise by
	 * {@code 0.37 (GAIN − 1)} of their height.
	 */
	static final float SOFT = 300f;
	/** The lapse rate that cools the raised mean of a coarse pixel, in °C per meter. */
	static final float COARSE_LAPSE = -0.0065f;

	private final TerrainSource source;

	public Uplift(TerrainSource source) {
		this.source = source;
	}

	/** How far ground {@code above} meters above its valley floor rises, in meters. */
	static float lift(float above) {
		if (above <= 0f) {
			return 0f;
		}
		return (GAIN - 1f) * (above - SOFT * (float) -Math.expm1(-above / SOFT));
	}

	/** Ground at {@code elevation} meters after the lift, where the valley floor lies at {@code floor} meters. */
	public static float raise(float elevation, float floor) {
		if (elevation <= 0f) {
			return elevation;
		}
		return elevation + lift(elevation - Math.max(0f, floor));
	}

	@Override
	public TerrainSample sample(int i0, int j0, int i1, int j1) {
		TerrainSample sample = source.sample(i0, j0, i1, j1);
		// The coarse pixels whose centres surround every pixel centre of the window.
		int scale = source.coarseScale();
		int ci0 = Math.floorDiv(i0, scale) - 1, cj0 = Math.floorDiv(j0, scale) - 1;
		int ci1 = Math.floorDiv(i1 - 1, scale) + 2, cj1 = Math.floorDiv(j1 - 1, scale) + 2;
		int width = cj1 - cj0;
		float[] map = source.coarse(ci0, cj0, ci1, cj1);
		int floors = (ci1 - ci0) * width;

		int h = i1 - i0, w = j1 - j0, plane = h * w;
		// Each pixel's place between the coarse centres depends on where it is alone, so windows agree to the bit.
		int[] columns = new int[w];
		float[] across = new float[w];
		for (int c = 0; c < w; c++) {
			double v = (j0 + c + 0.5) / scale - 0.5;
			int cv = (int) Math.floor(v);
			columns[c] = cv - cj0;
			across[c] = (float) (v - cv);
		}
		float[] elevation = sample.elevation().clone();
		float[] climate = sample.climate() == null ? null : sample.climate().clone();
		boolean raised = false;
		for (int r = 0; r < h; r++) {
			double u = (i0 + r + 0.5) / scale - 0.5;
			int cu = (int) Math.floor(u);
			float down = (float) (u - cu);
			int row = (cu - ci0) * width;
			for (int c = 0; c < w; c++) {
				int px = r * w + c;
				float e = elevation[px];
				if (e <= 0f) {
					continue;
				}
				int k = floors + row + columns[c];
				float top = lerp(map[k], map[k + 1], across[c]);
				float bottom = lerp(map[k + width], map[k + width + 1], across[c]);
				float lifted = raise(e, lerp(top, bottom, down));
				if (lifted == e) {
					continue;
				}
				elevation[px] = lifted;
				raised = true;
				if (climate != null) {
					// The lapse rate is negative: raised ground is colder.
					climate[TerrainSample.TEMPERATURE * plane + px] +=
						climate[TerrainSample.LAPSE_RATE * plane + px] * (lifted - e);
				}
			}
		}
		return raised ? new TerrainSample(i0, j0, h, w, elevation, climate) : sample;
	}

	@Override
	public int coarseScale() {
		return source.coarseScale();
	}

	@Override
	public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
		float[] map = source.coarse(ci0, cj0, ci1, cj1).clone();
		int plane = (ci1 - ci0) * (cj1 - cj0);
		for (int px = 0; px < plane; px++) {
			float mean = map[COARSE_ELEVATION * plane + px];
			float lifted = raise(mean, map[COARSE_LOW_ELEVATION * plane + px]);
			map[COARSE_ELEVATION * plane + px] = lifted;
			map[COARSE_TEMPERATURE * plane + px] += COARSE_LAPSE * (lifted - mean);
		}
		return map;
	}

	@Override
	public SpawnKind spawnKind() {
		return source.spawnKind();
	}

	@Override
	public void close() {
		source.close();
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
