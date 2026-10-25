package io.github.elpideus.diffusicraft.worldgen.geology;

import java.util.Arrays;
import java.util.Set;

import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.clamp01;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.fractal;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.gradient;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.gradient3;
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
 * <p>As in vanilla, the ground is mostly stone above y=0 and deepslate below; the other rocks come in beds, bands
 * and bodies in it. From the top down, as on Earth:
 * <ol>
 *     <li><b>Sedimentary cover</b> in beds a few blocks thick. The beds follow the height, not the surface, so cliffs
 *     and valley walls cut through them and show the bands. Lowland basins collect a thick cover, rugged uplands a
 *     thin one. Most beds are plain stone; what the others are made of depends on the basin: limestone, chalk and
 *     shale where there was a sea; shale and a little limestone and sandstone on land; coal seams in swampy basins;
 *     limestone, shale and rock salt in arid ones. Only in the deserts are the beds mostly sandstone and red
 *     sandstone. In fold belts (rugged land over thick sediment) the beds are folded steeply, and the deep ones turn
 *     into slate, marble and schist.</li>
 *     <li><b>Basement</b>: the old crust under the cover, stone with the rock of its region in it: granite plutons,
 *     bands of gneiss or schist, or lava flows of andesite with tuff beds between them, and lenses of other rocks.</li>
 *     <li><b>Lower crust</b> from about y=4 down: deepslate, as in vanilla, then a few blocks of gabbro.</li>
 *     <li><b>Mantle</b>: a few blocks of peridotite, pierced by rare kimberlite pipes that carry diamonds upwards.</li>
 *     <li><b>The magma layer</b>: magma, blackstone and basalt just above the bedrock.</li>
 * </ol>
 * Ores come in bodies whose mineral depends on the rock around them (copper and gold in granite, lapis in marble,
 * emerald in mountain schist, diamonds deep down), so prospecting by rock pays off. Where tech mods add them, the
 * bodies hold their minerals too: zinc and lead in limestone, tin in granite, nickel in gabbro. See docs/WORLDGEN.md.
 *
 * <p>The hard beds of the cover stand out of slopes as ledges, and the soft beds between them recede into benches:
 * {@link #ledge}.
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
	/** Where {@link #basement} switches from one kind of basement to the next, in the basement noise. */
	private static final double[] BASEMENT_EDGES = {-0.4, -0.1, 0.2, 0.5};
	/**
	 * How well each kind of basement resists erosion, in the order of {@link Basement}: granite, gneiss and plain
	 * stone are hard, schist splits along its foliation, and lava flows alternate with soft ash.
	 */
	private static final double[] BASEMENT_HARDNESS = {1.0, 0.85, 0.65, 0.35, 0.6};
	/** The hardness of flat-lying beds: stone, limestone and sandstone between shale, chalk and salt. */
	private static final double SEDIMENT_HARDNESS = 0;
	/** The hardness of folded beds, turned to slate, marble and schist. */
	private static final double FOLDED_HARDNESS = 0.35;
	/** Blocks over which the hardness passes from the basement to the cover above it. */
	private static final double CONTACT = 4;
	/** The share of the basement's bands that are of its region's rock, gneiss, schist or andesite; the rest is stone. */
	private static final double BAND_SHARE = 0.35;
	/** Where the granite of a granite basement is: above this in its noise, in about two fifths of the ground. */
	private static final double PLUTON = 0.12;
	/** Basement noise over which the hardness passes from one kind of basement to the next. */
	private static final double BASEMENT_BLEND = 0.05;
	/**
	 * Thick beds whose roll is below this are hard. Thin beds go with the beds around them: see {@link #hardBeds}. Hard
	 * beds are stone, limestone and sandstone, soft ones shale, chalk, rock salt, coal and stone.
	 */
	private static final float HARD_SHARE = 0.6f;
	/** How many times steeper than the slope around them the cliffs of hard beds stand. */
	private static final double LEDGE_STEEPNESS = 3;
	/** The tallest cliff a run of hard beds makes, in blocks; thicker runs keep the slope's own pace below it. */
	private static final double MAX_CLIFF = 15;
	/**
	 * The most times gentler than the slope the soft beds above a cliff lie. A cliff stands out no further than they
	 * can recede.
	 */
	private static final double MAX_BENCH = 5;
	/** The fold at which ledges start to fade, and the fold at which steeply tilted beds no longer make any. */
	private static final double LEDGE_FOLD = 0.15;
	private static final double NO_LEDGE_FOLD = 0.35;
	/**
	 * The fewest meters a block stands for below the sea, about the smaller sizes' vertical scale. The underground is
	 * sized for play, not for the real crust. Otherwise, in the tall worlds, a lowland basin's 1.7 km of sediment
	 * would bury the basement and reach below the bottom of the world.
	 */
	private static final double UNDERGROUND_METERS = 10.0;
	/**
	 * The lowest basement noise of the {@link #sulfurous} ground: the volcanic side of the basement, its lava fields and
	 * the schist belts around them, about a tenth of the ground.
	 */
	private static final double SULFUR_BASEMENT = 0.25;
	/** Where the sulfurous ground lies in that part of the basement: above this in its noise, in about a third of it. */
	private static final double SULFUR_PATCH = 0.1;
	/** Above this in the facies noise, an arid basin is an {@link Basin#EVAPORITE} one. */
	private static final double EVAPORITE_FACIES = -0.1;

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
		/** Desert: the cover is of sandstone and red sandstone, as in the deserts' ergs and red beds. */
		boolean desert;
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
	/** Which beds hold up ledges: see {@link #hardBeds}. */
	private final boolean[] bedHard;
	/**
	 * {@link #ledge}'s profile: at the bottom of each block of the bed sequence, how much further along the slope's
	 * smooth height has come than the ground. It is linear within a block.
	 */
	private final float[] ledgeOffset = new float[PERIOD];

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
		// The deep layers share out the room between the bottom of the world and y=0, 64 blocks in vanilla's height
		// and 256 in tall worlds, leaving most of it to the deepslate.
		int depth = Math.max(64, -minY);
		magmaTop = minY + 4 + Math.max(5, depth / 16);
		peridotiteTop = magmaTop + Math.max(3, depth / 20);
		gabbroTop = peridotiteTop + Math.max(5, depth / 12);
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
		bedHard = hardBeds();
		ledges();
	}

	/**
	 * Builds the profile of {@link #ledge}. Picture a slope cut through the beds. The top of each run of hard beds
	 * stands as a cliff {@link #LEDGE_STEEPNESS} times steeper than the slope, so the ground climbs above the slope's
	 * line; the run of soft beds above it lies gentler and brings the ground back to the line by its top, so thin soft
	 * beds over a thick hard run become benches. The cliffs are at most {@link #MAX_CLIFF} blocks tall: below that, a
	 * thicker run climbs at the slope's pace, as under a cliff whose foot is buried in its own debris.
	 *
	 * <p>The profile is kept as the smooth height at which the ground reaches each block of the sequence, less the
	 * block's own height, so that weaker ledges come from scaling it without moving them off the beds that make them.
	 * Its mean is taken off, which keeps the ground at the slope's height on average at every strength.
	 */
	private void ledges() {
		boolean[] hard = bedHard;
		double steeper = 1 - 1 / LEDGE_STEEPNESS;
		double[] ratio = new double[PERIOD];
		Arrays.fill(ratio, 1);
		for (int y = 0; y < PERIOD; ) {
			if (!hard[bedAt[y]]) {
				y++;
				continue;
			}
			int top = y;
			while (top < PERIOD && hard[bedAt[top]]) {
				top++;
			}
			int end = top;
			while (end < PERIOD && !hard[bedAt[end]]) {
				end++;
			}
			if (end == top) {
				// The sequence ends in hard beds, with none above to bring the ground back.
				break;
			}
			double lift = Math.min(Math.min(top - y, MAX_CLIFF) * steeper, (MAX_BENCH - 1) * (end - top));
			double cliff = lift / steeper;
			for (int i = y; i < end; i++) {
				// Smooth height per block of the ground's height: less in the cliff, more in the soft run.
				ratio[i] = i >= top ? 1 + lift / (end - top) : 1 - steeper * Math.clamp(cliff - (top - 1 - i), 0, 1);
			}
			y = end;
		}
		// Every pair of runs ends where it started, so the offset comes back to zero at the top of the sequence.
		double[] offset = new double[PERIOD + 1];
		double sum = 0;
		for (int y = 0; y < PERIOD; y++) {
			offset[y + 1] = offset[y] + ratio[y] - 1;
			sum += offset[y] + (ratio[y] - 1) / 2;
		}
		double mean = sum / PERIOD;
		for (int y = 0; y < PERIOD; y++) {
			ledgeOffset[y] = (float) (offset[y] - mean);
		}
	}

	/**
	 * Which beds hold up ledges: see {@link #HARD_SHARE}. A thin bed goes with the thick beds around it: one between two
	 * hard beds is a parting or a coal seam in their cliff, and one next to a soft bed recedes with it.
	 */
	private boolean[] hardBeds() {
		int beds = bedRoll.length;
		boolean[] hard = new boolean[beds];
		for (int bed = 0; bed < beds; bed++) {
			hard[bed] = !bedThin[bed] && bedRoll[bed] < HARD_SHARE;
		}
		for (int bed = 0; bed < beds; bed++) {
			if (bedThin[bed]) {
				int below = bed - 1;
				while (below >= 0 && bedThin[below]) {
					below--;
				}
				int above = bed + 1;
				while (above < beds && bedThin[above]) {
					above++;
				}
				hard[bed] = below >= 0 && above < beds && hard[below] && hard[above];
			}
		}
		return hard;
	}

	/** Derives a column's geology from its terrain inputs. */
	public void column(int x, int z, Column c) {
		double sx = x / stretch;
		double sz = z / stretch;
		double faciesNoise = faciesNoise(sx, sz);
		double basementNoise = basementNoise(sx, sz);
		c.fold = fold(sx, sz, c.relief);
		c.sedimentBase = sedimentBase(sx, sz, c.fold, c.meanSurfaceY, c.meanElevation, c.relief);
		c.warp = (float) warp(x, z, sx, sz, c.fold);

		boolean arid = arid(c.temperature, c.precipitation);
		// Köppen's desert: under half the rain the warmth would dry up.
		c.desert = arid && c.precipitation < 10 * c.temperature + 70;
		if (arid && faciesNoise > EVAPORITE_FACIES) {
			c.basin = Basin.EVAPORITE;
		} else if (c.meanElevation < -40 || faciesNoise < -0.15) {
			c.basin = Basin.MARINE;
		} else if (faciesNoise < 0.3) {
			c.basin = Basin.CLASTIC;
		} else {
			c.basin = Basin.COAL;
		}
		c.basement = basementNoise < BASEMENT_EDGES[0] ? Basement.GRANITE
			: basementNoise < BASEMENT_EDGES[1] ? Basement.GNEISS
			: basementNoise < BASEMENT_EDGES[2] ? Basement.STONE
			: basementNoise < BASEMENT_EDGES[3] ? Basement.SCHIST
			: Basement.VOLCANIC;

		c.deepslateTop = (int) Math.round(deepslateTop(x, z));
		c.magmaTop = magmaTop + (int) Math.round(Math.max(1, deepWarp / 2) * gradient(seed + 10, x / 24.0, z / 24.0));
		c.peridotiteTop = Math.max(c.magmaTop + 2,
			peridotiteTop + (int) Math.round(deepWarp * 0.7 * gradient(seed + 9, x / 50.0, z / 50.0)));
		c.gabbroTop = Math.min(c.deepslateTop - 2, Math.max(c.peridotiteTop + 2,
			gabbroTop + (int) Math.round(deepWarp * gradient(seed + 8, x / 60.0, z / 60.0))));

		pipe(x, z, c);
	}

	/**
	 * The rock under a point of the ground, which shapes the slope there: see {@link #hardness} and {@link #ledge}.
	 * The terrain inputs are those of {@link Column}. A site is cheap; make one per point.
	 */
	public Site site(double x, double z, float meanSurfaceY, float meanElevation, float relief) {
		double sx = x / stretch;
		double sz = z / stretch;
		float fold = fold(sx, sz, relief);
		return new Site(x, z, sx, sz, fold, sedimentBase(sx, sz, fold, meanSurfaceY, meanElevation, relief),
			deepslateTop(x, z));
	}

	/** What {@link #site} works out once for a point, for {@link #hardness} and {@link #ledge}. */
	public static final class Site {
		private final double x;
		private final double z;
		private final double sx;
		private final double sz;
		private final float fold;
		private final float sedimentBase;
		/** Where the beds start: at the sedimentary base, or above the deepslate where that lies higher. */
		private final double bedsBase;

		private Site(double x, double z, double sx, double sz, float fold, float sedimentBase, double deepslateTop) {
			this.x = x;
			this.z = z;
			this.sx = sx;
			this.sz = sz;
			this.fold = fold;
			this.sedimentBase = sedimentBase;
			this.bedsBase = Math.max(sedimentBase, deepslateTop);
		}
	}

	/** {@link #hardness(Site, double)} at a point given by its terrain inputs, those of {@link Column}. */
	public double hardness(double x, double z, double y, float meanSurfaceY, float meanElevation, float relief) {
		return hardness(site(x, z, meanSurfaceY, meanElevation, relief), y);
	}

	/**
	 * How well the ground at height {@code y} of a site resists erosion, from 0 (flat beds of stone and limestone
	 * between shale, chalk and salt) to 1 (granite). It is the bulk of the rock there, not the bed at {@code y}:
	 * landforms are carved over many beds. It changes smoothly across the contacts between rocks, so the shapes it
	 * gives the ground have no seams.
	 */
	public double hardness(Site site, double y) {
		double noise = basementNoise(site.sx, site.sz);
		double basement = BASEMENT_HARDNESS[0];
		for (int i = 0; i < BASEMENT_EDGES.length; i++) {
			basement += (BASEMENT_HARDNESS[i + 1] - BASEMENT_HARDNESS[i])
				* smoothstep(BASEMENT_EDGES[i] - BASEMENT_BLEND, BASEMENT_EDGES[i] + BASEMENT_BLEND, noise);
		}
		double cover = SEDIMENT_HARDNESS + (FOLDED_HARDNESS - SEDIMENT_HARDNESS) * smoothstep(0.25, 0.6, site.fold);
		return basement + (cover - basement) * smoothstep(site.sedimentBase - CONTACT, site.sedimentBase + CONTACT, y);
	}

	/**
	 * How far the beds of the sedimentary cover move the ground of a steep slope at smooth height {@code y} of a site,
	 * in blocks: up in the hard beds, which stand out as ledges and cliffs, and back down in the soft beds above them,
	 * which recede into gentler slopes and benches, as in the Grand Canyon. Over a run of beds it averages out, so the
	 * slope keeps its height. It is zero in the basement and the deepslate, and fades in fold belts, whose beds tilt too
	 * steeply to make ledges. It changes smoothly with the height and the site.
	 *
	 * @param strength how far the ledges stand out, from 0 to 1, by how steep and how bare the slope is; weaker ledges
	 *                 stand on the same beds
	 */
	public double ledge(Site site, double y, double strength) {
		double s = strength * smoothstep(site.bedsBase - CONTACT, site.bedsBase + CONTACT, y)
			* (1 - smoothstep(LEDGE_FOLD, NO_LEDGE_FOLD, site.fold));
		if (s == 0) {
			return 0;
		}
		// The beds lie where rock() has them. A block takes the bed at its bottom, so the beds step a whole block
		// where the warp passes a whole number; the ledges keep to the blocks' beds, and move across over the last
		// quarter of each step so the ground has no seams.
		double warp = (float) warp(site.x, site.z, site.sx, site.sz, site.fold);
		double whole = Math.floor(warp);
		double u = y + whole + smoothstep(0.75, 1, warp - whole);
		// The ground is at the point w of the sequence whose smooth height, w + s * offset(w), is the slope's; that
		// rises with w, linearly within each block, so find the block.
		int b = (int) Math.floor(u);
		double below = b + s * ledgeOffset(b) - u;
		while (below > 0) {
			b--;
			below = b + s * ledgeOffset(b) - u;
		}
		double above = b + 1 + s * ledgeOffset(b + 1) - u;
		while (above <= 0) {
			b++;
			below = above;
			above = b + 1 + s * ledgeOffset(b + 1) - u;
		}
		return b - below / (above - below) - u;
	}

	/**
	 * Whether water rich in hydrogen sulfide rises through the ground at a point, and has eaten sulfur caves out of it,
	 * as at Frasassi in Italy or Movile in Romania: in patches over the volcanic side of the basement, whose magma left
	 * the sulfur behind. Patches a few hundred blocks across (more at the finer scales, as every region is) over about
	 * one part of the ground in thirty.
	 */
	public boolean sulfurous(double x, double z) {
		double sx = x / stretch;
		double sz = z / stretch;
		return basementNoise(sx, sz) >= SULFUR_BASEMENT && fractal(seed + 18, sx / 160, sz / 160, 2) > SULFUR_PATCH;
	}

	/**
	 * Whether the beds under a point were laid down in an {@link Basin#EVAPORITE} basin, as {@link #column} decides:
	 * the salt of seas and lakes that dried up under an arid sky, in which water cuts salt caves.
	 *
	 * @param temperature   the annual mean temperature there, °C
	 * @param precipitation the annual precipitation there, mm
	 */
	public boolean evaporite(double x, double z, float temperature, float precipitation) {
		return arid(temperature, precipitation) && faciesNoise(x / stretch, z / stretch) > EVAPORITE_FACIES;
	}

	private static boolean arid(float temperature, float precipitation) {
		return precipitation < 350 && temperature > 8;
	}

	private double faciesNoise(double sx, double sz) {
		return fractal(seed + 2, sx / 650, sz / 650, 2);
	}

	/** The top of the deepslate at a point, before rounding to a block. */
	private double deepslateTop(double x, double z) {
		return 4 + 3.5 * gradient(seed + 7, x / 40.0, z / 40.0);
	}

	private double ledgeOffset(int y) {
		return ledgeOffset[Math.floorMod(y, PERIOD)];
	}

	/** How far the beds are shifted up at a point: they tilt gently across a region, and fold in fold belts. */
	private double warp(double x, double z, double sx, double sz, float fold) {
		double foldAmplitude = (25 + 450 * fold) / verticalMeters;
		return foldAmplitude * fractal(seed + 5, sx / 380, sz / 380, 2)
			+ 300 / verticalMeters * fractal(seed + 14, sx / 3000, sz / 3000, 1)
			+ 1.5 * gradient(seed + 6, x / 70.0, z / 70.0);
	}

	private double basementNoise(double sx, double sz) {
		return fractal(seed + 3, sx / 560, sz / 560, 3);
	}

	/** How folded the beds are; see {@link Column#fold()}. */
	private float fold(double sx, double sz, float relief) {
		double foldNoise = fractal(seed + 4, sx / 1100, sz / 1100, 2);
		return (float) (smoothstep(0.1, 0.45, foldNoise) * smoothstep(60, 260, relief));
	}

	/** The height below which the sedimentary cover gives way to the basement. */
	private float sedimentBase(double sx, double sz, float fold, float meanSurfaceY, float meanElevation,
							   float relief) {
		double basinNoise = fractal(seed + 1, sx / 900, sz / 900, 3);
		double rugged = clamp01(relief / 350.0);
		double lowland = clamp01(1 - Math.max(meanElevation, 0) / 1800.0);
		double thickness = 1700 * smoothstep(-0.55, 0.45, basinNoise) * (0.15 + 0.85 * lowland * (1 - rugged));
		if (meanElevation < -40) {
			// Shelves and the deep sea collect sediment.
			thickness += 400;
		}
		thickness = Math.max(thickness, 1500 * fold);
		return heightUnder(meanSurfaceY, thickness);
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

	/** The ore at a block, or {@code null} for plain rock, with vanilla's minerals alone. */
	public Ore ore(Column c, int x, int y, int z, Rock host) {
		return ore(c, x, y, z, host, Ore.VANILLA);
	}

	/**
	 * The ore at a block, or {@code null} for plain rock. Some bodies are of the minerals of tech mods, where
	 * {@code available} holds them; where it does not, those bodies are of vanilla's minerals, so the rock is as rich
	 * without the mods.
	 */
	public Ore ore(Column c, int x, int y, int z, Rock host, Set<Ore> available) {
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
		Ore modded = moddedMineral(host, unit(mix(h + 7)));
		if (modded != null && available.contains(modded)) {
			ore = modded;
		}
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
			case GRANITE -> pluton(x, y, z) ? Rock.GRANITE : Rock.STONE;
			case GNEISS -> band(c, y) ? Rock.GNEISS : Rock.STONE;
			case STONE -> Rock.STONE;
			case SCHIST -> band(c, y) ? Rock.SCHIST : Rock.STONE;
			// Lava flows with ash beds between them.
			case VOLCANIC -> bedThin[bed(c, y)] ? Rock.TUFF : band(c, y) ? Rock.ANDESITE : Rock.STONE;
		};
	}

	/** Whether a block of a granite basement is in one of its plutons, bodies a few dozen blocks across. */
	private boolean pluton(int x, int y, int z) {
		return gradient3(seed + 15, x / 40.0, y / 28.0, z / 40.0)
			+ 0.4 * gradient3(seed + 16, x / 14.0, y / 10.0, z / 14.0) > PLUTON;
	}

	/** Whether a block of the basement is in a band of its region's rock: see {@link #BAND_SHARE}. */
	private boolean band(Column c, int y) {
		return unit(hash(seed + 17, bed(c, y), 0, 0)) < BAND_SHARE;
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
		// The hard rocks lie in the beds that hold up the ledges and the soft ones in those that recede, so the ledges
		// stand where they show; stone lies in both. Most beds are stone, but in the deserts. The rolls are spread
		// evenly over each kind of bed.
		float r = roll < HARD_SHARE ? roll / HARD_SHARE : (roll - HARD_SHARE) / (1 - HARD_SHARE);
		Rock rock;
		if (c.basin == Basin.COAL && bedThin[bed] && roll < 0.7f) {
			rock = Rock.COAL_SEAM;
		} else if (bedHard[bed]) {
			rock = switch (c.basin) {
				case MARINE -> r < 0.7f ? Rock.STONE : Rock.LIMESTONE;
				case CLASTIC -> c.desert ? r < 0.73f ? Rock.SANDSTONE : r < 0.87f ? Rock.LIMESTONE : Rock.RED_SANDSTONE
					: r < 0.75f ? Rock.STONE : r < 0.88f ? Rock.LIMESTONE : Rock.SANDSTONE;
				case COAL -> r < 0.75f ? Rock.STONE : r < 0.92f ? Rock.LIMESTONE : Rock.SANDSTONE;
				case EVAPORITE -> c.desert ? r < 0.3f ? Rock.SANDSTONE : r < 0.6f ? Rock.RED_SANDSTONE : Rock.LIMESTONE
					: r < 0.65f ? Rock.STONE : r < 0.72f ? Rock.RED_SANDSTONE : Rock.LIMESTONE;
			};
		} else {
			rock = switch (c.basin) {
				case MARINE -> r < 0.4f ? Rock.SHALE : r < 0.55f ? Rock.CHALK : Rock.STONE;
				case CLASTIC -> c.desert || r < 0.4f ? Rock.SHALE : Rock.STONE;
				case COAL -> r < 0.45f ? Rock.SHALE : Rock.STONE;
				case EVAPORITE -> c.desert ? r < 0.45f ? Rock.SHALE : Rock.ROCK_SALT
					: r < 0.35f ? Rock.SHALE : r < 0.55f ? Rock.ROCK_SALT : Rock.STONE;
			};
		}
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

	/** Whether the bed of the cover at {@code y} holds up ledges; see {@link #hardBeds}. */
	boolean hardBed(Column c, int y) {
		return bedHard[bed(c, y)];
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

	/**
	 * The mineral of a tech mod that a body in a host rock is, if a mod adds it, or {@code null} for vanilla's. As on
	 * Earth: zinc and lead in limestone and shale, tin and tungsten in granite, silver in volcanic rock, nickel and the
	 * platinum metals in the mafic rocks, uranium in sandstone, rubies in marble.
	 */
	private static Ore moddedMineral(Rock host, double t) {
		return switch (host) {
			case LIMESTONE -> t < 0.28 ? Ore.ZINC : t < 0.44 ? Ore.LEAD : t < 0.52 ? Ore.FLUORITE : null;
			case SHALE, SLATE -> t < 0.18 ? Ore.ZINC : t < 0.3 ? Ore.LEAD : t < 0.38 ? Ore.SILVER
				: t < 0.43 ? Ore.URANIUM : null;
			case SANDSTONE, RED_SANDSTONE -> t < 0.15 ? Ore.URANIUM : null;
			case MARBLE -> t < 0.15 ? Ore.RUBY : null;
			case GRANITE -> t < 0.22 ? Ore.TIN : t < 0.32 ? Ore.TUNGSTEN : t < 0.38 ? Ore.URANIUM
				: t < 0.43 ? Ore.FLUORITE : null;
			case ANDESITE, TUFF -> t < 0.2 ? Ore.SILVER : t < 0.32 ? Ore.ZINC : t < 0.4 ? Ore.LEAD : null;
			case STONE -> t < 0.1 ? Ore.ZINC : t < 0.18 ? Ore.TIN : t < 0.26 ? Ore.LEAD : t < 0.32 ? Ore.SILVER
				: t < 0.37 ? Ore.NICKEL : null;
			case GNEISS -> t < 0.08 ? Ore.TIN : t < 0.14 ? Ore.SAPPHIRE : t < 0.19 ? Ore.URANIUM : null;
			case SCHIST -> t < 0.08 ? Ore.SAPPHIRE : t < 0.14 ? Ore.TUNGSTEN : null;
			case DEEPSLATE -> t < 0.1 ? Ore.NICKEL : t < 0.16 ? Ore.SILVER : t < 0.21 ? Ore.URANIUM
				: t < 0.26 ? Ore.TUNGSTEN : null;
			case GABBRO -> t < 0.25 ? Ore.NICKEL : t < 0.35 ? Ore.PLATINUM : t < 0.43 ? Ore.OSMIUM
				: t < 0.47 ? Ore.IRIDIUM : null;
			case PERIDOTITE -> t < 0.2 ? Ore.NICKEL : t < 0.3 ? Ore.PLATINUM : t < 0.4 ? Ore.OSMIUM
				: t < 0.45 ? Ore.IRIDIUM : t < 0.55 ? Ore.PERIDOT : null;
			default -> null;
		};
	}

	private static long cellKey(int cx, int cy, int cz) {
		return ((long) cx & 0x1FFFFFL) | (((long) cz & 0x1FFFFFL) << 21) | (((long) cy & 0x7FFFFL) << 42);
	}
}
