package io.github.elpideus.diffusicraft.terrain.geography;

import static io.github.elpideus.diffusicraft.terrain.geography.Continents.smoothstep;

/**
 * Plates and what happens where they meet, as heights in meters added to the continents.
 *
 * <p>Plates are the cells of a warped Voronoi diagram, their sites about {@value #PLATE} km apart. Each moves in a
 * direction of its own and is continental or oceanic by the crust at its site. Along a boundary the plates close in or
 * pull apart at the difference of their velocities across it:
 * <ul>
 *     <li>an oceanic plate meeting a continent sinks beneath it, leaving a trench offshore and a cordillera a couple
 *     of hundred kilometres inland, as the Andes;</li>
 *     <li>two oceanic plates make a trench and, behind it, an arc of volcanic islands, as the Aleutians or the
 *     Marianas;</li>
 *     <li>two continents collide into a wide belt of high mountains and plateaus, as the Himalaya and Tibet;</li>
 *     <li>oceanic plates pulling apart raise a mid-ocean ridge; continental ones parting fast open segments of rift
 *     valley between raised shoulders, deep enough in places to let the sea in, as the Red Sea.</li>
 * </ul>
 * Each pair of plates has an age: old boundaries give lower, wider ranges, worn down like the Appalachians or the
 * Urals. Ranges rise and fall along their length, broken into massifs and passes. Near a point where three plates
 * meet the features fade out, so the field stays continuous where the nearest boundary changes.
 */
final class Tectonics {
	/** Spacing of the plate sites in km. */
	static final double PLATE = 2400;
	private static final double JITTER = 0.42;
	private static final double WARP_WAVELENGTH = 3200;
	private static final double WARP = 650;
	private static final double FINE_WARP_WAVELENGTH = 550;
	private static final double FINE_WARP = 110;
	/** The features fade where the second and third nearest sites are less than this many km apart in distance. */
	private static final double JUNCTION = 220;
	/** Shortest octave of the continents read for the crust of a plate. */
	private static final double CRUST_DETAIL = 1500;
	private static final double CRUST_BAND = 0.08;

	private static final double TRENCH_DEPTH = 2600;
	private static final double TRENCH_OFFSET = 45;
	private static final double TRENCH_WIDTH = 38;
	private static final double ARC_HEIGHT = 4300;
	private static final double ARC_OFFSET = 150;
	private static final double ARC_WIDTH = 40;
	private static final double CORDILLERA_HEIGHT = 3200;
	private static final double CORDILLERA_OFFSET = 200;
	private static final double CORDILLERA_WIDTH = 120;
	private static final double COLLISION_HEIGHT = 3600;
	private static final double COLLISION_WIDTH = 360;
	private static final double RIDGE_HEIGHT = 2300;
	private static final double RIDGE_WIDTH = 420;
	private static final double RIFT_DEPTH = 800;
	private static final double RIFT_WIDTH = 45;
	private static final double SHOULDER_HEIGHT = 600;
	private static final double SHOULDER_OFFSET = 95;
	private static final double SHOULDER_WIDTH = 40;

	private final long seed;
	private final Continents continents;
	private final double crustLevel;
	private final GradientNoise warpX;
	private final GradientNoise warpZ;
	private final GradientNoise fineWarpX;
	private final GradientNoise fineWarpZ;
	private final GradientNoise strike;
	private final GradientNoise width;
	private final GradientNoise islands;
	private final GradientNoise segments;

	/**
	 * @param crustLevel value of the continental field where continental crust begins, about the coast
	 */
	Tectonics(long seed, Continents continents, double crustLevel) {
		this.seed = Hash.mix(seed ^ 0x7EC7_0000L);
		this.continents = continents;
		this.crustLevel = crustLevel;
		warpX = new GradientNoise(Hash.of(seed, 10, 0));
		warpZ = new GradientNoise(Hash.of(seed, 10, 1));
		fineWarpX = new GradientNoise(Hash.of(seed, 10, 2));
		fineWarpZ = new GradientNoise(Hash.of(seed, 10, 3));
		strike = new GradientNoise(Hash.of(seed, 11, 0));
		width = new GradientNoise(Hash.of(seed, 11, 1));
		islands = new GradientNoise(Hash.of(seed, 11, 2));
		segments = new GradientNoise(Hash.of(seed, 11, 3));
	}

