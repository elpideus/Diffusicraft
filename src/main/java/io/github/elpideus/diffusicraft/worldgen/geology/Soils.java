package io.github.elpideus.diffusicraft.worldgen.geology;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;

import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.clamp01;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.fractal;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.value3;

/**
 * The soil under the ground's top block: what it is made of and how deep it goes. A soil is the rock beneath it,
 * weathered by the climate where it lies, so both decide:
 * <ul>
 *     <li><b>The climate</b> sets how deep the rock has rotted. Weathering is a chemical reaction that warmth and rain
 *     drive, so profiles are a few blocks deep in cold or dry land and many in the wet tropics, where the rock rots
 *     to laterite. A blanket of wind-blown loess covers the cool, dry plains whatever lies under it, and the
 *     semi-arid land grows a calcrete pan where the rain stops sinking in.</li>
 *     <li><b>The rock</b> decides the rest: sandstone weathers to sand, shale to clay, granite to grus (the gravel of
 *     its crystals), and limestone dissolves away, leaving a thin dark soil on it in the cool and the red terra rossa
 *     in the warm.</li>
 *     <li><b>The slope</b> thins the profile: soil creeps off slopes and builds up on the flat.</li>
 * </ul>
 * From the top down, a profile has the biome's top block, a block or two of topsoil, the subsoil, perhaps the pan,
 * and the weathered rock, broken rock with the subsoil between it that gives way downwards to the solid rock. Depths
 * are in blocks, the same in every world type: the underground keeps game sizes (see {@link Geology}).
 *
 * <p>Plain Java with no state, like {@link Geology}; the blocks are chosen in {@code StrataRule}.
 */
public final class Soils {
	/** What the subsoil is made of. */
	public enum Soil {
		/** Plain soil: dirt. */
		LOAM,
		/** Heavy clay soil, over shale and slate in wet land, and the waterlogged gley under peat and mud. */
		CLAY,
		/** Sandy soil over sandstone. */
		SAND,
		/** Sandy soil over red sandstone. */
		RED_SAND,
		/** Weathered granite and gneiss: the coarse sand and gravel of their crystals. */
		GRUS,
		/** The red clay left where limestone dissolves in warm land with dry summers. */
		TERRA_ROSSA,
		/** The iron-rich red earth of the warm, wet tropics, deeply weathered from any rock. */
		LATERITE,
		/** Wind-blown silt over the cool, dry plains. */
		LOESS
	}

	/** What lies at a depth of a profile. */
	public enum Horizon {
		/** The biome's own ground. */
		TOPSOIL,
		SUBSOIL,
		/** Calcrete: lime that the rain carried down and left where it stopped sinking in. */
		PAN,
		/** Broken rock with the subsoil between it. */
		WEATHERED,
		/** The solid rock. */
		ROCK
	}

	/**
	 * One column's soil. {@link Soils#profile} fills it; depths count the column's top block as 1, as
	 * {@code stoneDepthAbove} does in the material rules. Not thread-safe: keep one per thread.
	 */
	public static final class Profile {
		Soil soil;
		int topsoil;
		int subsoil;
		int pan;
		int weathered;
		boolean bauxite;

		public Soil soil() {
			return soil;
		}

		/** The deepest block of topsoil. */
		public int topsoil() {
			return topsoil;
		}

		/** The deepest block of subsoil. */
		public int subsoil() {
			return subsoil;
		}

		/** The deepest block of weathered rock: the rock starts below it. */
		public int weathered() {
			return weathered;
		}

		/** Whether bauxite lies in the subsoil: see {@link Soils#bauxite}. */
		public boolean hasBauxite() {
			return bauxite;
		}

		/** What lies {@code depth} blocks down. */
		public Horizon horizon(int depth) {
			if (depth <= topsoil) {
				return Horizon.TOPSOIL;
			}
			if (depth <= subsoil) {
				return Horizon.SUBSOIL;
			}
			if (depth <= pan) {
				return Horizon.PAN;
			}
			return depth <= weathered ? Horizon.WEATHERED : Horizon.ROCK;
		}
	}

	/** Precipitation, mm a year, at which the rain weathers rock as fast as it can. */
	private static final double WET = 1500;
	/** Temperatures, °C, over which weathering goes from nothing to its fastest. */
	private static final double FROZEN = -8;
	private static final double HOT = 26;
	/** The slope, blocks per block, at which the profile is thinnest: where scree starts (see {@link Erosion}). */
	private static final double STEEP = 0.45;
	/** How much of the profile the steepest soil-covered slopes keep. */
	private static final double STEEP_KEEPS = 0.25;
	/** The flattest slope a loess blanket lies on. */
	private static final double LOESS_SLOPE = 0.25;
	/** Wavelength of the variation in depth, in blocks. */
	private static final double WAVELENGTH = 28;
	/** Wavelength of the patches of bauxite, in blocks, and the noise over which they lie. */
	private static final double BAUXITE_WAVELENGTH = 80;
	private static final double BAUXITE_PATCH = 0.1;
	/** The least weathering and flatness under which the laterite turns to bauxite. */
	private static final double BAUXITE_WEATHERING = 0.75;
	private static final double BAUXITE_FLAT = 0.8;

