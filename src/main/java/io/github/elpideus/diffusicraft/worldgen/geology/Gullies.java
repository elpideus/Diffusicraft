package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Gullies: relief finer than the terrain model's 30 m pixels, cut down the slopes.
 *
 * <p>Terrain Diffusion resolves nothing shorter than two of its pixels, and the spline between pixels is smooth, so at
 * 1:1 a mountainside is a smooth sheet between samples 30 blocks apart. Real slopes are cut by gullies and rills
 * that run straight down them, deeper the steeper the slope, while gentle ground stays smooth under its soil. Gullies
 * adds them as waves that cross the slope, anchored at jittered points and blended with a compact kernel (Gabor
 * noise), so that their crests and troughs run downhill. Each octave halves the wavelength and the depth, and follows
 * the slope of the ground carved so far, so smaller gullies branch off the walls of larger ones.
 *
 * <p>The rock and the climate decide their character. Hard rock is cut deeper and keeps sharp ridges and V-shaped
 * gullies, crags. Soil and plants, where it is wet and mild, creep down the slopes and round the ground off, and hide
 * the finer gullies: soft rock under a thick cover becomes rounded hills. Bare soft rock, where it is dry or frozen,
 * is cut into dense, sharp rills, as in badlands. The waves of each anchor pass from a cosine (rounded) to a triangle
 * (sharp) with the sharpness; the gradient that orients the next octave stays the cosine's, which is smooth, so the
 * finer gullies have no seams where the ridges fold.
 *
 * <p>Wavelengths are fixed in meters. Octaves shorter than 12 blocks are left out: the terrain is interpolated over
 * cells 4 blocks wide and would smooth them into zigzags. That keeps all three at 1:1, two at 1:2, one at 1:4 and
 * none in the smaller sizes, whose blocks are 8 m wide or more. Plain Java with no state, like {@link Erosion}.
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
	/** The first octave's depth in the softest rock and in the hardest, as a share of {@link #DEPTH}. */
	private static final double SOFT_MAIN = 0.85;
	private static final double HARD_MAIN = 1.15;
	/**
	 * The finer octaves' depth, as a share of their usual one: in soft rock and in hard rock, bare and under a full
	 * cover. Bare soft rock is cut into dense rills, and a cover of soil hides most of them.
	 */
	private static final double FINE_SOFT_BARE = 1.4;
	private static final double FINE_SOFT_COVERED = 0.4;
	private static final double FINE_HARD_BARE = 1.0;
	private static final double FINE_HARD_COVERED = 0.6;
	/** How sharp hard rock stays under a full cover; bare ground is fully sharp, and covered soft rock round. */
	private static final double HARD_COVERED_SHARPNESS = 0.6;
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
		if (octaves == 0) {
			return 0;
		}
		double fine = Math.max(Math.max(FINE_SOFT_BARE, FINE_SOFT_COVERED), Math.max(FINE_HARD_BARE, FINE_HARD_COVERED));
		return DEPTH * MAX_SLOPE * wavelength * (Math.max(SOFT_MAIN, HARD_MAIN) + fine * (1 - 2.0 / (1 << octaves)));
	}

	/**
	 * Whether ground of slope {@code (gx, gz)}, in blocks per block, is steep enough for gullies. Everywhere else
	 * {@link #depth} is 0 whatever the rock and the climate, so callers can skip working them out.
	 */
	public boolean cuts(double gx, double gz) {
		return octaves > 0 && gx * gx + gz * gz > SMOOTH_SLOPE * SMOOTH_SLOPE;
	}

	/**
	 * Blocks to add to the smooth ground at {@code (x, z)}, given in blocks, where the smooth ground's slope is
	 * {@code (gx, gz)} in blocks per block. {@code hardness} is that of the rock, from 0 (soft beds) to 1 (granite),
	 * as {@link Geology#hardness} gives it; {@code cover} is how much soil and plants cover the ground, from 0 (bare:
	 * dry or frozen) to 1 (wet and mild).
	 */
	public double depth(double x, double z, double gx, double gz, double hardness, double cover) {
		if (octaves == 0) {
			return 0;
		}
		double slope = Math.sqrt(gx * gx + gz * gz);
		double cut = GeoNoise.smoothstep(SMOOTH_SLOPE, FULL_SLOPE, slope);
		if (cut == 0) {
			return 0;
		}
		double hard = GeoNoise.clamp01(hardness), soft = 1 - hard;
		double covered = GeoNoise.clamp01(cover), bare = 1 - covered;
		double sharpness = bare + HARD_COVERED_SHARPNESS * hard * covered;
		double main = SOFT_MAIN * soft + HARD_MAIN * hard;
		double fine = soft * (FINE_SOFT_BARE * bare + FINE_SOFT_COVERED * covered)
			+ hard * (FINE_HARD_BARE * bare + FINE_HARD_COVERED * covered);
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
			double share = amplitude * (octave == 0 ? main : fine);
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
					// A triangle wave with the cosine's crests and troughs: sharp ridges and V-shaped gullies.
					double triangle = 1 - 4 * Math.abs(turns - Math.rint(turns));
					value += (cos + sharpness * (triangle - cos)) * w;
					valueX -= sin * acrossX * w;
					valueZ -= sin * acrossZ * w;
					weights += w;
				}
			}
			h += share * value / weights;
			// The gradient of this octave's cosines, in blocks per block, ignoring how the weights change.
			dx += share * 2 * Math.PI * f * valueX / weights;
			dz += share * 2 * Math.PI * f * valueZ / weights;
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
