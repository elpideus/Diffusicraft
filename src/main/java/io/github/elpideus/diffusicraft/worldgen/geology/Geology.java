package io.github.elpideus.diffusicraft.worldgen.geology;

import java.util.Arrays;

import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.clamp01;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.fractal;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.gradient;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.hash;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.mix;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.smoothstep;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.unit;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.unit2;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.value3;

/**
 * What the ground is made of: the rock at every block below the surface, and the ore bodies in it. One instance per
 * world seed and scale; it is immutable and thread-safe, while each thread keeps its own {@link Column}.
 *
 * <p>From the top down, as on Earth:
 * <ol>
 *     <li><b>Sedimentary cover</b> in beds a few blocks thick. The beds follow the height, not the surface, so cliffs
 *     and valley walls cut through them and show the bands. Lowland basins collect a thick cover, rugged uplands a
 *     thin one. What the beds are made of depends on the basin: limestone, chalk and shale where there was a sea;
 *     sandstone and shale on land; coal seams in swampy basins; red sandstone and rock salt in arid ones. In fold
 *     belts (rugged land over thick sediment) the beds are folded steeply, and the deep ones turn into slate, marble
 *     and schist.</li>
 *     <li><b>Basement</b>: the old crust under the cover. Granite, gneiss, plain stone, schist with marble lenses, or
 *     andesite with tuff beds, by region, with lenses of other rocks.</li>
 *     <li><b>Lower crust</b> from about y=4 down: deepslate, as in vanilla, then gabbro.</li>
 *     <li><b>Mantle</b>: peridotite, pierced by rare kimberlite pipes that carry diamonds upwards.</li>
 *     <li><b>The magma layer</b>: magma, blackstone and basalt just above the bedrock.</li>
 * </ol>
 * Ores come in bodies whose mineral depends on the rock around them (copper and gold in granite, lapis in marble,
 * emerald in mountain schist, diamonds deep down), so prospecting by rock pays off. See docs/WORLDGEN.md.
 *
 * <p>Thicknesses are given in meters. Above the sea they follow the world's vertical scale, so mountains look the
 * same at every scale. Below the sea they never use fewer than {@link #UNDERGROUND_METERS} meters per block, so the
 * layers stay within reach in every world type.
 */
public final class Geology {
	/** The bed sequence repeats after this many blocks, far more than any world is tall. */
	private static final int PERIOD = 4096;
	private static final int LENS_CELL_XZ = 28;
	private static final int LENS_CELL_Y = 18;
	private static final int ORE_CELL_XZ = 20;
	private static final int ORE_CELL_Y = 14;
	/** Every Diffusicraft scale keeps vanilla's sea level (see {@code WorldScale}). */
	private static final int SEA_LEVEL = 63;
	/**
	 * The fewest meters a block stands for below the sea: the standard world's vertical scale. The underground is
	 * sized for play, not for the real crust. Otherwise, in the tall worlds, a lowland basin's 1.7 km of sediment
	 * would bury the basement and reach below the bottom of the world.
	 */
	private static final double UNDERGROUND_METERS = 10.0;

	/** Where the sedimentary cover was laid down, which decides what its beds are made of. */
	public enum Basin {
		MARINE, CLASTIC, COAL, EVAPORITE
	}

	enum Basement {
		GRANITE, GNEISS, STONE, SCHIST, VOLCANIC
	}

	/**
	 * One column's geology. The caller fills in the terrain inputs and calls {@link Geology#column}; the rest is
	 * derived. Not thread-safe: keep one per thread.
	 */
	public static final class Column {
		/** Terrain surface, in blocks. */
		public float surfaceY;
		/** Surface of the land smoothed over about a kilometre, in blocks. */
		public float meanSurfaceY;
		/** The same smoothed elevation, in meters. */
		public float meanElevation;
		/** How rugged the land is, in meters (see {@code TerrainField.Column}). */
		public float relief;
		/** Annual mean temperature, °C. */
		public float temperature;
		/** Annual precipitation, mm. */
		public float precipitation;