	/**
	 * Height in meters that the plates add at a point in km.
	 *
	 * @param land how far the point lies on land, from 0 to 1: trenches and island arcs only form at sea
	 */
	double uplift(double x, double z, double land) {
		double px = x + WARP * warpX.sample(x / WARP_WAVELENGTH, z / WARP_WAVELENGTH)
			+ FINE_WARP * fineWarpX.sample(x / FINE_WARP_WAVELENGTH, z / FINE_WARP_WAVELENGTH);
		double pz = z + WARP * warpZ.sample(x / WARP_WAVELENGTH, z / WARP_WAVELENGTH)
			+ FINE_WARP * fineWarpZ.sample(x / FINE_WARP_WAVELENGTH, z / FINE_WARP_WAVELENGTH);
		long ci = (long) Math.floor(px / PLATE), cj = (long) Math.floor(pz / PLATE);

		// The three nearest sites.
		double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE, d3 = Double.MAX_VALUE;
		long h1 = 0, h2 = 0;
		double x1 = 0, z1 = 0, x2 = 0, z2 = 0;
		for (long i = ci - 2; i <= ci + 2; i++) {
			for (long j = cj - 2; j <= cj + 2; j++) {
				long h = Hash.of(seed, i, j);
				double sx = (i + 0.5 + JITTER * (2 * Hash.unit(h, 1) - 1)) * PLATE;
				double sz = (j + 0.5 + JITTER * (2 * Hash.unit(h, 2) - 1)) * PLATE;
				double d = (px - sx) * (px - sx) + (pz - sz) * (pz - sz);
				if (d < d1) {
					d3 = d2;
					d2 = d1;
					h2 = h1;
					x2 = x1;
					z2 = z1;
					d1 = d;
					h1 = h;
					x1 = sx;
					z1 = sz;
				} else if (d < d2) {
					d3 = d2;
					d2 = d;
					h2 = h;
					x2 = sx;
					z2 = sz;
				} else if (d < d3) {
					d3 = d;
				}
			}
		}
		double junction = smoothstep(0, JUNCTION, Math.sqrt(d3) - Math.sqrt(d2));
		if (junction <= 0) {
			return 0;
		}
		double ex = x2 - x1, ez = z2 - z1;
		double length = Math.sqrt(ex * ex + ez * ez);
		double nx = ex / length, nz = ez / length;
		// Distance to the boundary, in km on this side of it.
		double d = (d2 - d1) / (2 * length);
		double closing = (velocityX(h1) - velocityX(h2)) * nx + (velocityZ(h1) - velocityZ(h2)) * nz;
		double c1 = crust(x1, z1), c2 = crust(x2, z2);
		long pair = Hash.of(seed, Math.min(h1, h2), Math.max(h1, h2));
		double youth = 0.3 + 0.7 * Hash.unit(pair, 1);
		double along = Math.clamp(0.55 + 0.6 * strike.sample(x / 700, z / 700), 0.1, 1.0);
		double widen = (1 + 0.35 * width.sample(x / 1100, z / 1100)) * (1.6 - 0.6 * youth);

		double oceans = (1 - c1) * (1 - c2);
		double lands = c1 * c2;
		if (closing > 0) {
			double amplitude = Math.min(1.2, closing) * youth * along * junction;
			// Of two oceanic plates, the pair decides which one sinks.
			boolean firstSinks = (Hash.unit(pair, 2) < 0.5) == (h1 < h2);
			double arcSide = firstSinks ? -d : d;
			double spots = Math.clamp(0.5 + 1.3 * islands.sample(x / 110, z / 110), 0.0, 1.0);
			double sea = 1 - land;
			double value = oceans * sea * (arc(arcSide, spots) + trench(arcSide))
				+ (1 - c1) * c2 * (cordillera(-d, widen) + sea * trench(-d))
				+ c1 * (1 - c2) * (cordillera(d, widen) + sea * trench(d))
				+ lands * collision(d, widen);
			return amplitude * value;
		}
		double amplitude = Math.min(1.0, -closing) * (0.5 + 0.5 * along) * junction;
		// Continents tear open only where they part fast, in basins a few hundred kilometres long, as in East Africa.
		double basins = smoothstep(0.05, 0.45, segments.sample(x / 320, z / 320));
		double tear = smoothstep(0.35, 1.0, -closing) * along * basins * junction;
		return amplitude * oceans * ridge(d) + tear * lands * rift(d);
	}

	/** How continental a plate is, from 0 to 1, by the crust at its site. */
	private double crust(double x, double z) {
		return smoothstep(crustLevel - CRUST_BAND, crustLevel + CRUST_BAND, continents.field(x, z, CRUST_DETAIL));
	}

	private static double velocityX(long plate) {
		return speed(plate) * StrictMath.cos(2 * Math.PI * Hash.unit(plate, 3));
	}

	private static double velocityZ(long plate) {
		return speed(plate) * StrictMath.sin(2 * Math.PI * Hash.unit(plate, 3));
	}

	private static double speed(long plate) {
		return 0.3 + 0.7 * Hash.unit(plate, 4);
	}

	/** {@code s} is the signed distance from the boundary: negative on the sinking plate, positive on the other. */
	private static double trench(double s) {
		return -TRENCH_DEPTH * gauss((s + TRENCH_OFFSET) / TRENCH_WIDTH);
	}

	private static double arc(double s, double spots) {
		return ARC_HEIGHT * (0.2 + 0.8 * spots) * gauss((s - ARC_OFFSET) / ARC_WIDTH);
	}

	private static double cordillera(double s, double widen) {
		return CORDILLERA_HEIGHT * gauss((s - CORDILLERA_OFFSET * widen) / (CORDILLERA_WIDTH * widen));
	}

	private static double collision(double d, double widen) {
		return COLLISION_HEIGHT * gauss(d / (COLLISION_WIDTH * widen));
	}

	private static double ridge(double d) {
		return RIDGE_HEIGHT * gauss(d / RIDGE_WIDTH);
	}

	private static double rift(double d) {
		return -RIFT_DEPTH * gauss(d / RIFT_WIDTH) + SHOULDER_HEIGHT * gauss((d - SHOULDER_OFFSET) / SHOULDER_WIDTH);
	}

	static double gauss(double t) {
		return t * t > 40 ? 0 : StrictMath.exp(-t * t);
	}
}
