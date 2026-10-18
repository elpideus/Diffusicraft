package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Gullies: relief finer than the terrain model's 30 m pixels, cut down the slopes.
 *
 * <p>Terrain Diffusion resolves nothing shorter than two of its pixels, and the spline between pixels is smooth, so at
 * Earth 1:1 a mountainside is a smooth sheet between samples 30 blocks apart. Real slopes are cut by gullies and rills
 * that run straight down them, deeper the steeper the slope, while gentle ground stays smooth under its soil. Gullies
 * adds them as waves that cross the slope, anchored at jittered points and blended with a compact kernel (Gabor
 * noise), so that their crests and troughs run downhill. Each octave halves the wavelength and the depth, and follows
 * the slope of the ground carved so far, so smaller gullies branch off the walls of larger ones.
 *
 * <p>Wavelengths are fixed in meters. Octaves shorter than 12 blocks are left out: the terrain is interpolated over
 * cells 4 blocks wide and would smooth them into zigzags. That keeps all three at Earth 1:1 and none at the other
 * scales, whose pixels are only 2 and 6 blocks wide. Plain Java with no state, like {@link Erosion}.
 */
public final class Gullies {
	/** Wavelength of the first octave, in meters: a little over a pixel and a half. */
	private static final double WAVELENGTH_METERS = 48;
	/** Octaves at most. */
	private static final int OCTAVES = 3;
	/** The shortest wavelength kept, in blocks. */
	static final double MIN_WAVELENGTH = 12;
	/** Depth of the first octave, as a share of its wavelength times the slope. */
	private static final double DEPTH = 0.06;
	/** Slope, blocks per block, below which the ground stays smooth: about 9°. */
	static final double SMOOTH_SLOPE = 0.15;
	/** Slope at which gullies are fully cut: about 31°. */
	private static final double FULL_SLOPE = 0.6;
	/** Steeper slopes cut no deeper. */
	private static final double MAX_SLOPE = 1.5;
	/** How far the kernel reaches, in cells. */
	private static final double REACH = 1.25;
	/** How far an anchor may stray from the centre of its cell, in cells: a quarter either way. */
	private static final double JITTER = 0.5;
	private static final int TABLE = 1024;
	private static final double[] COS = new double[TABLE + 1];

	static {
		for (int i = 0; i <= TABLE; i++) {
			COS[i] = Math.cos(i * 2 * Math.PI / TABLE);
		}
	}

	private final long seed;
	private final double wavelength;
	private final int octaves;

	/** Gullies for a world whose blocks are {@code metersPerBlock} wide. */
	public Gullies(long seed, double metersPerBlock) {
		this.seed = GeoNoise.mix(seed ^ 0x47554C4C494553L);
		this.wavelength = WAVELENGTH_METERS / metersPerBlock;
		int octaves = 0;
		while (octaves < OCTAVES && wavelength / (1 << octaves) >= MIN_WAVELENGTH) {
			octaves++;
		}
		this.octaves = octaves;
	}

	/** Whether there are gullies at this scale at all. */
	public boolean any() {
		return octaves > 0;
	}

	/** The deepest a gully can go below or rise above the smooth ground, in blocks. */
	public double maxDepth() {
		return DEPTH * MAX_SLOPE * wavelength * (2 - 2.0 / (1 << octaves));
	}

	/**
	 * Blocks to add to the smooth ground at {@code (x, z)}, given in blocks, where the smooth ground's slope is
	 * {@code (gx, gz)} in blocks per block.
	 */
	public double depth(double x, double z, double gx, double gz) {
		if (octaves == 0) {
			return 0;
		}
		double slope = Math.sqrt(gx * gx + gz * gz);
		double cut = GeoNoise.smoothstep(SMOOTH_SLOPE, FULL_SLOPE, slope);
		if (cut == 0) {
			return 0;
		}
		double amplitude = DEPTH * cut * Math.min(slope, MAX_SLOPE) * wavelength, length = wavelength;
		double h = 0, dx = 0, dz = 0;
		for (int octave = 0; octave < octaves; octave++) {
			// Across the slope of the ground so far: the smooth ground and the gullies already cut.
			double sx = gx + dx, sz = gz + dz;
			double norm = Math.sqrt(sx * sx + sz * sz);
			if (norm < 1e-9) {
				break;
			}
			double f = 1 / length, acrossX = -sz / norm, acrossZ = sx / norm;
			long octaveSeed = seed + octave;
			double px = x * f, pz = z * f;
			int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
			double fx = px - ix, fz = pz - iz;
			double value = 0, valueX = 0, valueZ = 0, weights = 0;
			for (int cz = -1; cz <= 1; cz++) {
				for (int cx = -1; cx <= 1; cx++) {
					long hash = GeoNoise.hash(octaveSeed, ix + cx, 0, iz + cz);
					double ox = fx - cx - 0.5 - JITTER * (GeoNoise.unit(hash) - 0.5);
					double oz = fz - cz - 0.5 - JITTER * (GeoNoise.unit2(hash) - 0.5);
					double d = (ox * ox + oz * oz) / (REACH * REACH);
					if (d >= 1) {
						continue;
					}
					double k = 1 - d, w = k * k * k;
					double turns = ox * acrossX + oz * acrossZ + (hash & 0xffff) / 65536.0;
					double cos = cos(turns), sin = cos(turns - 0.25);
					value += cos * w;
					valueX -= sin * acrossX * w;
					valueZ -= sin * acrossZ * w;
					weights += w;
				}
			}
			h += amplitude * value / weights;
			// The gradient of this octave, in blocks per block, ignoring how the weights change.
			dx += amplitude * 2 * Math.PI * f * valueX / weights;
			dz += amplitude * 2 * Math.PI * f * valueZ / weights;
			length *= 0.5;
			amplitude *= 0.5;
		}
		return h;
	}

	/** The cosine of {@code turns} whole turns, from a table. */
	private static double cos(double turns) {
		double t = (turns - Math.floor(turns)) * TABLE;
		int i = (int) t;
		return COS[i] + (COS[i + 1] - COS[i]) * (t - i);
	}
}