		float fold;
		float sedimentBase;
		float warp;
		Basin basin;
		Basement basement;
		int deepslateTop;
		int gabbroTop;
		int peridotiteTop;
		int magmaTop;
		boolean pipe;
		float pipeDistance;
		int pipeBottom;
		int pipeTop;
		float pipeRadiusTop;

		// The last lens and ore cells looked at; neighbouring blocks share them.
		private long lensCell = Long.MIN_VALUE;
		private Rock lensRock;
		private double lensX;
		private double lensY;
		private double lensZ;
		private double lensRx;
		private double lensRy;
		private double lensRz;
		private long oreCell = Long.MIN_VALUE;
		private long oreHash;
		private boolean oreBody;

		public Basin basin() {
			return basin;
		}

		/** Height below which the sedimentary cover gives way to the basement. */
		public float sedimentBase() {
			return sedimentBase;
		}

		/** How folded the beds are, from 0 (flat) to 1 (a mountain fold belt). */
		public float fold() {
			return fold;
		}

		public int deepslateTop() {
			return deepslateTop;
		}

		public int magmaTop() {
			return magmaTop;
		}
	}

	private final long seed;
	private final double verticalMeters;
	private final double undergroundMeters;
	private final double stretch;
	private final int magmaTop;
	private final int peridotiteTop;
	private final int gabbroTop;
	private final int deepWarp;
	private final short[] bedAt = new short[PERIOD];
	private final float[] bedRoll;
	private final boolean[] bedThin;

	/**
	 * @param seed            the geology seed of the world
	 * @param minY            bottom of the world
	 * @param horizontalMeters meters per block horizontally; regions span more blocks on finer scales
	 * @param verticalMeters  meters per block vertically, for thicknesses given in meters; below the sea, at least
	 *                        {@link #UNDERGROUND_METERS}
	 */
	public Geology(long seed, int minY, double horizontalMeters, double verticalMeters) {
		this.seed = seed;
		this.verticalMeters = verticalMeters;
		this.undergroundMeters = Math.max(verticalMeters, UNDERGROUND_METERS);
		this.stretch = Math.sqrt(15.0 / horizontalMeters);
		// The deep layers share out the room between the bottom of the world and y=0: 64 blocks in vanilla's height,
		// 256 in tall worlds.
		int depth = Math.max(64, -minY);
		magmaTop = minY + 4 + Math.max(5, depth / 16);
		peridotiteTop = magmaTop + Math.max(4, depth / 10);
		gabbroTop = peridotiteTop + Math.max(6, depth / 6);
		deepWarp = Math.max(2, depth / 40);

		float[] rolls = new float[PERIOD];
		boolean[] thin = new boolean[PERIOD];
		int bed = 0;
		long h = mix(seed ^ 0x5EDB_ED5L);
		for (int y = 0; y < PERIOD; bed++) {
			h = mix(h + 0x9E3779B97F4A7C15L);
			boolean isThin = unit(h) < 0.16;
			int thickness = isThin ? 1 + (int) (unit2(h) * 2) : 2 + (int) (unit2(h) * 7);
			rolls[bed] = (float) unit(mix(h ^ 0xABCDEFL));
			thin[bed] = isThin;
			for (int i = 0; i < thickness && y < PERIOD; i++) {
				bedAt[y++] = (short) bed;
			}
		}
		bedRoll = Arrays.copyOf(rolls, bed);
		bedThin = Arrays.copyOf(thin, bed);
	}

