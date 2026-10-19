package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.jspecify.annotations.Nullable;

/**
 * Glacial overdeepening. In the ice ages, glaciers flowing off cold mountain coasts cut their valleys far below the
 * sea, which flooded them when the ice melted: the fjords of Norway, Patagonia, Alaska and New Zealand. The model draws
 * those coasts smooth, without inlets, so this wraps its {@link TerrainSource} and lowers the valleys after the fact.
 *
 * <p>Ground at elevation {@code e} sinks to {@link #deepen e − DEPTH × g × w(e)}, where {@code g} in [0, 1] is how
 * glaciated the place is. The weight {@code w} is 1 from sea level up to {@value #FLOOR} m, fades to nothing at
 * {@value #SHOULDER} m and fades below the sea too, to nothing {@value #SEA_FADE} m down. The map keeps every height's
 * order, so it makes no new hollow for the rivers to fill: the coastline moves up to about the old 230 m contour, the
 * valleys below it drown, and the slopes just above them steepen into the fjord's walls.
 *
 * <p>{@code g} is the product of two things. The coast must be mountainous, within a coarse pixel of one that rises
 * {@value #RUGGED_LOW} to {@value #RUGGED_HIGH} m above its lowest ground or the sea, and the sea must be near: within
 * {@value #SEA_NEAR} coarse pixels, fading out at {@value #SEA_REACH} (15 and 31 km in the game). Those come from the
 * source's coarse map ({@link #reach}). And it must be cold: the annual mean at sea level below {@value #COLD} °C,
 * fading out at {@value #MILD} °C. The fine samples and the coarse map are deepened alike, so the coastline, the rivers,
 * the lakes and the distant terrain all see the same fjords. Lowered ground is warmer by the local lapse rate.
 */
public final class Glaciation implements TerrainSource {
	/** How far the ice lowered the valley floors, in meters. */
	public static final float DEPTH = 400f;
	/** Ground up to this elevation is lowered by the full depth, in meters. */
	static final float FLOOR = 100f;
	/** Ground above this elevation is left alone, in meters: the shoulders above the glacier. */
	static final float SHOULDER = 400f;
	/** The sea floor this far down is left alone, in meters. Over 1.5 × {@link #DEPTH}, so heights keep their order. */
	static final float SEA_FADE = 1200f;
	/** Annual mean at sea level below which the coast is fully glaciated, °C. */
	static final float COLD = 8f;
	/** Annual mean at sea level above which no glacier reached the sea, °C. */
	static final float MILD = 12f;
	/** A coarse pixel this far above its 5th percentile, or the sea, in meters, begins to count as mountains. */
	static final float RUGGED_LOW = 150f;
	/** A coarse pixel this far above its 5th percentile, or the sea, in meters, counts fully as mountains. */
	static final float RUGGED_HIGH = 450f;
	/** Coarse pixels from the sea within which the ice reaches the coast fully. */
	static final float SEA_NEAR = 2f;
	/** Coarse pixels from the sea beyond which the ice does not reach it. */
	static final int SEA_REACH = 4;
	/** °C per meter by which the coarse map's temperatures are reduced to sea level: the standard atmosphere's. */
	static final float STANDARD_LAPSE = 0.0065f;
	/** The plane of {@link #coarseWithGlaciation} that holds {@code g}, after the coarse channels. */
	static final int COARSE_GLACIATION = COARSE_CHANNELS;

	private final TerrainSource source;

	public Glaciation(TerrainSource source) {
		this.source = source;
	}

	/** Ground at {@code elevation}, in meters, after ice of strength {@code glaciation} lowered it. */
	public static float deepen(float elevation, float glaciation) {
		float weight = elevation >= 0f
			? 1f - smoothstep(FLOOR, SHOULDER, elevation)
			: smoothstep(-SEA_FADE, 0f, elevation);
		return elevation - DEPTH * glaciation * weight;
	}