	private final long seed;

	public Soils(long seed) {
		this.seed = GeoNoise.mix(seed ^ 0x50_49_4C_53L);
	}

	/**
	 * Works out the soil of a column.
	 *
	 * @param parent        the rock a few blocks under the top, which the soil weathered from
	 * @param temperature   annual mean, °C
	 * @param precipitation annual, mm
	 * @param aridity       precipitation over the Köppen aridity threshold ({@link ClimateMapping#aridityRatio})
	 * @param slope         blocks per block
	 * @param waterlogged   whether peat or mud lies on top, which keeps the ground under it wet
	 */
	public void profile(Rock parent, float temperature, float precipitation, float aridity, double slope,
						boolean waterlogged, int x, int z, Profile p) {
		// How fast the rock rots, from 0 (frozen or dry) to 1 (hot and wet).
		double weathering = clamp01(precipitation / WET) * clamp01((temperature - FROZEN) / (HOT - FROZEN));
		double flat = 1 - (1 - STEEP_KEEPS) * clamp01(slope / STEEP);
		double variation = fractal(seed, x / WAVELENGTH, z / WAVELENGTH, 2);

		Soil soil = soil(parent, temperature, aridity, slope, waterlogged);
		double subsoil = (1 + 4 * weathering) * flat;
		if (soil == Soil.LATERITE) {
			subsoil = (3 + 8 * weathering) * flat;
		} else if (soil == Soil.LOESS) {
			subsoil += 4;
		} else if (soil == Soil.LOAM && isCarbonate(parent)) {
			// Limestone dissolves rather than crumbles, so little is left of it to make soil.
			subsoil = Math.min(subsoil, 1);
		}
		p.soil = soil;
		p.topsoil = variation > 0.15 ? 3 : 2;
		p.subsoil = p.topsoil + (int) Math.round(subsoil * (1 + 0.3 * variation));
		boolean pan = soil != Soil.LATERITE && aridity >= 0.5 && aridity < 1.1 && temperature >= 6;
		p.pan = p.subsoil + (pan ? 1 : 0);
		p.weathered = p.pan + 1 + (int) Math.max(0, Math.round((2.5 * weathering + 0.5 * variation) * flat));
		p.bauxite = soil == Soil.LATERITE && weathering >= BAUXITE_WEATHERING && flat >= BAUXITE_FLAT
			&& fractal(seed + 3, x / BAUXITE_WAVELENGTH, z / BAUXITE_WAVELENGTH, 2) > BAUXITE_PATCH;
	}

	/** Whether a block at {@code depth} of the weathered rock is broken rock, rather than the soil between it. */
	public boolean rubble(Profile p, int x, int y, int z, int depth) {
		// More rock the deeper, in blobs rather than single blocks.
		double down = (depth - p.pan) / (double) (p.weathered - p.pan + 1);
		return value3(seed + 1, x, y, z, 2) < 0.25 + 0.6 * down;
	}

	/**
	 * Whether a block of the subsoil is bauxite, the aluminium ore. Where the wettest tropics have weathered flat land
	 * longest, the rain has washed all but the aluminium out of the laterite, in patches some 80 blocks across.
	 * It lies in the lower subsoil, under a block of laterite and the topsoil.
	 */
	public boolean bauxite(Profile p, int x, int y, int z, int depth) {
		return p.bauxite && depth > p.topsoil + 1 && depth <= p.subsoil
			&& value3(seed + 3, x, y, z, 2) < Ore.ALUMINUM.grade;
	}

	/** Whether a block of clay subsoil is clay rather than a mottle of soil in it. */
	public boolean clay(int x, int y, int z) {
		return value3(seed + 2, x, y, z, 2) < 0.7;
	}

	static Soil soil(Rock parent, float temperature, double aridity, double slope, boolean waterlogged) {
		if (waterlogged) {
			return Soil.CLAY;
		}
		if (temperature >= 18 && aridity >= 1.2) {
			return Soil.LATERITE;
		}
		if (temperature >= -3 && temperature < 14 && aridity >= 0.5 && aridity < 1.4 && slope < LOESS_SLOPE) {
			return Soil.LOESS;
		}
		return switch (parent) {
			case LIMESTONE, CHALK, MARBLE -> temperature >= 12 && aridity < 3 ? Soil.TERRA_ROSSA : Soil.LOAM;
			case SANDSTONE -> Soil.SAND;
			case RED_SANDSTONE -> Soil.RED_SAND;
			case SHALE, SLATE, COAL_SEAM -> aridity >= 0.9 ? Soil.CLAY : Soil.LOAM;
			case GRANITE, GNEISS, DIORITE -> Soil.GRUS;
			default -> Soil.LOAM;
		};
	}

	private static boolean isCarbonate(Rock rock) {
		return rock == Rock.LIMESTONE || rock == Rock.CHALK || rock == Rock.MARBLE;
	}
}