	/** Derives a column's geology from its terrain inputs. */
	public void column(int x, int z, Column c) {
		double sx = x / stretch;
		double sz = z / stretch;
		double basinNoise = fractal(seed + 1, sx / 900, sz / 900, 3);
		double faciesNoise = fractal(seed + 2, sx / 650, sz / 650, 2);
		double basementNoise = fractal(seed + 3, sx / 560, sz / 560, 3);
		double foldNoise = fractal(seed + 4, sx / 1100, sz / 1100, 2);

		double rugged = clamp01(c.relief / 350.0);
		double lowland = clamp01(1 - Math.max(c.meanElevation, 0) / 1800.0);
		c.fold = (float) (smoothstep(0.1, 0.45, foldNoise) * smoothstep(60, 260, c.relief));
		double thickness = 1700 * smoothstep(-0.55, 0.45, basinNoise) * (0.15 + 0.85 * lowland * (1 - rugged));
		if (c.meanElevation < -40) {
			// Shelves and the deep sea collect sediment.
			thickness += 400;
		}
		thickness = Math.max(thickness, 1500 * c.fold);
		c.sedimentBase = heightUnder(c.meanSurfaceY, thickness);

		// Beds tilt gently across a region, and fold in fold belts.
		double foldAmplitude = (25 + 450 * c.fold) / verticalMeters;
		c.warp = (float) (foldAmplitude * fractal(seed + 5, sx / 380, sz / 380, 2)
			+ 300 / verticalMeters * fractal(seed + 14, sx / 3000, sz / 3000, 1)
			+ 1.5 * gradient(seed + 6, x / 70.0, z / 70.0));

		boolean arid = c.precipitation < 350 && c.temperature > 8;
		if (arid && faciesNoise > -0.1) {
			c.basin = Basin.EVAPORITE;
		} else if (c.meanElevation < -40 || faciesNoise < -0.15) {
			c.basin = Basin.MARINE;
		} else if (faciesNoise < 0.3) {
			c.basin = Basin.CLASTIC;
		} else {
			c.basin = Basin.COAL;
		}
		c.basement = basementNoise < -0.4 ? Basement.GRANITE
			: basementNoise < -0.1 ? Basement.GNEISS
			: basementNoise < 0.2 ? Basement.STONE
			: basementNoise < 0.5 ? Basement.SCHIST
			: Basement.VOLCANIC;

		c.deepslateTop = 4 + (int) Math.round(3.5 * gradient(seed + 7, x / 40.0, z / 40.0));
		c.magmaTop = magmaTop + (int) Math.round(Math.max(1, deepWarp / 2) * gradient(seed + 10, x / 24.0, z / 24.0));
		c.peridotiteTop = Math.max(c.magmaTop + 2,
			peridotiteTop + (int) Math.round(deepWarp * 0.7 * gradient(seed + 9, x / 50.0, z / 50.0)));
		c.gabbroTop = Math.min(c.deepslateTop - 2, Math.max(c.peridotiteTop + 2,
			gabbroTop + (int) Math.round(deepWarp * gradient(seed + 8, x / 60.0, z / 60.0))));

		pipe(x, z, c);
	}

	/** The rock at a block of a column {@link #column} has prepared. */
	public Rock rock(Column c, int x, int y, int z) {
		if (y < c.magmaTop) {
			double v = value3(seed + 12, x, y, z, 3);
			return v < 0.5 ? Rock.MAGMA : v < 0.68 ? Rock.BLACKSTONE : Rock.BASALT;
		}
		if (c.pipe && y < c.pipeTop) {
			double t = clamp01((y - c.pipeBottom) / (double) Math.max(1, c.pipeTop - c.pipeBottom));
			// Kimberlite pipes are carrot-shaped: narrow at depth, widening towards the top.
			if (c.pipeDistance < 1.5 + (c.pipeRadiusTop - 1.5) * Math.sqrt(t)) {
				return Rock.KIMBERLITE;
			}
		}
		if (y < c.peridotiteTop) {
			return Rock.PERIDOTITE;
		}
		if (y < c.gabbroTop) {
			return Rock.GABBRO;
		}
		if (y < c.deepslateTop) {
			return Rock.DEEPSLATE;
		}
		if (y < c.sedimentBase) {
			return basement(c, x, y, z);
		}
		return sediment(c, y);
	}