	/**
	 * Whether ground now at {@code elevation} below the sea was land before ice of strength {@code glaciation} lowered
	 * it: the floor of a drowned valley. {@link #deepen} keeps heights in order, so that is ground no lower than where
	 * it took sea level.
	 */
	public static boolean drowned(float elevation, float glaciation) {
		return elevation < 0f && elevation >= -DEPTH * glaciation;
	}

	@Override
	public TerrainSample sample(int i0, int j0, int i1, int j1) {
		return sample(i0, j0, i1, j1, null);
	}

	/**
	 * {@link #sample}, also writing how glaciated each pixel is to {@code glaciation}, row-major, if it is not null.
	 * Pixels the ice never reached are left as they are.
	 */
	TerrainSample sample(int i0, int j0, int i1, int j1, float @Nullable [] glaciation) {
		TerrainSample sample = source.sample(i0, j0, i1, j1);
		if (sample.climate() == null) {
			return sample;
		}
		// The coarse pixels whose centres surround every pixel centre of the window.
		int scale = source.coarseScale();
		int ci0 = Math.floorDiv(i0, scale) - 1, cj0 = Math.floorDiv(j0, scale) - 1;
		int ci1 = Math.floorDiv(i1 - 1, scale) + 2, cj1 = Math.floorDiv(j1 - 1, scale) + 2;
		int width = cj1 - cj0;
		float[] reach = reach(source.coarse(ci0 - SEA_REACH, cj0 - SEA_REACH, ci1 + SEA_REACH, cj1 + SEA_REACH),
			ci1 - ci0, width);
		if (reach == null) {
			return sample;
		}

		int h = i1 - i0, w = j1 - j0, plane = h * w;
		float[] elevation = sample.elevation().clone();
		float[] climate = sample.climate().clone();
		int[] columns = new int[w];
		float[] across = new float[w];
		for (int c = 0; c < w; c++) {
			double v = (j0 + c + 0.5) / scale - 0.5 - cj0;
			columns[c] = (int) Math.floor(v);
			across[c] = (float) (v - columns[c]);
		}
		for (int r = 0; r < h; r++) {
			double u = (i0 + r + 0.5) / scale - 0.5 - ci0;
			int a = (int) Math.floor(u);
			float down = (float) (u - a);
			for (int c = 0; c < w; c++) {
				int k = a * width + columns[c];
				float fv = across[c];
				float top = lerp(reach[k], reach[k + 1], fv);
				float bottom = lerp(reach[k + width], reach[k + width + 1], fv);
				float near = lerp(top, bottom, down);
				if (near <= 0f) {
					continue;
				}
				int px = r * w + c;
				float e = elevation[px];
				float lapse = climate[TerrainSample.LAPSE_RATE * plane + px];
				float temperature = climate[TerrainSample.TEMPERATURE * plane + px];
				float g = near * cold(temperature - lapse * Math.max(0f, e));
				if (g <= 0f) {
					continue;
				}
				float deepened = deepen(e, g);
				elevation[px] = deepened;
				// The lapse rate is negative: lowered ground is warmer.
				climate[TerrainSample.TEMPERATURE * plane + px] =
					temperature + lapse * (Math.max(0f, deepened) - Math.max(0f, e));
				if (glaciation != null) {
					glaciation[px] = g;
				}
			}
		}
		return new TerrainSample(i0, j0, h, w, elevation, climate);
	}

	@Override
	public int coarseScale() {
		return source.coarseScale();
	}

