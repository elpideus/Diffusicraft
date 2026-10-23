package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;

/**
 * How the land comes down to the sea. The model's coasts are steps: it learnt them from maps whose sea floor was
 * sampled far more coarsely than their land, so where hills or mountains meet the sea the ground climbs a hundred
 * meters within a pixel or two of the coastline, and with the heights exaggerated it stood in the world as a wall.
 * Real coasts mostly ease down to the water, over a beach, a strip of lowland or the foot of a slope. So the land
 * within {@link TerrainField#COAST_REACH} of the coastline is held under an envelope that climbs from the waterline
 * at the coast's {@link #slope}, between {@value #SLOPE_MIN} and {@value #SLOPE_MAX} m per meter, and bends up by
 * {@value #RISE} m more over the reach, so that by the reach any ground gets through: the first slope above the
 * water is gentle, the rise the coast took is pushed inland, and a mountain still stands behind.
 *
 * <p>Ground under the envelope keeps its height: the envelope is a soft minimum, which bites only on ground near it
 * ({@link #ease}). The easing fades out over the last {@code 1 - }{@value #FADE_FROM} of the reach, beyond which
 * the distance to the coastline is not measured. {@link TerrainField} eases the pixels of its tiles as it reads
 * them, so the rivers and lakes, the biomes, the levels of detail and the ground in blocks all see the eased land,
 * and a lake standing above the sea in a valley the ice drowned keeps its shores: the land under it was eased too,
 * so its basin is a basin of the eased land. Where the ice lowered the land into fjords, whose sides drop
 * steeply into the water, the envelope climbs at {@value #SLOPE_ICE} m per meter instead: the sides stay steep,
 * but no longer sheer for hundreds of blocks. The envelope never falls under {@value #LEAST} m, so a steep shore
 * keeps a bank at the waterline rather than a flat flush with the water. Everything is in real meters, so the coasts
 * have the same shape at every size of world and are exaggerated as the rest of the land is.
 */
final class CoastSlope {
	/** How fast the envelope climbs from the coastline, in meters per meter: from 17° on the gentlest coasts to 31°. */
	static final double SLOPE_MIN = 0.3;
	static final double SLOPE_MAX = 0.6;
	/** Real wavelength of the noise that sets each stretch of coast its slope, in meters. */
	static final double SLOPE_WAVELENGTH = 2500;
	/** How much more the envelope climbs by the reach, in meters, bending up so that any ground gets through it. */
	static final double RISE = 360;
	/** The least the envelope allows, in meters. */
	static final double LEAST = 2;
	/** The distance inland, in meters, from which the easing fades out, and the reach where it is gone. */
	static final double REACH = TerrainField.COAST_REACH;
	static final double FADE_FROM = 0.7 * REACH;
	/** How fast the envelope climbs in the fjords, in meters per meter: 35°. */
	static final double SLOPE_ICE = 0.7;
	/** The ice cut, in meters, from which a coast's slope steepens towards the fjords', and at which it is theirs. */
	static final double ICE_FROM = 40;
	static final double ICE_TO = 120;

	private CoastSlope() {
	}

	/** The slope of the envelope on the stretch of coast at a point, given in real meters, in meters per meter. */
	static double slope(long seed, double xMeters, double zMeters) {
		double n = GeoNoise.fractal(seed, xMeters / SLOPE_WAVELENGTH, zMeters / SLOPE_WAVELENGTH, 2);
		return SLOPE_MIN + (SLOPE_MAX - SLOPE_MIN) * GeoNoise.smoothstep(-0.5, 0.5, n);
	}

	/** The envelope {@code d} meters inland of the coastline, on a coast of the given slope, in meters. */
	static double envelope(double slope, double d) {
		double r = d / REACH;
		return Math.max(LEAST, slope * d + RISE * r * r);
	}

	/** How much of the easing applies {@code d} meters inland: 1 up to {@link #FADE_FROM}, 0 from the reach. */
	static double weight(double d) {
		return d >= REACH ? 0 : 1 - GeoNoise.smoothstep(FADE_FROM, REACH, d);
	}

	/** The slope of the envelope on a coast whose own slope is {@code slope}, where the ice cut {@code iceCut} meters. */
	static double slope(double slope, float iceCut) {
		return slope + (SLOPE_ICE - slope) * GeoNoise.smoothstep(ICE_FROM, ICE_TO, iceCut);
	}

	/**
	 * The elevation of land {@code elevation} meters high, {@code d} meters inland of the coastline, eased under the
	 * envelope of a coast of the given {@code slope} where the ice cut {@code iceCut} meters. Sea and ground beyond the
	 * reach are left alone.
	 */
	static float ease(float elevation, double d, float iceCut, double slope) {
		if (elevation <= 0f || d <= 0) {
			return elevation;
		}
		double weight = weight(d);
		if (weight <= 0) {
			return elevation;
		}
		double envelope = envelope(slope(slope, iceCut), d);
		// A soft minimum of the ground and the envelope: t / (1 + t^4)^(1/4) of the envelope, which is the ground
		// itself well under it and the envelope well over it.
		double t = elevation / envelope, t4 = t * t * t * t;
		double eased = envelope * t * Math.pow(1 + t4, -0.25);
		return (float) (elevation - weight * (elevation - eased));
	}
}
