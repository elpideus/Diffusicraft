package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.FastNoiseLite;
import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Terrain Diffusion output for one seed, resampled for chunk generation.
 *
 * <p>The source is read through {@link Glaciation}, which drowns the valleys of cold mountain coasts into fjords.
 * <p>The source is read in tiles of {@value #TILE}² native pixels (30 m each) with a {@value #PAD}-pixel margin.
 * For every tile we keep the padded elevation, sampled with Catmull-Rom splines so the surface stays smooth at any
 * block scale, and a grid of slowly varying values every {@value #GRID} pixels, sampled bilinearly: the local mean
 * elevation and relief, and the climate reduced to sea level. Tiles near the sea also keep the distance of each pixel
 * to the coastline ({@link Coastline}). Tiles are computed once, on the first thread that needs them or ahead of time
 * by {@link TerrainPrefetcher}, and kept in a small LRU cache. Everything here is thread-safe and deterministic.
 *
 * <p>Coordinates are native pixels: {@code i} grows with Minecraft Z and {@code j} with X, and the centre of pixel
 * {@code k} is at {@code k + 0.5}. {@link WorldScale#toPixel} converts block coordinates.
 */
public final class TerrainField implements AutoCloseable {
	/** Native pixels per tile side. */
	public static final int TILE = 256;
	/** Margin around each tile, in native pixels. */
	public static final int PAD = 32;
	/** Side of a padded tile. */
	public static final int SPAN = TILE + 2 * PAD;
	static final int GRID = 8;
	static final int GRID_POINTS = TILE / GRID + 1;
	/** Half-width of the box, in pixels, over which the mean elevation and the relief are measured (720 m). */
	static final int BOX_RADIUS = 24;
	/** Coarse pixels per cached block of the coarse map. */
	static final int COARSE_BLOCK = 16;
	/**
	 * Relief over the {@link #BOX_RADIUS} box per meter between a coarse pixel's mean elevation and its 5th
	 * percentile. That gap is about 1.6 standard deviations over 7.7 km, and rough terrain varies about a third as much
	 * over 1.4 km.
	 */
	static final float COARSE_RELIEF = 0.2f;
	/**
	 * Half the run, in pixels, over which {@link #slope} measures a hillside (480 m): a valley side, not the gullies on
	 * it, so that sun and shade follow the valleys rather than speckle every slope. It reads the tile's margin.
	 */
	static final int HILLSIDE = 16;
	/** Real-world wavelength of the noise that picks vanilla's biome variants, in meters. */
	static final float VARIANT_WAVELENGTH = 12000f;
	/**
	 * The longest wavelength of that noise, in blocks: from 1:4 up, 12 km of real ground would make its patches
	 * thousands of blocks across.
	 */
	static final double VARIANT_MAX_BLOCKS = 2000;

	static final int MEAN = 0;
	static final int RELIEF = 1;
	static final int SEA_TEMPERATURE = 2;
	static final int TEMPERATURE_SEASONALITY = 3;
	static final int PRECIPITATION = 4;
	static final int PRECIPITATION_SEASONALITY = 5;
	static final int LAPSE_RATE = 6;
	static final int ICE_CUT = 7;
	/** The coarse map's 5th percentile of the elevation, for {@link Column#aboveLow}. */
	static final int LOW = 8;
	static final int CHANNELS = 9;

	private static final int DEFAULT_MAX_TILES = 96;
	private static final int MAX_COARSE_BLOCKS = 64;

	/** Everything the generator reads at one column. */
	public static final class Column {
		/** Meters above sea level; negative is below. */
		public float elevation;
		/** Mean elevation over about 1.4 km, in meters. */
		public float meanElevation;
		/** Root-mean-square deviation from that mean, in meters: how rugged the land is. */
		public float relief;
		/**
		 * Meters above the low ground around: the elevation less the 5th percentile of the elevation over the coarse
		 * pixel (7.7 km), interpolated. Below zero in the lowest twentieth of the land around: a basin's floor.
		 */
		public float aboveLow;
		/** Annual mean temperature at this elevation, °C. */
		public float temperature;
		/** Temperature seasonality (WorldClim BIO4: monthly standard deviation × 100). */
		public float temperatureSeasonality;
		/** Annual precipitation, mm. */
		public float precipitation;
		/** Precipitation seasonality (WorldClim BIO15: coefficient of variation, %). */
		public float precipitationSeasonality;
		/** Smooth noise in [-1, 1] that picks vanilla's biome variants (sunflower plains, ice spikes and so on). */
		public float variant;
		/**
		 * Distance to the coastline in meters, positive on land and negative at sea, up to {@link #COAST_REACH}. The
		 * coarse map has no coastline: there it is the farthest distance, with the elevation's sign.
		 */
		public float coast;
		/**
		 * How far glaciers lowered ground at sea level here, in meters; see {@link Glaciation}. Ground below the sea
		 * and less than this deep was land before: a fjord ({@link Glaciation#drowned}).
		 */
		public float iceCut;
	}

	/** Farthest distance to the coastline that {@link #coast} measures, in meters (360 m). */
	public static final float COAST_REACH = (float) (Coastline.REACH * WorldScale.METERS_PER_PIXEL);

	private final long seed;
	private final long geologySeed;
	private final Glaciation source;
	private final int maxTiles;
	private final Map<Long, CompletableFuture<FieldTile>> tiles = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<CoarseBlock>> coarse = new ConcurrentHashMap<>();
	private final ThreadLocal<FieldTile> lastTile = new ThreadLocal<>();
	private final AtomicLong clock = new AtomicLong();
	private final LongAdder computedTiles = new LongAdder();
	private final LongAdder tileWaitNanos = new LongAdder();
	private final FastNoiseLite variantNoise;
	private final TerrainSurface[] surfaces = new TerrainSurface[WorldScale.values().length];

	/** A field whose rocks share the terrain's seed, for tests and tools. */
	public TerrainField(long seed, TerrainSource source) {
		this(seed, seed, source, DEFAULT_MAX_TILES);
	}

	/** A field for a world whose terrain and rocks have the seeds {@code seed} and {@code geologySeed}. */
	public TerrainField(long seed, long geologySeed, TerrainSource source) {
		this(seed, geologySeed, source, DEFAULT_MAX_TILES);
	}

	TerrainField(long seed, TerrainSource source, int maxTiles) {
		this(seed, seed, source, maxTiles);
	}

	private TerrainField(long seed, long geologySeed, TerrainSource source, int maxTiles) {
		this.seed = seed;
		this.geologySeed = geologySeed;
		this.source = new Glaciation(source, seed);
		this.maxTiles = maxTiles;
		this.variantNoise = new FastNoiseLite((int) (seed ^ (seed >>> 32)) ^ 0x5eed_b10e);
		variantNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
		variantNoise.SetFrequency(1f);
	}

	public long seed() {
		return seed;
	}

	/** The seed of the world's rocks, which shape the ground's finest detail (see {@link TerrainSurface}). */
	public long geologySeed() {
		return geologySeed;
	}

	/** Elevation in meters at a native pixel coordinate. */
	public float elevation(double i, double j) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		FieldTile tile = tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE));
		return tile.elevation(iu, iv, (float) (u - iu), (float) (v - iv));
	}

	/**
	 * Elevation in meters at a native pixel coordinate, as {@link #elevation(double, double)} gives it, and its
	 * gradient in meters per pixel: along {@code i} in {@code gradient[0]} and along {@code j} in {@code gradient[1]}.
	 */
	public float elevation(double i, double j, float[] gradient) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		FieldTile tile = tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE));
		return tile.elevation(iu, iv, (float) (u - iu), (float) (v - iv), gradient);
	}

	/**
	 * The slope of a hillside at a native pixel coordinate: the rise in meters per meter along {@code i} in
	 * {@code slope[0]} and along {@code j} in {@code slope[1]}, as differences over {@value #HILLSIDE} pixels either
	 * way. Smoother than {@link #elevation(double, double, float[]) the gradient}, which follows every bump. It reads
	 * the margin of the pixel's own tile, never the next one.
	 */
	public void slope(double i, double j, float[] slope) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		FieldTile tile = tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE));
		float fu = (float) (u - iu), fv = (float) (v - iv);
		float run = (float) (2 * HILLSIDE * WorldScale.METERS_PER_PIXEL);
		slope[0] = (tile.elevation(iu + HILLSIDE, iv, fu, fv) - tile.elevation(iu - HILLSIDE, iv, fu, fv)) / run;
		slope[1] = (tile.elevation(iu, iv + HILLSIDE, fu, fv) - tile.elevation(iu, iv - HILLSIDE, fu, fv)) / run;
	}

	/** The ground of a world at {@code scale} over this terrain. */
	public TerrainSurface surface(WorldScale scale) {
		TerrainSurface surface = surfaces[scale.ordinal()];
		if (surface == null) {
			// Two threads may both make one; they are the same.
			surface = new TerrainSurface(this, scale);
			surfaces[scale.ordinal()] = surface;
		}
		return surface;
	}

	/** Fills {@code out} with everything at a native pixel coordinate. */
	public void column(double i, double j, Column out) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		FieldTile tile = tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE));
		float elevation = tile.elevation(iu, iv, (float) (u - iu), (float) (v - iv));
		out.elevation = elevation;

		double gu = (u - tile.ti * (double) TILE) / GRID, gv = (v - tile.tj * (double) TILE) / GRID;
		int a = Math.min((int) gu, GRID_POINTS - 2), b = Math.min((int) gv, GRID_POINTS - 2);
		float wu = (float) (gu - a), wv = (float) (gv - b);
		out.meanElevation = tile.grid(MEAN, a, b, wu, wv);
		out.relief = tile.grid(RELIEF, a, b, wu, wv);
		out.aboveLow = elevation - tile.grid(LOW, a, b, wu, wv);
		out.temperature = tile.grid(SEA_TEMPERATURE, a, b, wu, wv)
			+ tile.grid(LAPSE_RATE, a, b, wu, wv) * Math.max(0f, elevation);
		out.temperatureSeasonality = tile.grid(TEMPERATURE_SEASONALITY, a, b, wu, wv);
		out.precipitation = Math.max(0f, tile.grid(PRECIPITATION, a, b, wu, wv));
		out.precipitationSeasonality = tile.grid(PRECIPITATION_SEASONALITY, a, b, wu, wv);
		out.coast = (float) (tile.coast(u, v) * WorldScale.METERS_PER_PIXEL);
		out.iceCut = tile.grid(ICE_CUT, a, b, wu, wv);
	}

	/**
	 * Distance from a native pixel coordinate to the coastline, where the elevation crosses zero, in pixels: positive
	 * on land, negative at sea, and at most {@link Coastline#REACH} either way. It is interpolated bilinearly between
	 * pixel centres, so it is continuous everywhere, across tiles too.
	 */
	public float coast(double i, double j) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		return tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE)).coast(u, v);
	}

	/**
	 * {@link #coast(double, double)} with its gradient, as differences over a pixel either way: along {@code i} in
	 * {@code gradient[0]} and along {@code j} in {@code gradient[1]}. Unlike the slope of the bilinear distance, these
	 * vary continuously. The gradient has unit length and points inland, except near the middle between two coasts,
	 * where it shrinks, and far from any coast, where it is zero.
	 */
	public float coast(double i, double j, float[] gradient) {
		double u = i - 0.5, v = j - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		FieldTile tile = tile(Math.floorDiv(iu, TILE), Math.floorDiv(iv, TILE));
		gradient[0] = (tile.coast(u + 1, v) - tile.coast(u - 1, v)) * 0.5f;
		gradient[1] = (tile.coast(u, v + 1) - tile.coast(u, v - 1)) * 0.5f;
		return tile.coast(u, v);
	}

	/** Variant noise at a position in real meters (block × meters per block). */
	public float variant(double xMeters, double zMeters) {
		return variantNoise.GetNoise((float) (xMeters / VARIANT_WAVELENGTH), (float) (zMeters / VARIANT_WAVELENGTH));
	}

	/**
	 * Variant noise at a block of a world of {@code scale}: {@link #variant(double, double)}, but with a wavelength of
	 * at most {@value #VARIANT_MAX_BLOCKS} blocks.
	 */
	public float variant(WorldScale scale, int blockX, int blockZ) {
		double metersPerBlock = Math.max(scale.metersPerBlock(), VARIANT_WAVELENGTH / VARIANT_MAX_BLOCKS);
		return variant(blockX * metersPerBlock, blockZ * metersPerBlock);
	}

	/**
	 * Elevation in meters from the coarse map alone (one pixel per {@link TerrainSource#coarseScale()} native pixels),
	 * interpolated bilinearly. Cheap enough to search a large area, for example for the spawn point.
	 */
	public float coarseElevation(double i, double j) {
		return coarseChannel(TerrainSource.COARSE_ELEVATION, i, j);
	}

	/**
	 * Fills {@code out} from the coarse map alone, like {@link #column} but without the details: the elevation is the
	 * coarse pixel's mean, and the relief is estimated from how far its 5th percentile lies below that mean. Cheap
	 * enough for biome searches that cover many kilometres, such as placing strongholds.
	 */
	public void coarseColumn(double i, double j, Column out) {
		float elevation = coarseChannel(TerrainSource.COARSE_ELEVATION, i, j);
		float low = coarseChannel(TerrainSource.COARSE_LOW_ELEVATION, i, j);
		out.elevation = elevation;
		out.meanElevation = elevation;
		out.relief = COARSE_RELIEF * Math.max(0f, elevation - low);
		out.aboveLow = elevation - low;
		out.temperature = coarseChannel(TerrainSource.COARSE_TEMPERATURE, i, j);
		out.temperatureSeasonality = coarseChannel(TerrainSource.COARSE_TEMPERATURE_SEASONALITY, i, j);
		out.precipitation = Math.max(0f, coarseChannel(TerrainSource.COARSE_PRECIPITATION, i, j));
		out.precipitationSeasonality = coarseChannel(TerrainSource.COARSE_PRECIPITATION_SEASONALITY, i, j);
		out.coast = elevation >= 0f ? COAST_REACH : -COAST_REACH;
		out.iceCut = coarseChannel(Glaciation.COARSE_ICE_CUT, i, j);
	}

	/**
	 * The raw elevation of tile {@code (ti, tj)} with its margin: {@value #SPAN}² row-major values in meters, the first
	 * one at native pixel {@code (ti * TILE - PAD, tj * TILE - PAD)}. The array is shared and must not be modified.
	 * Neighbouring tiles agree to the bit where their margins overlap: the source is seamless, and each pixel depends
	 * only on where it is, not on the window that asked for it.
	 */
	public float[] paddedTile(int ti, int tj) {
		return tile(ti, tj).elevation;
	}

	/**
	 * Which pixels of {@link #paddedTile} lie in a drowned valley ({@link Glaciation#drowned}): ground the ice lowered
	 * below the sea that was land before. Pixel {@code k} is bit {@code k % 64} of word {@code k / 64}, as
	 * {@link #isDrowned} reads it. Null if the tile has none. The array is shared and must not be modified.
	 */
	public long @Nullable [] paddedDrowned(int ti, int tj) {
		return tile(ti, tj).drowned;
	}

	/** Whether pixel {@code k} is set in a mask of {@link #paddedDrowned}, which may be null. */
	public static boolean isDrowned(long @Nullable [] drowned, int k) {
		return drowned != null && (drowned[k >> 6] & 1L << k) != 0;
	}

	/** Native pixels per coarse pixel. */
	public int coarseScale() {
		return source.coarseScale();
	}

	/** The kind of place the map put at the origin for the world spawn; see {@link SpawnSearch}. */
	public SpawnKind spawnKind() {
		return source.spawnKind();
	}

	/**
	 * The coarse map over {@code [ci0, ci1) x [cj0, cj1)}, as {@link TerrainSource#coarse} returns it, with the fjords
	 * of {@link Glaciation}. Not cached.
	 */
	public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
		return source.coarse(ci0, cj0, ci1, cj1);
	}

	/** Tiles currently held in memory. */
	public int cachedTiles() {
		return tiles.size();
	}

	/** Tiles computed so far: each one is a run of the full pipeline. A tile evicted and computed again counts twice. */
	public long computedTiles() {
		return computedTiles.sum();
	}

	/** Most tiles held in memory: more are evicted, the least recently used first. */
	public int maxTiles() {
		return maxTiles;
	}

	/**
	 * Time the readers of this field have spent waiting for tiles that were not ready, in nanoseconds, summed over
	 * threads: computing them or waiting for another thread to. {@link #prefetch} does not count.
	 */
	public long tileWaitNanos() {
		return tileWaitNanos.sum();
	}

	/** The tile index of a native pixel coordinate along either axis. */
	public static int tileOf(double pixel) {
		return Math.floorDiv((int) Math.floor(pixel - 0.5), TILE);
	}

	/** Whether tile {@code (ti, tj)} is in memory or being computed. */
	public boolean hasTile(int ti, int tj) {
		return tiles.containsKey(key(ti, tj));
	}

	/**
	 * Computes tile {@code (ti, tj)} on this thread if nobody has started it yet, so that readers find it ready. Returns
	 * at once if it is in memory or another thread is computing it.
	 *
	 * @return whether this thread computed the tile
	 * @throws RuntimeException if the source fails, as when the models are missing
	 */
	public boolean prefetch(int ti, int tj) {
		long key = key(ti, tj);
		if (tiles.containsKey(key)) {
			return false;
		}
		CompletableFuture<FieldTile> mine = new CompletableFuture<>();
		if (load(ti, tj, key, mine) != mine) {
			return false;
		}
		join(mine);
		return true;
	}

	@Override
	public void close() {
		tiles.clear();
		coarse.clear();
		source.close();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Tiles

	private FieldTile tile(int ti, int tj) {
		FieldTile last = lastTile.get();
		if (last != null && last.ti == ti && last.tj == tj) {
			last.lastUse = clock.incrementAndGet();
			return last;
		}
		long key = key(ti, tj);
		CompletableFuture<FieldTile> future = tiles.get(key);
		FieldTile tile;
		if (future != null && future.isDone()) {
			tile = join(future);
		} else {
			long start = System.nanoTime();
			try {
				tile = join(future != null ? future : load(ti, tj, key, new CompletableFuture<>()));
			} finally {
				tileWaitNanos.add(System.nanoTime() - start);
			}
		}
		tile.lastUse = clock.incrementAndGet();
		lastTile.set(tile);
		return tile;
	}

	/**
	 * The future of a tile that was not in memory: {@code mine}, completed on this thread, or the future of whichever
	 * thread got there first.
	 */
	private CompletableFuture<FieldTile> load(int ti, int tj, long key, CompletableFuture<FieldTile> mine) {
		CompletableFuture<FieldTile> future = tiles.putIfAbsent(key, mine);
		if (future != null) {
			return future;
		}
		try {
			FieldTile computed = computeTile(ti, tj);
			computedTiles.increment();
			// Mark it used before evicting, or it would be the oldest tile and go first.
			computed.lastUse = clock.incrementAndGet();
			mine.complete(computed);
		} catch (Throwable t) {
			tiles.remove(key, mine);
			mine.completeExceptionally(t);
		}
		evictTiles();
		return mine;
	}

	private FieldTile computeTile(int ti, int tj) {
		int i0 = ti * TILE - PAD, j0 = tj * TILE - PAD;
		int plane = SPAN * SPAN;
		float[] iceCut = new float[plane];
		TerrainSample sample = source.sample(i0, j0, i0 + SPAN, j0 + SPAN, iceCut);
		float[] elevation = sample.elevation();
		float[] climate = sample.climate();

		// Summed-area tables of the elevation and its square give box means and variances in constant time.
		int stride = SPAN + 1;
		double[] sum = new double[stride * stride], sumSq = new double[stride * stride];
		for (int r = 0; r < SPAN; r++) {
			double rowSum = 0, rowSumSq = 0;
			for (int c = 0; c < SPAN; c++) {
				double e = elevation[r * SPAN + c];
				rowSum += e;
				rowSumSq += e * e;
				sum[(r + 1) * stride + c + 1] = sum[r * stride + c + 1] + rowSum;
				sumSq[(r + 1) * stride + c + 1] = sumSq[r * stride + c + 1] + rowSumSq;
			}
		}

		int points = GRID_POINTS * GRID_POINTS;
		float[] grid = new float[CHANNELS * points];
		double boxArea = 4.0 * BOX_RADIUS * BOX_RADIUS;
		for (int a = 0; a < GRID_POINTS; a++) {
			int r = PAD + a * GRID;
			for (int b = 0; b < GRID_POINTS; b++) {
				int c = PAD + b * GRID;
				int r0 = r - BOX_RADIUS, r1 = r + BOX_RADIUS, c0 = c - BOX_RADIUS, c1 = c + BOX_RADIUS;
				double s = sum[r1 * stride + c1] - sum[r0 * stride + c1] - sum[r1 * stride + c0] + sum[r0 * stride + c0];
				double sq = sumSq[r1 * stride + c1] - sumSq[r0 * stride + c1] - sumSq[r1 * stride + c0] + sumSq[r0 * stride + c0];
				double mean = s / boxArea;
				double variance = Math.max(0.0, sq / boxArea - mean * mean);

				int px = r * SPAN + c, g = a * GRID_POINTS + b;
				float lapse = climate[TerrainSample.LAPSE_RATE * plane + px];
				float temperature = climate[TerrainSample.TEMPERATURE * plane + px];
				grid[MEAN * points + g] = (float) mean;
				grid[RELIEF * points + g] = (float) Math.sqrt(variance);
				grid[SEA_TEMPERATURE * points + g] = temperature - lapse * Math.max(0f, elevation[px]);
				grid[TEMPERATURE_SEASONALITY * points + g] = climate[TerrainSample.TEMPERATURE_SEASONALITY * plane + px];
				grid[PRECIPITATION * points + g] = climate[TerrainSample.PRECIPITATION * plane + px];
				grid[PRECIPITATION_SEASONALITY * points + g] = climate[TerrainSample.PRECIPITATION_SEASONALITY * plane + px];
				grid[LAPSE_RATE * points + g] = lapse;
				grid[ICE_CUT * points + g] = iceCut[px];
				grid[LOW * points + g] = coarseChannel(TerrainSource.COARSE_LOW_ELEVATION, ti * TILE + a * GRID + 0.5,
					tj * TILE + b * GRID + 0.5);
			}
		}
		long[] drowned = null;
		for (int px = 0; px < plane; px++) {
			if (Glaciation.drowned(elevation[px], iceCut[px])) {
				drowned = drowned == null ? new long[(plane + 63) >> 6] : drowned;
				drowned[px >> 6] |= 1L << px;
			}
		}
		return new FieldTile(ti, tj, elevation, drowned, grid, Coastline.compute(elevation));
	}

	private void evictTiles() {
		while (tiles.size() > maxTiles) {
			long oldestKey = 0, oldestUse = Long.MAX_VALUE;
			for (Map.Entry<Long, CompletableFuture<FieldTile>> entry : tiles.entrySet()) {
				CompletableFuture<FieldTile> future = entry.getValue();
				if (future.isDone() && !future.isCompletedExceptionally()) {
					long use = future.join().lastUse;
					if (use < oldestUse) {
						oldestUse = use;
						oldestKey = entry.getKey();
					}
				}
			}
			if (oldestUse == Long.MAX_VALUE) {
				return;
			}
			tiles.remove(oldestKey);
		}
	}

	static final class FieldTile {
		final int ti;
		final int tj;
		final int i0;
		final int j0;
		final float[] elevation;
		/** See {@link #paddedDrowned}. */
		final long @Nullable [] drowned;
		final float[] grid;
		/** The signed distances of {@link Coastline}, or {@code null} far from the coast. */
		final short[] coast;
		/** The distance everywhere in the tile when {@link #coast} is {@code null}. */
		final float farCoast;
		volatile long lastUse;

		FieldTile(int ti, int tj, float[] elevation, long @Nullable [] drowned, float[] grid, short[] coast) {
			this.ti = ti;
			this.tj = tj;
			this.i0 = ti * TILE - PAD;
			this.j0 = tj * TILE - PAD;
			this.elevation = elevation;
			this.drowned = drowned;
			this.grid = grid;
			this.coast = coast;
			int first = Coastline.ORIGIN * SPAN + Coastline.ORIGIN;
			this.farCoast = elevation[first] >= 0f ? Coastline.REACH : -Coastline.REACH;
		}

		/**
		 * The distance to the coastline in pixels at {@code (u, v)}, pixel centres being whole, interpolated
		 * bilinearly. It must lie within a pixel of this tile: {@code u} in {@code [ti * TILE - 1, ti * TILE + TILE + 1)}.
		 */
		float coast(double u, double v) {
			if (coast == null) {
				return farCoast;
			}
			double a = u - (ti * TILE - 1), b = v - (tj * TILE - 1);
			int ia = (int) Math.floor(a), ib = (int) Math.floor(b);
			float fa = (float) (a - ia), fb = (float) (b - ib);
			int k = ia * Coastline.SIDE + ib;
			short[] d = coast;
			float top = lerp(d[k], d[k + 1], fb), bottom = lerp(d[k + Coastline.SIDE], d[k + Coastline.SIDE + 1], fb);
			return lerp(top, bottom, fa) / Coastline.UNIT;
		}

		/** Catmull-Rom interpolation between pixel centres; {@code (iu, iv)} must lie in this tile. */
		float elevation(int iu, int iv, float fu, float fv) {
			float[] e = elevation;
			int base = (iu - i0 - 1) * SPAN + (iv - j0 - 1);
			float r0 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r1 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r2 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r3 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			return cubic(r0, r1, r2, r3, fu);
		}

		/** {@link #elevation(int, int, float, float)} with its gradient along {@code u} and {@code v}. */
		float elevation(int iu, int iv, float fu, float fv, float[] gradient) {
			float[] e = elevation;
			int base = (iu - i0 - 1) * SPAN + (iv - j0 - 1);
			float r0 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			float d0 = slope(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r1 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			float d1 = slope(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r2 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			float d2 = slope(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			base += SPAN;
			float r3 = cubic(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			float d3 = slope(e[base], e[base + 1], e[base + 2], e[base + 3], fv);
			gradient[0] = slope(r0, r1, r2, r3, fu);
			gradient[1] = cubic(d0, d1, d2, d3, fu);
			return cubic(r0, r1, r2, r3, fu);
		}

		float grid(int channel, int a, int b, float wu, float wv) {
			int base = channel * GRID_POINTS * GRID_POINTS + a * GRID_POINTS + b;
			float[] g = grid;
			return lerp(lerp(g[base], g[base + 1], wv), lerp(g[base + GRID_POINTS], g[base + GRID_POINTS + 1], wv), wu);
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Coarse map

	/** One coarse channel at a native pixel coordinate, interpolated bilinearly. */
	private float coarseChannel(int channel, double i, double j) {
		int scale = source.coarseScale();
		double u = i / scale - 0.5, v = j / scale - 0.5;
		int cu = (int) Math.floor(u), cv = (int) Math.floor(v);
		float fu = (float) (u - cu), fv = (float) (v - cv);
		float e00 = coarsePixel(channel, cu, cv), e01 = coarsePixel(channel, cu, cv + 1);
		float e10 = coarsePixel(channel, cu + 1, cv), e11 = coarsePixel(channel, cu + 1, cv + 1);
		return lerp(lerp(e00, e01, fv), lerp(e10, e11, fv), fu);
	}

	private float coarsePixel(int channel, int cu, int cv) {
		int bi = Math.floorDiv(cu, COARSE_BLOCK), bj = Math.floorDiv(cv, COARSE_BLOCK);
		long key = key(bi, bj);
		CompletableFuture<CoarseBlock> future = coarse.get(key);
		if (future == null) {
			CompletableFuture<CoarseBlock> mine = new CompletableFuture<>();
			future = coarse.putIfAbsent(key, mine);
			if (future == null) {
				future = mine;
				try {
					int ci0 = bi * COARSE_BLOCK, cj0 = bj * COARSE_BLOCK;
					mine.complete(new CoarseBlock(
						source.coarseWithIceCut(ci0, cj0, ci0 + COARSE_BLOCK, cj0 + COARSE_BLOCK)));
				} catch (Throwable t) {
					coarse.remove(key, mine);
					mine.completeExceptionally(t);
				}
				if (coarse.size() > MAX_COARSE_BLOCKS) {
					coarse.keySet().removeIf(k -> k != key);
				}
			}
		}
		return join(future).data[(channel * COARSE_BLOCK + cu - bi * COARSE_BLOCK) * COARSE_BLOCK + (cv - bj * COARSE_BLOCK)];
	}

	/** {@link Glaciation#coarseWithIceCut}'s planes of {@value #COARSE_BLOCK}² coarse pixels. */
	private record CoarseBlock(float[] data) {
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	private static long key(int i, int j) {
		return ((long) i << 32) | (j & 0xffffffffL);
	}

	private static <T> T join(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}

	static float cubic(float p0, float p1, float p2, float p3, float t) {
		return p1 + 0.5f * t * (p2 - p0 + t * (2f * p0 - 5f * p1 + 4f * p2 - p3 + t * (3f * (p1 - p2) + p3 - p0)));
	}

	/** The derivative of {@link #cubic} with {@code t}. */
	static float slope(float p0, float p1, float p2, float p3, float t) {
		return 0.5f * (p2 - p0 + t * (2f * (2f * p0 - 5f * p1 + 4f * p2 - p3) + t * 3f * (3f * (p1 - p2) + p3 - p0)));
	}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