	@Override
	public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
		return coarse(ci0, cj0, ci1, cj1, false);
	}

	/**
	 * {@link #coarse}, followed by one more plane, {@link #COARSE_GLACIATION}: how glaciated each coarse pixel is at
	 * its centre.
	 */
	float[] coarseWithGlaciation(int ci0, int cj0, int ci1, int cj1) {
		return coarse(ci0, cj0, ci1, cj1, true);
	}

	private float[] coarse(int ci0, int cj0, int ci1, int cj1, boolean withGlaciation) {
		int m = SEA_REACH, h = ci1 - ci0, w = cj1 - cj0, plane = h * w;
		int outerWidth = w + 2 * m, outerPlane = (h + 2 * m) * outerWidth;
		float[] map = source.coarse(ci0 - m, cj0 - m, ci1 + m, cj1 + m);
		float[] out = new float[(withGlaciation ? COARSE_CHANNELS + 1 : COARSE_CHANNELS) * plane];
		for (int ch = 0; ch < COARSE_CHANNELS; ch++) {
			for (int r = 0; r < h; r++) {
				System.arraycopy(map, ch * outerPlane + (r + m) * outerWidth + m, out, ch * plane + r * w, w);
			}
		}
		float[] reach = reach(map, h, w);
		if (reach == null) {
			return out;
		}
		for (int px = 0; px < plane; px++) {
			if (reach[px] <= 0f) {
				continue;
			}
			float mean = out[COARSE_ELEVATION * plane + px];
			float temperature = out[COARSE_TEMPERATURE * plane + px];
			float g = reach[px] * cold(temperature + STANDARD_LAPSE * Math.max(0f, mean));
			if (g <= 0f) {
				continue;
			}
			float deepened = deepen(mean, g);
			out[COARSE_ELEVATION * plane + px] = deepened;
			out[COARSE_LOW_ELEVATION * plane + px] = deepen(out[COARSE_LOW_ELEVATION * plane + px], g);
			out[COARSE_TEMPERATURE * plane + px] =
				temperature + STANDARD_LAPSE * (Math.max(0f, mean) - Math.max(0f, deepened));
			if (withGlaciation) {
				out[COARSE_GLACIATION * plane + px] = g;
			}
		}
		return out;
	}

	@Override
	public void close() {
		source.close();
	}

	/**
	 * How far the ice reaches each pixel of an {@code height × width} window of the coarse map, before the cold: how
	 * mountainous the coast is, times how near the sea. {@code map} is the source's coarse map of the window with a
	 * margin of {@value #SEA_REACH} pixels all round. Row-major, or {@code null} if the ice reaches none of them.
	 */
	static float @Nullable [] reach(float[] map, int height, int width) {
		int m = SEA_REACH, outerWidth = width + 2 * m, outerPlane = (height + 2 * m) * outerWidth;
		float[] rugged = new float[outerPlane];
		for (int px = 0; px < outerPlane; px++) {
			rugged[px] = map[COARSE_ELEVATION * outerPlane + px]
				- Math.max(0f, map[COARSE_LOW_ELEVATION * outerPlane + px]);
		}
		float[] reach = null;
		for (int r = 0; r < height; r++) {
			for (int c = 0; c < width; c++) {
				int centre = (r + m) * outerWidth + c + m;
				float most = Float.NEGATIVE_INFINITY;
				for (int a = -1; a <= 1; a++) {
					for (int b = -1; b <= 1; b++) {
						most = Math.max(most, rugged[centre + a * outerWidth + b]);
					}
				}
				float mountains = smoothstep(RUGGED_LOW, RUGGED_HIGH, most);
				if (mountains <= 0f) {
					continue;
				}
				int nearest = Integer.MAX_VALUE;
				for (int a = -m; a <= m; a++) {
					for (int b = -m; b <= m; b++) {
						if (map[COARSE_ELEVATION * outerPlane + centre + a * outerWidth + b] < 0f) {
							nearest = Math.min(nearest, a * a + b * b);
						}
					}
				}
				if (nearest == Integer.MAX_VALUE) {
					continue;
				}
				float near = 1f - smoothstep(SEA_NEAR, SEA_REACH, (float) Math.sqrt(nearest));
				if (near > 0f) {
					if (reach == null) {
						reach = new float[height * width];
					}
					reach[r * width + c] = mountains * near;
				}
			}
		}
		return reach;
	}

	/** How cold a coast with this annual mean at sea level is, in [0, 1]: 1 where glaciers reached the sea. */
	static float cold(float seaTemperature) {
		return 1f - smoothstep(COLD, MILD, seaTemperature);
	}

	static float smoothstep(float from, float to, float x) {
		float t = Math.clamp((x - from) / (to - from), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
