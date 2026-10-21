package io.github.elpideus.diffusicraft.terrain.geography;

import static io.github.elpideus.diffusicraft.terrain.geography.Continents.smoothstep;

/**
 * The geography of a world: where its continents, mountains, island chains and seas lie, and the climate that
 * follows from them. It is the map the coarse model is conditioned on, so the model draws the terrain and the climate
 * that fit it. Everything is a function of the seed and the position, cheap enough to compute anywhere on demand.
 *
 * <ul>
 *     <li>{@link Continents}: landmasses of every size, from supercontinents to islands, with coasts smooth in places
 *     and broken into archipelagos in others;</li>
 *     <li>{@link Hypsometry}: heights spread like the Earth's;</li>
 *     <li>{@link Tectonics}: cordilleras, collision belts, island arcs, trenches, mid-ocean ridges and rifts where
 *     plates meet;</li>
 *     <li>{@link Hotspots}: chains of volcanic islands;</li>
 *     <li>{@link ClimateModel}: the climate from the latitude, the winds, the sea and the mountains.</li>
 * </ul>
 *
 * <p>The latitude runs along the z axis, north towards negative z as in Minecraft, 111 km to the degree at every world
 * scale. Past a pole it comes back down the other side, as it would going over the pole of a globe. The origin lies at
 * a temperate latitude between {@value #MIN_ORIGIN_LATITUDE}° and {@value #MAX_ORIGIN_LATITUDE}° north, chosen by the
 * seed, and the continents are shifted so that the kind of place the seed drew for the spawn ({@link SpawnKind}) lies
 * there: the coast of a continent, a plain or other land well inland, a small island far out at sea, or a high
 * mountain. A coast, or land inland off the plains, lies in view of mountains wherever the seed has such a place.
 */
public final class Geography {
	/**
	 * Bumped whenever the map of a seed changes, so that windows cached on disk from an older map are not mixed with
	 * the new one.
	 */
	public static final int VERSION = 2;
	/** Kilometres per pixel of the coarse map: 256 native pixels of 30 m. */
	public static final double KM_PER_PIXEL = 7.68;
	public static final double KM_PER_DEGREE = 111.2;
	static final double MIN_ORIGIN_LATITUDE = 35;
	static final double MAX_ORIGIN_LATITUDE = 50;
	/** Coarse pixels between the points at which the climate is computed; it is interpolated between them. */
	static final int LATTICE = 4;
	/** Shortest octave of the continents in the relief the winds cross. */
	private static final double RELIEF_DETAIL = 150;
	private static final double LAND_BAND = 0.1;
	private static final double ORIGIN_SEARCH = 30000;
	private static final int ORIGIN_TRIES = 4096;
	/** Islands far out at sea are rare, so more places are tried for them. */
	private static final int ISLAND_TRIES = 65536;
	/** And for the places with mountains in view, which few coasts and little land inland have. */
	private static final int VIEW_TRIES = 16384;
	/** An inland origin has land all around it this many km out, and mostly land four times as far. */
	private static final double NEAR_LAND = 100;
	private static final double WIDE_LAND = 400;
	/** The share of seeds whose inland origin lies on a plain. */
	private static final double PLAINS_CHANCE = 0.75;
	/** A plain is no higher than this, and rises and falls less than {@value #PLAIN_RELIEF} m over its radius. */
	private static final double PLAIN_HEIGHT = 500;
	private static final double PLAIN_RELIEF = 200;
	private static final double PLAIN_RADIUS = 25;
	/** A coastal origin has the sea within this many km, on a landmass that reaches past {@value #CONTINENT} km. */
	private static final double COAST_REACH = 16;
	/**
	 * Beyond the coast the sea is open: deeper than {@value #OPEN_SEA_DEPTH} m at {@value #OPEN_SEA} km and twice that
	 * out the same way. The coarse model fills a small or shallow inlet in with land.
	 */
	private static final double OPEN_SEA = 30;
	private static final double OPEN_SEA_DEPTH = -100;
	private static final double CONTINENT = 150;
	/**
	 * An island at the origin is at least {@value #ISLAND_SIZE} km across, so that the coarse map draws it, has no
	 * other land within {@value #ISLAND_ALONE} km and little within {@value #ISLAND_REMOTE} km.
	 */
	private static final double ISLAND_SIZE = 25;
	private static final double ISLAND_ALONE = 120;
	private static final double ISLAND_REMOTE = 300;
	/** A mountain at the origin is at least this high. */
	private static final double PEAK_HEIGHT = 3000;
	/**
	 * A coast, or land inland off the plains, is better with mountains in view: ground at least {@value #VIEW_HEIGHT}
	 * m high within {@value #VIEW_REACH} km, looked for on rings {@value #VIEW_STEP} km apart.
	 */
	private static final double VIEW_HEIGHT = 1500;
	private static final double VIEW_REACH = 30;
	private static final double VIEW_STEP = 2;
	private static final double MIN_HEIGHT = -8700;
	private static final double MAX_HEIGHT = 6200;