	/** The ore at a block, or {@code null} for plain rock. */
	public Ore ore(Column c, int x, int y, int z, Rock host) {
		switch (host) {
			case COAL_SEAM:
				return unit(hash(seed + 20, x, y, z)) < Ore.COAL.grade ? Ore.COAL : null;
			case KIMBERLITE:
				double chance = y < c.deepslateTop - 4 ? 0.02 : 0.004;
				return unit(hash(seed + 20, x, y, z)) < chance ? Ore.DIAMOND : null;
			case MAGMA, BLACKSTONE, BASALT, CHALK, ROCK_SALT:
				return null;
			default:
				break;
		}
		int cx = Math.floorDiv(x, ORE_CELL_XZ);
		int cy = Math.floorDiv(y, ORE_CELL_Y);
		int cz = Math.floorDiv(z, ORE_CELL_XZ);
		long key = cellKey(cx, cy, cz);
		if (key != c.oreCell) {
			c.oreCell = key;
			c.oreHash = hash(seed + 21, cx, cy, cz);
			// Ore is commoner deep down. The chance depends on the cell alone, so a body is never cut off by a column
			// that disagrees.
			c.oreBody = unit(c.oreHash) < ((cy + 1) * ORE_CELL_Y <= 0 ? 0.14 : 0.09);
		}
		if (!c.oreBody) {
			return null;
		}
		long h = c.oreHash;
		Ore ore = mineral(host, unit2(h), y, c);
		if (ore == null) {
			return null;
		}
		long h2 = mix(h + 3);
		double rx = ore.minRadius + unit(h2) * (ore.maxRadius - ore.minRadius);
		double rz = rx * (0.7 + 0.5 * unit2(h2));
		double ry = Math.max(1, ore.maxHeight * (0.6 + 0.4 * unit(mix(h2 + 1))));
		double bodyX = cx * ORE_CELL_XZ + 7 + 6 * unit(mix(h2 + 2));
		double bodyY = cy * ORE_CELL_Y + 4.5 + 5 * unit(mix(h2 + 3));
		double bodyZ = cz * ORE_CELL_XZ + 7 + 6 * unit(mix(h2 + 4));
		double dx = (x + 0.5 - bodyX) / rx;
		double dy = (y + 0.5 - bodyY) / ry;
		double dz = (z + 0.5 - bodyZ) / rz;
		double d2 = dx * dx + dy * dy + dz * dz;
		if (d2 >= 1) {
			return null;
		}
		return unit(hash(seed + 22, x, y, z)) < ore.grade * (1.25 - d2) ? ore : null;
	}

	private void pipe(int x, int z, Column c) {
		c.pipe = false;
		int cell = (int) Math.round(180 * stretch);
		int cx = Math.floorDiv(x, cell);
		int cz = Math.floorDiv(z, cell);
		long h = hash(seed + 11, cx, 0, cz);
		if (unit(h) >= 0.12) {
			return;
		}
		double margin = 8;
		double px = cx * cell + margin + unit2(h) * (cell - 2 * margin);
		double pz = cz * cell + margin + unit(mix(h + 1)) * (cell - 2 * margin);
		double radius = 3 + unit(mix(h + 2)) * 3.5;
		double distance = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
		if (distance >= radius) {
			return;
		}
		c.pipe = true;
		c.pipeDistance = (float) distance;
		c.pipeRadiusTop = (float) radius;
		c.pipeBottom = c.magmaTop;
		double reach = unit(mix(h + 3));
		// A quarter of the pipes reach the surface, as the ones that were found first on Earth did.
		c.pipeTop = reach < 0.25 ? (int) c.surfaceY + 1 : c.deepslateTop + 10 + (int) (reach * 50);
	}

