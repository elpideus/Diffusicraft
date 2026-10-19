package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Where the ground is too steep to hold soil, and where what falls from it comes to rest.
 *
 * <p>Soil creeps off slopes steeper than about 50°, so the rock shows there: cliffs. What weathers out of a cliff falls
 * and piles up below it as scree, at about the angle of repose, 30 to 35°, and spreads out a little further at its
 * foot. Both follow from the shape of the surface alone, which the terrain gives at any point. Slopes are measured in
 * blocks, as the player sees them, not in meters: the smaller scales raise the land more than they widen it.
 *
 * <p>The thresholds wander with a little noise, so the edges of cliffs and scree follow the land rather than the
 * terrain's grid of pixels. Plain Java with no state, like {@link Geology}.
 */
public final class Erosion {
	/** The height of the surface, in blocks, at a point given in blocks: block {@code x} spans {@code [x, x + 1)}. */
	@FunctionalInterface
	public interface Surface {
		double y(double x, double z);
	}

	/** What covers the ground of a column. */
	public enum Kind {
		/** Whatever the biome lays down. */
		SOIL,
		/** Bare rock. */
		CLIFF,
		/** Gravel and fallen rock. */
		SCREE
	}

	/** Slope, blocks per block, above which no soil holds: about 50°. */
	static final double CLIFF_SLOPE = 1.2;
	/** Slope below which scree no longer reaches: about 24°. */
	static final double SCREE_SLOPE = 0.45;
	/** How far uphill a cliff sheds its rock, in blocks. */
	static final double REACH = 12;
	/** Mean slope over {@link #REACH} uphill that makes a cliff above the column: about 45°. */
	static final double FEED_SLOPE = 1.0;
	/** Blocks of scree over the rock. */
	public static final int SCREE_DEPTH = 3;
	/** How far the thresholds wander, as a fraction of their value. */
	private static final double WANDER = 0.2;
	/** Wavelength of the wander, in blocks. */
	private static final double WAVELENGTH = 16;
	/** Share of gravel in scree; the rest is broken rock. */
	private static final double GRAVEL = 0.55;

	private final long seed;

	public Erosion(long seed) {
		this.seed = GeoNoise.mix(seed ^ 0x45524F53494F4EL);
	}

	/** What covers the ground of column {@code (x, z)} on {@code surface}. */
	public Kind kind(Surface surface, int x, int z) {
		return kind(surface, x, z, slope(surface, x, z));
	}

	/** The slope of column {@code (x, z)} on {@code surface}, in blocks per block. */
	public static double slope(Surface surface, int x, int z) {
		double cx = x + 0.5, cz = z + 0.5;
		double gx = (surface.y(cx + 1, cz) - surface.y(cx - 1, cz)) / 2;
		double gz = (surface.y(cx, cz + 1) - surface.y(cx, cz - 1)) / 2;
		return Math.sqrt(gx * gx + gz * gz);
	}

	/** {@link #kind(Surface, int, int)} for a column whose {@link #slope} is known. */
	public Kind kind(Surface surface, int x, int z, double slope) {
		double cx = x + 0.5, cz = z + 0.5;
		double wander = 1 + WANDER * GeoNoise.fractal(seed, cx / WAVELENGTH, cz / WAVELENGTH, 2);
		if (slope >= CLIFF_SLOPE * wander) {
			return Kind.CLIFF;
		}
		if (slope < SCREE_SLOPE * wander) {
			return Kind.SOIL;
		}
		// Scree lies under a cliff: the ground rises steeply within reach uphill.
		double gx = (surface.y(cx + 1, cz) - surface.y(cx - 1, cz)) / 2;
		double gz = (surface.y(cx, cz + 1) - surface.y(cx, cz - 1)) / 2;
		double rise = surface.y(cx + gx / slope * REACH, cz + gz / slope * REACH) - surface.y(cx, cz);
		return rise >= FEED_SLOPE * REACH * wander ? Kind.SCREE : Kind.SOIL;
	}

	/** Whether the scree block at {@code (x, y, z)} is gravel rather than broken rock. */
	public boolean gravel(int x, int y, int z) {
		return GeoNoise.unit(GeoNoise.hash(seed, x, y, z)) < GRAVEL;
	}
}