	private final Continents continents;
	private final Hypsometry hypsometry;
	private final Tectonics tectonics;
	private final Hotspots hotspots;
	private final ClimateModel climate;
	private final double originLatitude;
	private final SpawnKind spawnKind;
	private final double offsetX;
	private final double offsetZ;

	/** @param elevationQuantiles the real distribution of elevations in meters, at evenly spaced probabilities */
	public Geography(long seed, float[] elevationQuantiles) {
		this(seed, elevationQuantiles, null);
	}

	/** A geography whose origin is the kind of place given, or the one the seed draws if {@code null}. */
	Geography(long seed, float[] elevationQuantiles, SpawnKind kind) {
		this.continents = new Continents(seed);
		this.hypsometry = new Hypsometry(elevationQuantiles);
		this.tectonics = new Tectonics(seed, continents, hypsometry.seaLevel());
		this.hotspots = new Hotspots(seed);
		this.climate = new ClimateModel(seed, this::relief);
		long h = Hash.mix(seed ^ 0x0216_1000L);
		this.originLatitude = MIN_ORIGIN_LATITUDE + (MAX_ORIGIN_LATITUDE - MIN_ORIGIN_LATITUDE) * Hash.unit(h, 1);
		SpawnKind wanted = kind != null ? kind : SpawnKind.draw(Hash.unit(h, 2));
		boolean plains = Hash.unit(h, 3) < PLAINS_CHANCE;
		// If no such place turns up, the origin falls back to a plain, then to any land inland.
		double[] origin = placeOrigin(h, wanted, plains);
		SpawnKind placed = wanted;
		if (origin == null) {
			placed = SpawnKind.INLAND;
			origin = placeOrigin(h, placed, plains);
		}
		if (origin == null) {
			origin = placeOrigin(h, placed, false);
		}
		if (origin == null) {
			origin = new double[]{0, 0};
		}
		this.spawnKind = placed;
		this.offsetX = origin[0];
		this.offsetZ = origin[1];
	}

	/** The kind of place at the origin, where the world spawn should be. */
	public SpawnKind spawnKind() {
		return spawnKind;
	}

	/** Elevation in meters at a point of the world in km. */
	public double elevation(double x, double z) {
		return shiftedElevation(x + offsetX, z + offsetZ);
	}

	/** Latitude in degrees at a z coordinate in km, positive to the north. */
	public double latitude(double z) {
		return fold(originLatitude - z / KM_PER_DEGREE);
	}

