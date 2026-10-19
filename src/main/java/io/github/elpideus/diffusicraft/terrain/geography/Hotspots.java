package io.github.elpideus.diffusicraft.terrain.geography;

import static io.github.elpideus.diffusicraft.terrain.geography.Tectonics.gauss;

/**
 * Hotspots: plumes from the mantle that the plates drift over, leaving a chain of volcanoes. The youngest stand
 * highest, over a broad swell; older ones have cooled and sunk, from islands to seamounts, as along the Hawaiian
 * chain. On continental crust only the swell is left, a broad upland as around Yellowstone or the Ethiopian highlands.
 *
 * <p>About a third of the cells of a {@value #CELL} km grid hold one, with a chain up to {@value #LONGEST} km long.
 */
final class Hotspots {
	static final double CELL = 1600;
	static final double LONGEST = 2400;
	private static final double CHANCE = 0.35;
	private static final double SWELL = 900;
	private static final double SWELL_RADIUS = 450;

	private final long seed;

	Hotspots(long seed) {
		this.seed = Hash.mix(seed ^ 0x4075_7075L);
	}

	/**
	 * Height in meters that hotspots add at a point in km.
	 *
	 * @param land how continental the crust is here, from 0 to 1
	 */
	double uplift(double x, double z, double land) {
		long ci = (long) Math.floor(x / CELL), cj = (long) Math.floor(z / CELL);
		double total = 0;
		for (long i = ci - 2; i <= ci + 2; i++) {
			for (long j = cj - 2; j <= cj + 2; j++) {
				long h = Hash.of(seed, i, j);
				if (Hash.unit(h, 1) >= CHANCE) {
					continue;
				}
				total += chain(h, (i + Hash.unit(h, 2)) * CELL, (j + Hash.unit(h, 3)) * CELL, x, z, land);
			}
		}
		return total;
	}

	private static double chain(long h, double hx, double hz, double x, double z, double land) {
		double angle = 2 * Math.PI * Hash.unit(h, 4);
		double dx = StrictMath.cos(angle), dz = StrictMath.sin(angle);
		double length = 300 + (LONGEST - 300) * StrictMath.pow(Hash.unit(h, 5), 1.5);
		double spacing = 70 + 80 * Hash.unit(h, 6);
		double height = 4200 + 2300 * Hash.unit(h, 7);
		double rx = x - hx, rz = z - hz;
		double t = rx * dx + rz * dz;
		double l = rz * dx - rx * dz;
		double swell = SWELL * gauss(Math.sqrt(rx * rx + rz * rz) / SWELL_RADIUS);
		if (land >= 1 || t < -300 || t > length + 300 || Math.abs(l) > 300) {
			return swell;
		}
		double best = 0;
		long k0 = (long) Math.floor(t / spacing);
		for (long k = k0 - 1; k <= k0 + 2; k++) {
			if (k < 0 || k * spacing > length) {
				continue;
			}
			long v = Hash.of(h, k, 0);
			double ct = (k + 0.3 * (2 * Hash.unit(v, 1) - 1)) * spacing;
			double cl = 25 * (2 * Hash.unit(v, 2) - 1);
			double age = Math.clamp(ct / length, 0.0, 1.0);
			double rise = height * StrictMath.pow(1 - 0.85 * age, 1.3) * (0.75 + 0.25 * Hash.unit(v, 3));
			double radius = 30 + 25 * Hash.unit(v, 4) + 25 * (1 - age);
			double dt = t - ct, dl = l - cl;
			best = Math.max(best, rise * gauss(Math.sqrt(dt * dt + dl * dl) / radius));
		}
		return Math.max((1 - land) * best, swell);
	}
}
