package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;
import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Glacial overdeepening. In the ice ages, glaciers flowing off cold mountain coasts cut their valleys far below the
 * sea, which flooded them when the ice melted: the fjords of Norway, Patagonia, Alaska and New Zealand. The model draws
 * those coasts smooth, without inlets, so this wraps its {@link TerrainSource} and cuts the valleys after the fact.
 *
 * <p>The ice cut the floors of the valleys down to a trough whose floor lies {@value #DEPTH} m under the sea at the
 * coast and climbs to sea level {@value #LENGTH} coarse pixels inland (92 km in the game). The valley floor is the
 * coarse map's 5th percentile of the elevation, or sea level if that is lower; it counts only as far as the coarse
 * pixel is mountainous or lies in a broad valley, whose sides rise on two opposite neighbours, since the lowest ground
 * of a plateau is no glacier's floor. Ground at elevation {@code e} sinks to {@link #deepen e − cut × w(e)}, where the
 * cut is how far the ice lowered the valley floor: from the floor down to the trough, up to {@value #FULL_CUT} m and
 * fading out by {@value #MAX_CUT} m, so high valleys keep their floors ({@link #cut}), times how glaciated the place
 * is. In the mountains the weight {@code w} is 1 up to {@value #FLOOR} m above the valley floor and fades to nothing
 * {@value #SHOULDER} m above it, so the ice follows the valleys of the full terrain, side valleys too, and leaves the
 * ridges between them. On plains, where the ice spread out rather than cutting in, it fades from 1 at the floor to
 * nothing {@value #PLAIN_SHOULDER} m above it, and in between as far as the coarse pixel is mountainous: the coastal
 * lowland and the plains at the foot of the mountains keep their ground. Below the sea it fades out at
 * {@value #SEA_FADE} times the cut down. The map keeps every height's order, so a valley floor that was land stays
 * above one that was sea, and the fjord biome can tell which water was land before ({@link #drowned}).
 *
 * <p>How glaciated a place is, in [0, 1], is the product of three things. The land must be mountainous, within a
 * coarse pixel of one that rises {@value #RUGGED_LOW} to {@value #RUGGED_HIGH} m above its lowest ground or the sea.
 * The sea must be near: the ice reaches fully {@value #SEA_NEAR} coarse pixels inland and not beyond
 * {@value #SEA_REACH} (61 and 84 km in the game), so the fjords end where their troughs no longer reach under the sea.
 * Those come from the source's coarse map ({@link #ice}). And it must be cold: the annual mean at sea level below
 * {@value #COLD} °C, fading out at {@value #MILD} °C. The coarse map reduces its temperatures to sea level as the fine
 * samples do ({@link #seaLevel}), so both find the same coasts cold. The fine samples interpolate how glaciated they
 * are, the valley floor and how mountainous the land is between the centres of the coarse pixels; the coarse map
 * lowers each pixel's mean and 5th percentile by the mean of what the samples do at {@value #POINTS} ×
 * {@value #POINTS} points across it, so a valley cut through a strip of the pixel sinks its floor as far as the
 * samples' and the rivers find the same coasts. Lowered ground is warmer by the local lapse rate.
 *
 * <p>That drowns the valleys as far as the coarse map's valley floors lie under the trough, some 20 km inland. The
 * longest fjords, which the great outlet glaciers cut a hundred kilometres and more inland, are the {@link Fjords}:
 * troughs traced along the main valleys and their arms up the side valleys, each cut as a whole, so their water
 * always reaches the sea. The fine samples sink into them where they lie lower. In the coarse map, the pixels they
 * cross keep {@code 1 −} {@value #FJORD_COVER} of their height above the trough's floor, and their 5th percentile
 * sinks to it.
 */
public final class Glaciation implements TerrainSource {
	/** How far under the sea the ice cut the valley floors at the coast, in meters. */
	public static final float DEPTH = 400f;
	/** Coarse pixels inland over which the floor of the trough climbs from {@link #DEPTH} under the sea to sea level. */
	static final float LENGTH = 12f;
	/** The ice cut valley floors down to the trough by up to this much, in meters: walls most of a kilometre high. */
	static final float FULL_CUT = 700f;
	/**
	 * Valley floors this far above the trough or more, in meters, were not cut at all: high valleys far from any
	 * fjord. Between {@link #FULL_CUT} and this, the cut fades out.
	 */
	static final float MAX_CUT = 1000f;
	/** Ground up to this height above the valley floor is lowered by the full cut, in meters. */
	static final float FLOOR = 100f;
	/** Ground this high above the valley floor is left alone, in meters: the shoulders above the glacier. */
	static final float SHOULDER = 400f;
	/** On plains, ground this high above the floor is left alone, in meters: the ice spread over them. */
	static final float PLAIN_SHOULDER = 30f;
	/** The sea floor this many times the cut down is left alone. Over 1.5, so heights keep their order. */
	static final float SEA_FADE = 3f;
	/** Annual mean at sea level below which the coast is fully glaciated, °C. */
	static final float COLD = 8f;
	/** Annual mean at sea level above which no glacier reached the sea, °C. */
	static final float MILD = 12f;
	/** A coarse pixel this far above its 5th percentile, or the sea, in meters, begins to count as mountains. */
	static final float RUGGED_LOW = 150f;
	/** A coarse pixel this far above its 5th percentile, or the sea, in meters, counts fully as mountains. */
	static final float RUGGED_HIGH = 450f;
	/** Coarse pixels from the sea within which the ice reaches fully. */
	static final float SEA_NEAR = 8f;
	/** Coarse pixels from the sea beyond which the ice does not reach. */
	static final int SEA_REACH = 11;
	/**
	 * The coarse map is deepened as the fine samples are at this many points across each coarse pixel, and as many
	 * down it.
	 */
	static final int POINTS = 4;
	/** The plane of {@link #coarseWithIceCut} that holds the cut, after the coarse channels. */
	static final int COARSE_ICE_CUT = COARSE_CHANNELS;

	/** One neighbour on each of the four axes through a coarse pixel, as (row, column) steps. */
	private static final int[][] AXES = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

	/**
	 * The share of a coarse pixel that a fjord's trough covers, by which it lowers the pixel's mean towards the
	 * trough's floor on the coarse map.
	 */
	static final float FJORD_COVER = 0.2f;

	private final TerrainSource source;
	private final Fjords fjords;

	/** The source's terrain after the ice, whose fjords depend on {@code seed} as well as on the terrain. */
	public Glaciation(TerrainSource source, long seed) {
		this.source = source;
		this.fjords = new Fjords(source, seed);
	}

	public Glaciation(TerrainSource source) {
		this(source, 0L);
	}

	/**
	 * Ground at {@code elevation}, in meters, after the ice lowered the valley floor at {@code floor} (at least 0) by
	 * {@code cut}, in meters, where the land is {@code mountainous}: 1 in the mountains, where the ice cut into the
	 * valleys, 0 on plains, where it spread over them.
	 */
	public static float deepen(float elevation, float floor, float cut, float mountainous) {
		if (cut <= 0f) {
			return elevation;
		}
		float full = FLOOR * mountainous, none = PLAIN_SHOULDER + (SHOULDER - PLAIN_SHOULDER) * mountainous;
		float weight = elevation >= 0f
			? 1f - smoothstep(floor + full, floor + none, elevation)
			: smoothstep(-SEA_FADE * cut, 0f, elevation);
		return elevation - cut * weight;
	}

	/**
	 * Whether ground now at {@code elevation} below the sea was land before the ice lowered it, where it lowered ground
	 * at sea level by {@code cut}: the floor of a drowned valley. {@link #deepen} keeps heights in order, so that is
	 * ground no lower than where sea level went.
	 */
	public static boolean drowned(float elevation, float cut) {
		return elevation < 0f && elevation >= -cut;
	}

	/**
	 * How far the ice lowered a valley floor at {@code floor} meters (at least 0), {@code distance} coarse pixels
	 * from the sea, where it was fully glaciated: down to the trough, unless the floor lies too high above it.
	 */
	static float cut(float floor, float distance) {
		float above = Math.max(0f, floor + DEPTH * (1f - distance / LENGTH));
		return above * (1f - smoothstep(FULL_CUT, MAX_CUT, above));
	}

	@Override
	public TerrainSample sample(int i0, int j0, int i1, int j1) {
		return sample(i0, j0, i1, j1, null);
	}

	/**
	 * {@link #sample}, also writing how far the ice lowered ground at sea level, in meters, to {@code iceCut},
	 * row-major, if it is not null. Pixels the ice never reached are left as they are.
	 */
	TerrainSample sample(int i0, int j0, int i1, int j1, float @Nullable [] iceCut) {
		TerrainSample sample = source.sample(i0, j0, i1, j1);
		if (sample.climate() == null) {
			return sample;
		}
		// The coarse pixels whose centres surround every pixel centre of the window.
		int scale = source.coarseScale();
		int ci0 = Math.floorDiv(i0, scale) - 1, cj0 = Math.floorDiv(j0, scale) - 1;
		int ci1 = Math.floorDiv(i1 - 1, scale) + 2, cj1 = Math.floorDiv(j1 - 1, scale) + 2;
		int width = cj1 - cj0, coarsePlane = (ci1 - ci0) * width;
		float[] ice = ice(source.coarse(ci0 - SEA_REACH, cj0 - SEA_REACH, ci1 + SEA_REACH, cj1 + SEA_REACH),
			ci1 - ci0, width);
		List<Fjords.Trough> troughs = fjords.near((double) i0 / scale, (double) j0 / scale, (double) i1 / scale,
			(double) j1 / scale);
		if (ice == null && troughs.isEmpty()) {
			return sample;
		}

		int h = i1 - i0, w = j1 - j0;
		float[] elevation = sample.elevation().clone();
		float[] climate = sample.climate().clone();
		if (ice != null) {
			valleys(ice, i0, j0, h, w, ci0, cj0, coarsePlane, width, scale, elevation, climate, iceCut);
		}
		if (!troughs.isEmpty()) {
			fjords(fjords.carve(troughs, scale, i0, j0, h, w), h, w, elevation, climate, iceCut);
		}
		return new TerrainSample(i0, j0, h, w, elevation, climate);
	}

	/** Lowers the valleys of a window of the fine terrain where the coarse map's {@link #ice} reaches them. */
	private static void valleys(float[] ice, int i0, int j0, int h, int w, int ci0, int cj0, int coarsePlane,
								int width, int scale, float[] elevation, float[] climate, float @Nullable [] iceCut) {
		int plane = h * w;
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
				float reach = bilinear(ice, k, width, across[c], down);
				if (reach <= 0f) {
					continue;
				}
				int px = r * w + c;
				float e = elevation[px];
				float lapse = climate[TerrainSample.LAPSE_RATE * plane + px];
				float temperature = climate[TerrainSample.TEMPERATURE * plane + px];
				float cut = reach * cold(temperature - lapse * Math.max(0f, e));
				if (cut <= 0f) {
					continue;
				}
				float deepened = deepen(e, bilinear(ice, coarsePlane + k, width, across[c], down), cut,
					bilinear(ice, 2 * coarsePlane + k, width, across[c], down));
				elevation[px] = deepened;
				// The lapse rate is negative: lowered ground is warmer.
				climate[TerrainSample.TEMPERATURE * plane + px] =
					temperature + lapse * (Math.max(0f, deepened) - Math.max(0f, e));
				if (iceCut != null) {
					iceCut[px] = cut;
				}
			}
		}
	}

	/** Sinks a window of the fine terrain into the long fjords' troughs where they lie below it. */
	private static void fjords(float[] carving, int h, int w, float[] elevation, float[] climate,
							   float @Nullable [] iceCut) {
		int plane = h * w;
		for (int px = 0; px < plane; px++) {
			float beyond = carving[Fjords.BEYOND * plane + px];
			if (beyond == Float.POSITIVE_INFINITY) {
				continue;
			}
			float e = elevation[px], floor = carving[Fjords.FLOOR * plane + px];
			float trough = floor + Fjords.wall(Fjords.rough(beyond, carving[Fjords.ROUGH * plane + px]));
			if (trough >= e + Fjords.SMOOTH) {
				continue;
			}
			float carved = Fjords.smoothMin(e, trough);
			elevation[px] = carved;
			// The lapse rate is negative: lowered ground is warmer.
			climate[TerrainSample.TEMPERATURE * plane + px] += climate[TerrainSample.LAPSE_RATE * plane + px]
				* (Math.max(0f, carved) - Math.max(0f, e));
			if (iceCut != null) {
				// The whole trough was land, down to its floor: a fjord. The walls carry the floor's cut too, so it
				// holds where the cut is interpolated between them.
				iceCut[px] = Math.max(iceCut[px], Fjords.cut(floor));
			}
		}
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
	 * {@link #coarse}, followed by one more plane, {@link #COARSE_ICE_CUT}: how far the ice lowered ground at sea
	 * level at the centre of each coarse pixel, in meters.
	 */
	float[] coarseWithIceCut(int ci0, int cj0, int ci1, int cj1) {
		return coarse(ci0, cj0, ci1, cj1, true);
	}

	private float[] coarse(int ci0, int cj0, int ci1, int cj1, boolean withIceCut) {
		int m = SEA_REACH, h = ci1 - ci0, w = cj1 - cj0, plane = h * w;
		// The ice and the climate reach one pixel further, for the samples interpolate them between pixel centres.
		int iceWidth = w + 2, icePlane = (h + 2) * iceWidth;
		int outerWidth = w + 2 * m + 2, outerPlane = (h + 2 * m + 2) * outerWidth;
		float[] map = source.coarse(ci0 - m - 1, cj0 - m - 1, ci1 + m + 1, cj1 + m + 1);
		float[] out = new float[(withIceCut ? COARSE_CHANNELS + 1 : COARSE_CHANNELS) * plane];
		for (int ch = 0; ch < COARSE_CHANNELS; ch++) {
			for (int r = 0; r < h; r++) {
				System.arraycopy(map, ch * outerPlane + (r + m + 1) * outerWidth + m + 1, out, ch * plane + r * w, w);
			}
		}
		float[] ice = ice(map, h + 2, w + 2);
		float[] climate = null;
		for (int px = 0; ice != null && px < plane; px++) {
			int k = (px / w + 1) * iceWidth + px % w + 1;
			if (!reached(ice, k, iceWidth)) {
				continue;
			}
			if (climate == null) {
				climate = seaLevel(map, h + 2, w + 2, m);
			}
			float mean = out[COARSE_ELEVATION * plane + px];
			float deepened = deepenPixel(ice, climate, k, iceWidth, icePlane, mean);
			out[COARSE_ELEVATION * plane + px] = deepened;
			out[COARSE_LOW_ELEVATION * plane + px] = deepenPixel(ice, climate, k, iceWidth, icePlane,
				out[COARSE_LOW_ELEVATION * plane + px]);
			// The lapse rate is negative: lowered ground is warmer.
			out[COARSE_TEMPERATURE * plane + px] +=
				climate[icePlane + k] * (Math.max(0f, deepened) - Math.max(0f, mean));
			if (withIceCut) {
				out[COARSE_ICE_CUT * plane + px] = Math.max(0f, ice[k] * cold(climate[k]));
			}
		}

		// The fjords: the lowest floor of a trough in each coarse pixel it crosses.
		List<Fjords.Trough> troughs = fjords.near(ci0, cj0, ci1, cj1);
		if (troughs.isEmpty()) {
			return out;
		}
		float[] deepest = new float[plane];
		Arrays.fill(deepest, Float.POSITIVE_INFINITY);
		for (Fjords.Trough trough : troughs) {
			for (int k = 0; k < trough.rows().length; k++) {
				int r = (int) Math.floor(trough.rows()[k]) - ci0, c = (int) Math.floor(trough.columns()[k]) - cj0;
				if (r >= 0 && r < h && c >= 0 && c < w) {
					deepest[r * w + c] = Math.min(deepest[r * w + c], trough.floor()[k]);
				}
			}
		}
		for (int px = 0; px < plane; px++) {
			float floor = deepest[px], mean = out[COARSE_ELEVATION * plane + px];
			if (floor == Float.POSITIVE_INFINITY || mean < 0f) {
				continue;
			}
			if (climate == null) {
				climate = seaLevel(map, h + 2, w + 2, m);
			}
			float lowered = mean - FJORD_COVER * Math.max(0f, mean - floor);
			out[COARSE_ELEVATION * plane + px] = lowered;
			out[COARSE_LOW_ELEVATION * plane + px] = Math.min(out[COARSE_LOW_ELEVATION * plane + px], floor);
			out[COARSE_TEMPERATURE * plane + px] += climate[icePlane + (px / w + 1) * iceWidth + px % w + 1]
				* (Math.max(0f, lowered) - Math.max(0f, mean));
			if (withIceCut) {
				out[COARSE_ICE_CUT * plane + px] = Math.max(out[COARSE_ICE_CUT * plane + px], Fjords.cut(floor));
			}
		}
		return out;
	}

	/** Whether the ice reaches the pixel at {@code k} of a window {@code width} wide or one of its eight neighbours. */
	private static boolean reached(float[] ice, int k, int width) {
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (ice[k + a * width + b] > 0f) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Ground at {@code elevation} in the coarse pixel at {@code k} of a window {@code width} wide and {@code plane} in
	 * all, after the ice: the mean of what the fine samples make of ground that high ({@link #valleys}) at
	 * {@value #POINTS} × {@value #POINTS} points spread evenly over the pixel, where they interpolate the
	 * {@link #ice} and the {@link #seaLevel} climate of the window between the centres of its pixels. The pixel must
	 * not lie on the window's sides.
	 */
	private static float deepenPixel(float[] ice, float[] climate, int k, int width, int plane, float elevation) {
		float sum = 0f;
		for (int u = 0; u < POINTS; u++) {
			float y = (u + 0.5f) / POINTS - 0.5f;
			int up = y < 0f ? 1 : 0;
			for (int v = 0; v < POINTS; v++) {
				float x = (v + 0.5f) / POINTS - 0.5f;
				int left = x < 0f ? 1 : 0;
				int q = k - up * width - left;
				float down = y + up, across = x + left;
				float reach = bilinear(ice, q, width, across, down);
				float cut = reach > 0f ? reach * cold(bilinear(climate, q, width, across, down)) : 0f;
				sum += deepen(elevation, bilinear(ice, plane + q, width, across, down), cut,
					bilinear(ice, 2 * plane + q, width, across, down));
			}
		}
		return sum / (POINTS * POINTS);
	}

	@Override
	public SpawnKind spawnKind() {
		return source.spawnKind();
	}

	@Override
	public void close() {
		source.close();
	}

	/**
	 * Where the ice reaches each pixel of an {@code height × width} window of the coarse map, before the cold. Three
	 * planes, row-major: how far it would lower the valley floor (how mountainous the coast is, times how near the
	 * sea, times {@link #cut}); the valley floor itself, which counts only as far as the pixel is mountainous or lies
	 * in a broad valley, its two neighbours on some axis rising {@value #RUGGED_LOW} to {@value #RUGGED_HIGH} m above
	 * its floor, since the lowest ground of a plateau is no glacier's floor; and how mountainous the pixel itself is.
	 * {@code map} is the source's coarse map of the window with a margin of {@value #SEA_REACH} pixels all round.
	 * {@code null} if the ice reaches none of them.
	 */
	static float @Nullable [] ice(float[] map, int height, int width) {
		int m = SEA_REACH, outerWidth = width + 2 * m, outerPlane = (height + 2 * m) * outerWidth;
		int plane = height * width;
		float[] rugged = new float[outerPlane];
		for (int px = 0; px < outerPlane; px++) {
			rugged[px] = map[COARSE_ELEVATION * outerPlane + px]
				- Math.max(0f, map[COARSE_LOW_ELEVATION * outerPlane + px]);
		}
		float[] ice = new float[3 * plane];
		boolean reached = false;
		for (int r = 0; r < height; r++) {
			for (int c = 0; c < width; c++) {
				int centre = (r + m) * outerWidth + c + m, px = r * width + c;
				float own = smoothstep(RUGGED_LOW, RUGGED_HIGH, rugged[centre]);
				float low = Math.max(0f, map[COARSE_LOW_ELEVATION * outerPlane + centre]);
				// A broad valley: the ground on two opposite sides rises above its floor.
				float sides = Float.NEGATIVE_INFINITY;
				for (int[] axis : AXES) {
					int a = axis[0] * outerWidth + axis[1];
					sides = Math.max(sides, Math.min(map[COARSE_ELEVATION * outerPlane + centre + a],
						map[COARSE_ELEVATION * outerPlane + centre - a]));
				}
				float floor = Math.max(own, smoothstep(RUGGED_LOW, RUGGED_HIGH, sides - low)) * low;
				ice[plane + px] = floor;
				ice[2 * plane + px] = own;
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
						if (a * a + b * b < nearest
							&& map[COARSE_ELEVATION * outerPlane + centre + a * outerWidth + b] < 0f) {
							nearest = a * a + b * b;
						}
					}
				}
				if (nearest == Integer.MAX_VALUE) {
					continue;
				}
				float distance = (float) Math.sqrt(nearest);
				float near = 1f - smoothstep(SEA_NEAR, SEA_REACH, distance);
				if (near > 0f) {
					ice[px] = mountains * near * cut(floor, distance);
					reached |= ice[px] > 0f;
				}
			}
		}
		return reached ? ice : null;
	}

	/**
	 * The climate at sea level over an {@code height × width} window of the coarse map, as the fine samples have it
	 * ({@link WorldPipeline#seaLevelClimate}): two planes, row-major, the annual mean at sea level in °C and the local
	 * lapse rate in °C per meter, at most 0. {@code map} is the source's coarse map of the window with a margin of
	 * {@code margin} pixels all round, at least {@link WorldPipeline#CLIMATE_RADIUS}.
	 */
	static float[] seaLevel(float[] map, int height, int width, int margin) {
		int r = WorldPipeline.CLIMATE_RADIUS, skip = margin - r;
		int outerWidth = width + 2 * margin;
		float[][][] around = temperatures(map, outerWidth, (height + 2 * margin) * outerWidth, skip, skip, height + 2 * r,
			width + 2 * r);
		float[][][] climate = WorldPipeline.seaLevelClimate(around[0], around[1]);
		int plane = height * width;
		float[] out = new float[2 * plane];
		for (int a = 0; a < height; a++) {
			System.arraycopy(climate[0][a], 0, out, a * width, width);
			System.arraycopy(climate[1][a], 0, out, plane + a * width, width);
		}
		return out;
	}

	/**
	 * The annual mean at sea level, in °C, at row {@code r} and column {@code c} of the source's coarse map {@code map}
	 * of a square {@code size} pixels across, as {@link #seaLevel} has it there: those lie at least
	 * {@link WorldPipeline#CLIMATE_RADIUS} pixels from its sides.
	 */
	static float seaTemperature(float[] map, int size, int r, int c) {
		int radius = WorldPipeline.CLIMATE_RADIUS, side = 2 * radius + 1;
		float[][][] around = temperatures(map, size, size * size, r - radius, c - radius, side, side);
		return WorldPipeline.seaLevelClimate(around[0], around[1])[0][0][0];
	}

	/**
	 * The temperature (in {@code [0]}) and the elevation over the sea (in {@code [1]}) of the {@code height × width}
	 * pixels from row {@code r0} and column {@code c0} of a coarse map {@code mapWidth} pixels wide and {@code plane} in
	 * all, as {@link WorldPipeline#seaLevelClimate} takes them.
	 */
	private static float[][][] temperatures(float[] map, int mapWidth, int plane, int r0, int c0, int height,
											int width) {
		float[][][] out = new float[2][height][width];
		for (int a = 0; a < height; a++) {
			for (int b = 0; b < width; b++) {
				int k = (r0 + a) * mapWidth + c0 + b;
				out[0][a][b] = map[COARSE_TEMPERATURE * plane + k];
				out[1][a][b] = Math.max(0f, map[COARSE_ELEVATION * plane + k]);
			}
		}
		return out;
	}

	/** How cold a coast with this annual mean at sea level is, in [0, 1]: 1 where glaciers reached the sea. */
	static float cold(float seaTemperature) {
		return 1f - smoothstep(COLD, MILD, seaTemperature);
	}

	static float smoothstep(float from, float to, float x) {
		float t = Math.clamp((x - from) / (to - from), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	/** The value between {@code values[k]}, the next one and the two below them, in rows of {@code width}. */
	private static float bilinear(float[] values, int k, int width, float across, float down) {
		float top = lerp(values[k], values[k + 1], across);
		float bottom = lerp(values[k + width], values[k + width + 1], across);
		return lerp(top, bottom, down);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