	/**
	 * The conditioning map over coarse pixels {@code [x1, x2) × [y1, y2)}, x along the world's x axis and y along
	 * its z axis: {@code [5][y2 - y1][x2 - x1]} holding the signed square root of the elevation, the temperature at
	 * that elevation, the temperature seasonality, the annual precipitation and its seasonality, as the coarse model
	 * expects them.
	 */
	public float[][][] sample(int x1, int y1, int x2, int y2) {
		int h = y2 - y1, w = x2 - x1;
		float[][][] out = new float[5][h][w];

		int lx0 = Math.floorDiv(x1, LATTICE), lz0 = Math.floorDiv(y1, LATTICE);
		int lx1 = Math.floorDiv(x2 - 1, LATTICE) + 1, lz1 = Math.floorDiv(y2 - 1, LATTICE) + 1;
		int lw = lx1 - lx0 + 1, lh = lz1 - lz0 + 1;
		double[][] lattice = new double[4][lw * lh];
		double[] values = new double[4];
		for (int r = 0; r < lh; r++) {
			for (int c = 0; c < lw; c++) {
				climateAt((lx0 + c) * LATTICE, (lz0 + r) * LATTICE, values);
				for (int k = 0; k < 4; k++) {
					lattice[k][r * lw + c] = values[k];
				}
			}
		}

		for (int r = 0; r < h; r++) {
			int y = y1 + r;
			int lr = Math.floorDiv(y, LATTICE) - lz0;
			double fz = (double) Math.floorMod(y, LATTICE) / LATTICE;
			for (int c = 0; c < w; c++) {
				int x = x1 + c;
				int lc = Math.floorDiv(x, LATTICE) - lx0;
				double fx = (double) Math.floorMod(x, LATTICE) / LATTICE;
				for (int k = 0; k < 4; k++) {
					double[] l = lattice[k];
					int i = lr * lw + lc;
					double top = l[i] + fx * (l[i + 1] - l[i]);
					double bottom = l[i + lw] + fx * (l[i + lw + 1] - l[i + lw]);
					values[k] = top + fz * (bottom - top);
				}
				double elevation = elevation(x * KM_PER_PIXEL, y * KM_PER_PIXEL);
				double precipitation = values[2];
				double lapse = Math.clamp(-6.5 + 0.0015 * precipitation, -9.8, -4.0) / 1000.0;
				double temperature = Math.clamp(values[0] + lapse * Math.max(0, elevation), -10, 40);
				out[0][r][c] = (float) (Math.signum(elevation) * Math.sqrt(Math.abs(elevation)));
				out[1][r][c] = (float) temperature;
				out[2][r][c] = (float) values[1];
				out[3][r][c] = (float) precipitation;
				out[4][r][c] = (float) values[3];
			}
		}
		return out;
	}

	/** Climate at a coarse pixel: sea-level temperature, its seasonality, precipitation and its seasonality. */
	void climateAt(int x, int y, double[] out) {
		double z = y * KM_PER_PIXEL;
		double latitude = latitude(z);
		double next = latitude(z + 1);
		double poleward = Math.signum((Math.abs(next) - Math.abs(latitude)));
		climate.climate(x * KM_PER_PIXEL + offsetX, z + offsetZ, latitude, poleward, out);
	}

	private double shiftedElevation(double x, double z) {
		double field = continents.field(x, z, 0);
		double land = land(field);
		double height = hypsometry.meters(field, continents.uplands(x, z)) + tectonics.uplift(x, z, land)
			+ hotspots.uplift(x, z, land);
		return Math.clamp(height, MIN_HEIGHT, MAX_HEIGHT);
	}

	/**
	 * The broad relief the winds cross, without small islands and volcanoes. Rifts do not flood it: a narrow sea
	 * inland hardly changes the climate around it.
	 */
	private double relief(double x, double z) {
		double field = continents.field(x, z, RELIEF_DETAIL);
		double base = hypsometry.meters(field, continents.uplands(x, z));
		double height = base + tectonics.uplift(x, z, land(field));
		if (base > 0) {
			height = Math.max(1, height);
		}
		return Math.clamp(height, MIN_HEIGHT, MAX_HEIGHT);
	}

	/** How far a value of the continental field lies on land, from 0 out at sea to 1 inland. */
	private double land(double field) {
		return smoothstep(hypsometry.seaLevel() - LAND_BAND, hypsometry.seaLevel() + LAND_BAND, field);
	}

	/**
	 * A place for the origin that suits a kind of spawn, or {@code null} if none turns up. Of the coasts and the land
	 * inland off the plains, the first with mountains in view, or else the first.
	 */
	private double[] placeOrigin(long h, SpawnKind kind, boolean plains) {
		boolean view = kind == SpawnKind.COAST || kind == SpawnKind.INLAND && !plains;
		int tries = kind == SpawnKind.ISLAND ? ISLAND_TRIES : view ? VIEW_TRIES : ORIGIN_TRIES;
		double[] first = null;
		for (int k = 0; k < tries; k++) {
			long t = Hash.of(h, 7, k);
			double x = ORIGIN_SEARCH * (2 * Hash.unit(t, 1) - 1);
			double z = ORIGIN_SEARCH * (2 * Hash.unit(t, 2) - 1);
			if (suits(kind, plains, x, z)) {
				if (!view || mountainsInView(x, z)) {
					return new double[]{x, z};
				}
				if (first == null) {
					first = new double[]{x, z};
				}
			}
		}
		return first;
	}

