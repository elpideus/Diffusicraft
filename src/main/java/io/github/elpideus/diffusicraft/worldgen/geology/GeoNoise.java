package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Seeded hashes and smooth noise for the geology. Plain Java with no state, so the model runs the same in the game and
 * in unit tests, on any thread.
 */
final class GeoNoise {
	private static final double[] GRADIENTS_X = new double[16];
	private static final double[] GRADIENTS_Z = new double[16];

	static {
		for (int i = 0; i < 16; i++) {
			double angle = i * Math.PI / 8;
			GRADIENTS_X[i] = Math.cos(angle);
			GRADIENTS_Z[i] = Math.sin(angle);
		}
	}

	private GeoNoise() {
	}

	/** SplitMix64's finaliser: a bijective mix of all 64 bits. */
	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static long hash(long seed, int x, int y, int z) {
		return mix(seed ^ mix(x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L));
	}

	/** Uniform in [0, 1) from the top 53 bits of a hash. */
	static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}

	/** A second independent value from the same hash. */
	static double unit2(long hash) {
		return unit(mix(hash + 0x9E3779B97F4A7C15L));
	}

	/** 2D gradient noise in about [-1, 1], one feature per unit. */
	static double gradient(long seed, double x, double z) {
		int ix = (int) Math.floor(x);
		int iz = (int) Math.floor(z);
		double fx = x - ix;
		double fz = z - iz;
		double a = dot(seed, ix, iz, fx, fz);
		double b = dot(seed, ix + 1, iz, fx - 1, fz);
		double c = dot(seed, ix, iz + 1, fx, fz - 1);
		double d = dot(seed, ix + 1, iz + 1, fx - 1, fz - 1);
		double u = fade(fx);
		double v = fade(fz);
		// The raw range of 2D gradient noise is about ±0.71.
		return 1.41 * lerp(v, lerp(u, a, b), lerp(u, c, d));
	}

	/** Gradient noise summed over octaves of halving wavelength, in about [-1, 1]. */
	static double fractal(long seed, double x, double z, int octaves) {
		double sum = 0;
		double amplitude = 1;
		double norm = 0;
		for (int o = 0; o < octaves; o++) {
			sum += amplitude * gradient(seed + o * 0x632BE59BD9B4E019L, x, z);
			norm += amplitude;
			amplitude *= 0.5;
			x *= 2.03;
			z *= 2.03;
		}
		return sum / norm;
	}

	/** 3D value noise in [0, 1) on a lattice of the given spacing, smoothly interpolated. */
	static double value3(long seed, int x, int y, int z, int spacing) {
		int ix = Math.floorDiv(x, spacing);
		int iy = Math.floorDiv(y, spacing);
		int iz = Math.floorDiv(z, spacing);
		double fx = fade((x - ix * spacing + 0.5) / spacing);
		double fy = fade((y - iy * spacing + 0.5) / spacing);
		double fz = fade((z - iz * spacing + 0.5) / spacing);
		double x00 = lerp(fx, unit(hash(seed, ix, iy, iz)), unit(hash(seed, ix + 1, iy, iz)));
		double x10 = lerp(fx, unit(hash(seed, ix, iy + 1, iz)), unit(hash(seed, ix + 1, iy + 1, iz)));
		double x01 = lerp(fx, unit(hash(seed, ix, iy, iz + 1)), unit(hash(seed, ix + 1, iy, iz + 1)));
		double x11 = lerp(fx, unit(hash(seed, ix, iy + 1, iz + 1)), unit(hash(seed, ix + 1, iy + 1, iz + 1)));
		return lerp(fz, lerp(fy, x00, x10), lerp(fy, x01, x11));
	}

	static double smoothstep(double edge0, double edge1, double x) {
		double t = clamp01((x - edge0) / (edge1 - edge0));
		return t * t * (3 - 2 * t);
	}

	static double clamp01(double x) {
		return x < 0 ? 0 : x > 1 ? 1 : x;
	}

	private static double dot(long seed, int ix, int iz, double fx, double fz) {
		int g = (int) (hash(seed, ix, 0, iz) >>> 60);
		return GRADIENTS_X[g] * fx + GRADIENTS_Z[g] * fz;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}
}