	private Rock basement(Column c, int x, int y, int z) {
		Rock lens = lens(c, x, y, z);
		if (lens != null) {
			return lens;
		}
		return switch (c.basement) {
			case GRANITE -> Rock.GRANITE;
			case GNEISS -> Rock.GNEISS;
			case STONE -> Rock.STONE;
			case SCHIST -> Rock.SCHIST;
			// Lava flows with ash beds between them.
			case VOLCANIC -> bedThin[bed(c, y)] ? Rock.TUFF : Rock.ANDESITE;
		};
	}

	/** Bodies of another rock inside the basement: intrusions, and marble lenses in schist. */
	private Rock lens(Column c, int x, int y, int z) {
		int cx = Math.floorDiv(x, LENS_CELL_XZ);
		int cy = Math.floorDiv(y, LENS_CELL_Y);
		int cz = Math.floorDiv(z, LENS_CELL_XZ);
		long key = cellKey(cx, cy, cz) ^ ((long) c.basement.ordinal() << 61);
		if (key != c.lensCell) {
			c.lensCell = key;
			long h = hash(seed + 13, cx, cy, cz);
			c.lensRock = null;
			if (unit(h) < 0.35) {
				double t = unit2(h);
				c.lensRock = switch (c.basement) {
					case GRANITE, VOLCANIC -> Rock.DIORITE;
					case GNEISS -> Rock.GRANITE;
					case STONE -> t < 0.45 ? Rock.GRANITE : t < 0.75 ? Rock.DIORITE : Rock.ANDESITE;
					case SCHIST -> Rock.MARBLE;
				};
				long h2 = mix(h + 5);
				c.lensRx = 4 + 6 * unit(h2);
				c.lensRz = 4 + 6 * unit2(h2);
				c.lensRy = 2 + 4 * unit(mix(h2 + 1));
				c.lensX = cx * LENS_CELL_XZ + 10 + 8 * unit(mix(h2 + 2));
				c.lensY = cy * LENS_CELL_Y + 6 + 6 * unit(mix(h2 + 3));
				c.lensZ = cz * LENS_CELL_XZ + 10 + 8 * unit(mix(h2 + 4));
			}
		}
		if (c.lensRock == null) {
			return null;
		}
		double dx = (x + 0.5 - c.lensX) / c.lensRx;
		double dy = (y + 0.5 - c.lensY) / c.lensRy;
		double dz = (z + 0.5 - c.lensZ) / c.lensRz;
		return dx * dx + dy * dy + dz * dz < 1 ? c.lensRock : null;
	}

	private Rock sediment(Column c, int y) {
		int bed = bed(c, y);
		float roll = bedRoll[bed];
		Rock rock = switch (c.basin) {
			case MARINE -> roll < 0.42f ? Rock.LIMESTONE : roll < 0.66f ? Rock.SHALE
				: roll < 0.80f ? Rock.CHALK : Rock.SANDSTONE;
			case CLASTIC -> roll < 0.48f ? Rock.SANDSTONE : roll < 0.82f ? Rock.SHALE
				: roll < 0.92f ? Rock.LIMESTONE : Rock.RED_SANDSTONE;
			case COAL -> bedThin[bed] && roll < 0.7f ? Rock.COAL_SEAM : roll < 0.48f ? Rock.SHALE
				: roll < 0.85f ? Rock.SANDSTONE : Rock.LIMESTONE;
			case EVAPORITE -> roll < 0.36f ? Rock.RED_SANDSTONE : roll < 0.58f ? Rock.LIMESTONE
				: roll < 0.78f ? Rock.SHALE : Rock.ROCK_SALT;
		};
		if (c.fold > 0.25f) {
			// Buried and squeezed: mudstone becomes slate, then schist; limestone becomes marble.
			double depthMeters = metersUnder(c.meanSurfaceY, y);
			if (depthMeters > 250) {
				rock = switch (rock) {
					case SHALE -> depthMeters > 900 ? Rock.SCHIST : Rock.SLATE;
					case LIMESTONE, CHALK -> Rock.MARBLE;
					default -> rock;
				};
			}
		}
		return rock;
	}