	/** Whether ground at least {@value #VIEW_HEIGHT} m high lies within {@value #VIEW_REACH} km. */
	private boolean mountainsInView(double x, double z) {
		for (double radius = VIEW_STEP; radius <= VIEW_REACH; radius += VIEW_STEP) {
			int points = (int) Math.ceil(2 * Math.PI * radius / VIEW_STEP);
			for (int k = 0; k < points; k++) {
				double angle = 2 * Math.PI * k / points;
				if (shiftedElevation(x + radius * StrictMath.cos(angle), z + radius * StrictMath.sin(angle))
					>= VIEW_HEIGHT) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean suits(SpawnKind kind, boolean plains, double x, double z) {
		return switch (kind) {
			case COAST -> coast(x, z);
			case INLAND -> plains ? inland(x, z, PLAIN_HEIGHT) && plain(x, z) : inland(x, z, 900);
			case ISLAND -> island(x, z);
			case HOLLOW -> inland(x, z, 1500);
			case PEAK -> shiftedElevation(x, z) >= PEAK_HEIGHT;
		};
	}

	/**
	 * Low land with the open sea within {@value #COAST_REACH} km, on a landmass that reaches past {@value #CONTINENT}
	 * km in several directions: the coast of a continent or a large island, not of a small one or of an inlet.
	 */
	private boolean coast(double x, double z) {
		double height = shiftedElevation(x, z);
		if (height < 2 || height > 150) {
			return false;
		}
		boolean open = false;
		for (int k = 0; k < 8 && !open; k++) {
			double angle = 2 * Math.PI * k / 8, cos = StrictMath.cos(angle), sin = StrictMath.sin(angle);
			open = (shiftedElevation(x + COAST_REACH / 2 * cos, z + COAST_REACH / 2 * sin) <= 0
				|| shiftedElevation(x + COAST_REACH * cos, z + COAST_REACH * sin) <= 0)
				&& shiftedElevation(x + OPEN_SEA * cos, z + OPEN_SEA * sin) < OPEN_SEA_DEPTH
				&& shiftedElevation(x + 2 * OPEN_SEA * cos, z + 2 * OPEN_SEA * sin) < OPEN_SEA_DEPTH;
		}
		return open && landOnRing(x, z, CONTINENT, 12) >= 5 && landOnRing(x, z, WIDE_LAND, 12) >= 4;
	}

	/**
	 * Land between 60 m and {@code highest}, at least {@value #NEAR_LAND} km from the sea, with most of the land
	 * {@value #WIDE_LAND} km around it dry too.
	 */
	private boolean inland(double x, double z, double highest) {
		double height = shiftedElevation(x, z);
		if (height < 60 || height > highest) {
			return false;
		}
		return landOnRing(x, z, NEAR_LAND, 12) == 12 && landOnRing(x, z, WIDE_LAND, 12) >= 9;
	}

	/** Whether the land rises and falls less than {@value #PLAIN_RELIEF} m within {@value #PLAIN_RADIUS} km. */
	private boolean plain(double x, double z) {
		double lowest = shiftedElevation(x, z), highest = lowest;
		for (int k = 0; k < 8; k++) {
			double angle = 2 * Math.PI * k / 8;
			double height = shiftedElevation(x + PLAIN_RADIUS * StrictMath.cos(angle),
				z + PLAIN_RADIUS * StrictMath.sin(angle));
			lowest = Math.min(lowest, height);
			highest = Math.max(highest, height);
		}
		return highest - lowest <= PLAIN_RELIEF;
	}

	/** Well inside a small island, alone far out at sea. */
	private boolean island(double x, double z) {
		if (shiftedElevation(x, z) < 150) {
			return false;
		}
		return landOnRing(x, z, ISLAND_SIZE / 2, 8) == 8 && landOnRing(x, z, ISLAND_ALONE, 12) == 0
			&& landOnRing(x, z, ISLAND_REMOTE, 12) <= 1 && landOnRing(x, z, 2 * ISLAND_REMOTE, 16) <= 2;
	}

	/** How many of {@code points} evenly spaced on a circle around a point lie on land. */
	private int landOnRing(double x, double z, double radius, int points) {
		int land = 0;
		for (int k = 0; k < points; k++) {
			double angle = 2 * Math.PI * k / points;
			if (shiftedElevation(x + radius * StrictMath.cos(angle), z + radius * StrictMath.sin(angle)) > 0) {
				land++;
			}
		}
		return land;
	}

	/** Folds a latitude back into [-90, 90], as going past a pole comes back down the other side. */
	static double fold(double latitude) {
		double t = (latitude + 90) % 360;
		if (t < 0) {
			t += 360;
		}
		return t <= 180 ? t - 90 : 270 - t;
	}
}
