package io.github.elpideus.diffusicraft.terrain.geography;

/**
 * Where the continents lie: a field that is high on continental crust and low on the ocean floor, before the
 * hypsometry turns it into meters.
 *
 * <p>It sums octaves of gradient noise from 12,000 km, the size of supercontinents, down to 24 km, below which the
 * coarse model adds its own detail. The octaves follow the spectrum of real topography, so one world holds landmasses
 * of every size: continents of thousands of kilometres, subcontinents, large islands and small ones. Two layers of
 * domain warping bend and stretch the shapes into arms, peninsulas and gulfs. The short octaves are scaled by a slowly
 * varying ruggedness: where it is low, coasts run long and smooth, as around Africa; where it is high, they break into
 * archipelagos and shelf seas, as in the Canadian Arctic or the Aegean.
 *
 * <p>How high the land stands is a field of its own, the {@link #uplands uplands}.
 */
final class Continents {
	/** Wavelengths of the octaves in km. */
	private static final double[] WAVELENGTHS = {12000, 6000, 3000, 1500, 750, 375, 190, 95, 48, 24};
	private static final double[] AMPLITUDES = {1.0, 0.62, 0.44, 0.31, 0.22, 0.155, 0.11, 0.078, 0.055, 0.039};
	/** The octaves from this one on scale with the ruggedness. */
	private static final int RUGGED_FROM = 4;
	private static final double WARP_WAVELENGTH = 4500;
	private static final double WARP = 1000;
	private static final double FINE_WARP_WAVELENGTH = 700;
	private static final double FINE_WARP = 150;
	private static final double RUGGEDNESS_WAVELENGTH = 5000;
	/** Wavelengths of the octaves of the uplands in km. */
	private static final double[] UPLAND_WAVELENGTHS = {4000, 2000, 1000, 500, 250};
	private static final double[] UPLAND_AMPLITUDES = {1.0, 0.6, 0.36, 0.22, 0.13};

	private final GradientNoise[] octaves = new GradientNoise[WAVELENGTHS.length];
	private final GradientNoise warpX;
	private final GradientNoise warpZ;
	private final GradientNoise fineWarpX;
	private final GradientNoise fineWarpZ;
	private final GradientNoise ruggedness;
	private final GradientNoise[] uplands = new GradientNoise[UPLAND_WAVELENGTHS.length];

	Continents(long seed) {
		for (int o = 0; o < octaves.length; o++) {
			octaves[o] = new GradientNoise(Hash.of(seed, 1, o));
		}
		warpX = new GradientNoise(Hash.of(seed, 2, 0));
		warpZ = new GradientNoise(Hash.of(seed, 2, 1));
		fineWarpX = new GradientNoise(Hash.of(seed, 2, 2));
		fineWarpZ = new GradientNoise(Hash.of(seed, 2, 3));
		ruggedness = new GradientNoise(Hash.of(seed, 3, 0));
		for (int o = 0; o < uplands.length; o++) {
			uplands[o] = new GradientNoise(Hash.of(seed, 4, o));
		}
	}

	/**
	 * The field at a point in km, summing the octaves down to {@code shortest} km. Leaving out the short octaves gives
	 * the shape of the land without its islands, for the crust of a plate or a look upwind.
	 */
	double field(double x, double z, double shortest) {
		double wx = x + WARP * warpX.sample(x / WARP_WAVELENGTH, z / WARP_WAVELENGTH)
			+ FINE_WARP * fineWarpX.sample(x / FINE_WARP_WAVELENGTH, z / FINE_WARP_WAVELENGTH);
		double wz = z + WARP * warpZ.sample(x / WARP_WAVELENGTH, z / WARP_WAVELENGTH)
			+ FINE_WARP * fineWarpZ.sample(x / FINE_WARP_WAVELENGTH, z / FINE_WARP_WAVELENGTH);
		double rugged = 1.0;
		double sum = 0;
		for (int o = 0; o < octaves.length && WAVELENGTHS[o] >= shortest; o++) {
			if (o == RUGGED_FROM) {
				rugged = ruggedness(x, z);
			}
			double amplitude = o >= RUGGED_FROM ? AMPLITUDES[o] * rugged : AMPLITUDES[o];
			sum += amplitude * octaves[o].sample(wx / WAVELENGTHS[o], wz / WAVELENGTHS[o]);
		}
		return sum;
	}

	/**
	 * The uplands field: high where the land away from the coasts rises into plateaus and shields, low over the
	 * great lowland basins and plains. It does not follow the continents, so the heart of a continent can be a plain,
	 * as in the Amazon or Siberia, as well as a plateau.
	 */
	double uplands(double x, double z) {
		double sum = 0;
		for (int o = 0; o < uplands.length; o++) {
			sum += UPLAND_AMPLITUDES[o] * uplands[o].sample(x / UPLAND_WAVELENGTHS[o], z / UPLAND_WAVELENGTHS[o]);
		}
		return sum;
	}

	/** From 0.45 (smooth coasts) to 1.65 (shattered ones). */
	private double ruggedness(double x, double z) {
		double n = ruggedness.sample(x / RUGGEDNESS_WAVELENGTH, z / RUGGEDNESS_WAVELENGTH);
		return 0.45 + 1.2 * smoothstep(-0.35, 0.45, n);
	}

	static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
		return t * t * (3 - 2 * t);
	}
}