	private int bed(Column c, int y) {
		return bedAt[Math.floorMod((int) Math.floor(y + c.warp), PERIOD)];
	}

	/** The height {@code meters} of rock below a mean surface, at the world's scale down to the sea and below it. */
	private float heightUnder(float meanSurfaceY, double meters) {
		double aboveSea = Math.max(0, meanSurfaceY - SEA_LEVEL) * verticalMeters;
		if (meters <= aboveSea) {
			return (float) (meanSurfaceY - meters / verticalMeters);
		}
		return (float) (Math.min(meanSurfaceY, SEA_LEVEL) - (meters - aboveSea) / undergroundMeters);
	}

	/** The inverse of {@link #heightUnder}: the meters of rock between a mean surface and {@code y}. */
	private double metersUnder(float meanSurfaceY, int y) {
		double sea = Math.min(meanSurfaceY, SEA_LEVEL);
		return Math.max(0, meanSurfaceY - Math.max(y, sea)) * verticalMeters
			+ Math.max(0, sea - y) * undergroundMeters;
	}

	/** The mineral an ore body forms in a host rock. {@code t} in [0, 1) is the body's own roll. */
	private static Ore mineral(Rock host, double t, int y, Column c) {
		boolean deep = y < c.deepslateTop + 12;
		return switch (host) {
			case GRANITE, DIORITE -> t < 0.5 ? Ore.COPPER : t < 0.78 ? Ore.GOLD : Ore.IRON;
			case ANDESITE -> t < 0.55 ? Ore.COPPER : t < 0.8 ? Ore.GOLD : Ore.SULFUR;
			case TUFF -> t < 0.4 ? Ore.SULFUR : t < 0.7 ? Ore.CINNABAR : Ore.GOLD;
			case STONE -> t < 0.45 ? Ore.IRON : t < 0.7 ? Ore.COPPER : t < 0.85 ? Ore.GOLD
				: deep ? Ore.REDSTONE : Ore.IRON;
			case GNEISS -> t < 0.4 ? Ore.IRON : t < 0.75 ? Ore.GOLD : Ore.COPPER;
			case SCHIST -> t < 0.3 ? Ore.GOLD : t < 0.55 ? Ore.IRON
				: t < 0.75 && c.meanElevation > 500 ? Ore.EMERALD : Ore.COPPER;
			case MARBLE -> t < 0.6 ? Ore.LAPIS : Ore.IRON;
			case LIMESTONE -> t < 0.45 ? Ore.IRON : t < 0.7 ? Ore.COPPER : null;
			case SHALE, SLATE -> t < 0.55 ? Ore.IRON : t < 0.8 ? Ore.COPPER : Ore.GOLD;
			case SANDSTONE, RED_SANDSTONE -> t < 0.45 ? Ore.IRON : t < 0.75 ? Ore.COPPER : null;
			case DEEPSLATE -> t < 0.32 ? Ore.REDSTONE : t < 0.55 ? Ore.IRON : t < 0.72 ? Ore.GOLD
				: t < 0.85 ? Ore.LAPIS : t < 0.93 ? Ore.DIAMOND : Ore.COPPER;
			case GABBRO -> t < 0.3 ? Ore.IRON : t < 0.5 ? Ore.COPPER : t < 0.75 ? Ore.REDSTONE
				: t < 0.88 ? Ore.DIAMOND : Ore.GOLD;
			case PERIDOTITE -> t < 0.25 ? Ore.DIAMOND : t < 0.5 ? Ore.REDSTONE : t < 0.75 ? Ore.SULFUR : Ore.CINNABAR;
			default -> null;
		};
	}

	private static long cellKey(int cx, int cy, int cz) {
		return ((long) cx & 0x1FFFFFL) | (((long) cz & 0x1FFFFFL) << 21) | (((long) cy & 0x7FFFFL) << 42);
	}
}
