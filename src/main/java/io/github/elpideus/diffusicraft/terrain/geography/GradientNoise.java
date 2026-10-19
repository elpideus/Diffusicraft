package io.github.elpideus.diffusicraft.terrain.geography;

/**
 * Seeded two-dimensional gradient (Perlin) noise in double precision, between about -1 and 1, with a standard
 * deviation of about 0.3. Each instance turns and shifts its lattice by its seed, so octaves built from different
 * instances never line up their lattices or share their zeros.
 */
final class GradientNoise {
	private static final int DIRECTIONS = 32;
	private static final double[] GX = new double[DIRECTIONS];
	private static final double[] GZ = new double[DIRECTIONS];
	private static final double SCALE = Math.sqrt(2.0);

	static {
		for (int k = 0; k < DIRECTIONS; k++) {
			double angle = 2 * Math.PI * k / DIRECTIONS;
			GX[k] = StrictMath.cos(angle);
			GZ[k] = StrictMath.sin(angle);
		}
	}

	private final long seed;
	private final double cos;
	private final double sin;
	private final double shiftX;
	private final double shiftZ;

	GradientNoise(long seed) {
		this.seed = Hash.mix(seed);
		double angle = 2 * Math.PI * Hash.unit(this.seed, 1);
		this.cos = StrictMath.cos(angle);
		this.sin = StrictMath.sin(angle);
		this.shiftX = 4096 * Hash.unit(this.seed, 2);
		this.shiftZ = 4096 * Hash.unit(this.seed, 3);
	}

	/** The noise at a point, in units of its lattice. */
	double sample(double x, double z) {
		double rx = x * cos - z * sin + shiftX;
		double rz = x * sin + z * cos + shiftZ;
		double x0 = Math.floor(rx), z0 = Math.floor(rz);
		long xi = (long) x0, zi = (long) z0;
		double fx = rx - x0, fz = rz - z0;
		double u = fade(fx), v = fade(fz);
		double a = gradient(xi, zi, fx, fz);
		double b = gradient(xi + 1, zi, fx - 1, fz);
		double c = gradient(xi, zi + 1, fx, fz - 1);
		double d = gradient(xi + 1, zi + 1, fx - 1, fz - 1);
		double ab = a + u * (b - a);
		double cd = c + u * (d - c);
		return (ab + v * (cd - ab)) * SCALE;
	}

	private double gradient(long xi, long zi, double dx, double dz) {
		int k = (int) (Hash.of(seed, xi, zi) >>> 59);
		return GX[k] * dx + GZ[k] * dz;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}
}
