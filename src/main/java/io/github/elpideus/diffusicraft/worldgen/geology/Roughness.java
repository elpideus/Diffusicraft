package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Knolls, hollows and small scarps: relief finer than the terrain model's 30 m pixels, on all land.
 *
 * <p>The terrain is interpolated smoothly between pixels 30 m apart, so at the larger sizes level ground lies as smooth
 * as a sheet for dozens of blocks, and the {@link Gullies} cut only the slopes. Real ground undulates at every scale.
 * Roughness adds knolls and hollows, octaves of gradient noise from {@value #WAVELENGTH_METERS} m across down to 16
 * blocks, each half as wide as the one before and two-thirds as high, so the finer ones show. It adds scarps too: small
 * cliffs along the contours of a noise of their own, {@value #SCARP_METERS} m high, about every quarter of a kilometre
 * where they are. Behind each crest the ground falls gently away to the foot of the next, as on a cuesta, so the
 * scarps lower the ground as much as they lift it. Noise {@value #REGION_METERS} m across makes some country rougher
 * than the rest, and puts the scarps in some of it.
 *
 * <p>Bare ground, dry or frozen, has sharp-crested knolls and full scarps. Under soil and plants, where it is wet and
 * mild, the knolls are rounded and the scarps lower. Rugged land is rougher than plains.
 *
 * <p>Wavelengths are fixed in meters, and heights in blocks are shares of the widest wavelength in blocks, so the
 * knolls keep their shape at every size. Octaves shorter than 16 blocks are left out: the terrain is interpolated over
 * cells 4 blocks wide. That keeps four octaves at 1:1, three at 1:2, two at 1:4, one at 1:8 and none in the smaller
 * sizes, whose blocks are 16 m wide or more and show the model's own detail. Scarps need their noise at least
 * {@value #MIN_SCARP_WAVELENGTH} blocks across, from 1:4 up, and stand at least {@value #MIN_SCARP_BLOCKS} blocks
 * high. Plain Java with no state, like {@link Gullies}.
 */
public final class Roughness {
	/** Wavelength of the first octave of knolls, in meters. */
	static final double WAVELENGTH_METERS = 240;
	/** Octaves at most. */
	private static final int OCTAVES = 4;
	/** The shortest wavelength kept, in blocks. */
	static final double MIN_WAVELENGTH = 16;
	/** How high the first octave's knolls rise and its hollows sink, at most, as a share of its wavelength. */
	private static final double HEIGHT = 0.04;
	/** How high each octave's knolls are, as a share of the last's. */
	private static final double PERSISTENCE = 0.65;
	/**
	 * The mean of the noise's magnitude, which centres the ridged knolls of bare ground on zero: they peak along the
	 * noise's zero contours.
	 */
	private static final double MEAN_MAGNITUDE = 0.248;
	/** How much of the knolls of bare ground are ridged rather than rounded. */
	private static final double BARE_RIDGES = 0.7;
	/** Wavelength of the noise that makes some country rougher than the rest and puts the scarps in some, in meters. */
	static final double REGION_METERS = 4000;
	/** The roughness of the smoothest country, as a share of the roughest. */
	private static final double SMOOTHEST = 0.3;
	/** The roughness of plains and of rugged land, as shares of the usual. */
	private static final double PLAINS = 0.8;
	private static final double RUGGED = 1.3;
	/** Relief, in meters of standard deviation over 1.4 km, of plains and of rugged land. */
	private static final double PLAINS_RELIEF = 10;
	private static final double RUGGED_RELIEF = 150;
	/** Wavelength of the noise whose contours the scarps follow, in meters. */
	static final double SCARP_WAVELENGTH_METERS = 800;
	/** The shortest wavelength of that noise at which there are scarps, in blocks. */
	static final double MIN_SCARP_WAVELENGTH = 200;
	/** Height of a scarp, in meters, and the least in blocks. */
	static final double SCARP_METERS = 3.5;
	static final double MIN_SCARP_BLOCKS = 2;
	/** Scarps per unit of their noise: about one every 230 m. */
	private static final double SCARP_STEPS = 3;
	/** The share of each step that its face takes: about 3 m at 1:1. */
	private static final double RISER = 0.015;
	/** Where the region's noise starts to bring scarps, and where they are full. */
	private static final double SCARPS_FROM = 0.05;
	private static final double SCARPS_FULL = 0.4;
	/** The height of the scarps under a full cover of soil and plants, as a share of those of bare ground. */
	private static final double COVERED_SCARPS = 0.6;
	/** The angle between the axes of one octave of knolls and the next, so their lattices do not line up. */
	private static final double TURN = 0.6435;
	private static final double TURN_COS = Math.cos(TURN);
	private static final double TURN_SIN = Math.sin(TURN);

	private final long seed;
	private final double wavelength;
	private final int octaves;
	private final double region;
	private final double scarpWavelength;
	private final double scarpHeight;

	/**
	 * Roughness for a world whose blocks are {@code metersPerBlock} wide and, below its highest mountains,
	 * {@code verticalMetersPerBlock} high.
	 */
	public Roughness(long seed, double metersPerBlock, double verticalMetersPerBlock) {
		this.seed = GeoNoise.mix(seed ^ 0x524F554748L);
		this.wavelength = WAVELENGTH_METERS / metersPerBlock;
		int octaves = 0;
		while (octaves < OCTAVES && wavelength / (1 << octaves) >= MIN_WAVELENGTH) {
			octaves++;
		}
		this.octaves = octaves;
		this.region = REGION_METERS / metersPerBlock;
		this.scarpWavelength = SCARP_WAVELENGTH_METERS / metersPerBlock;
		this.scarpHeight = scarpWavelength >= MIN_SCARP_WAVELENGTH
			? Math.max(SCARP_METERS / verticalMetersPerBlock, MIN_SCARP_BLOCKS) : 0;
	}

	/** Whether there is any roughness at this scale. */
	public boolean any() {
		return octaves > 0;
	}

	/** Whether there are scarps at this scale. */
	public boolean scarps() {
		return scarpHeight > 0;
	}

	/**
	 * Blocks to add to the smooth ground at {@code (x, z)}, given in blocks. {@code cover} is how much soil and plants
	 * cover the ground, from 0 (bare: dry or frozen) to 1 (wet and mild), and {@code relief} how rugged the land is,
	 * as {@code TerrainField.Column#relief} has it, in meters.
	 */
	public double height(double x, double z, double cover, double relief) {
		if (octaves == 0) {
			return 0;
		}
		double bare = 1 - GeoNoise.clamp01(cover);
		double rough = SMOOTHEST + (1 - SMOOTHEST)
			* GeoNoise.smoothstep(-0.5, 0.5, GeoNoise.gradient(seed, x / region, z / region));
		rough *= PLAINS + (RUGGED - PLAINS) * GeoNoise.smoothstep(PLAINS_RELIEF, RUGGED_RELIEF, relief);
		double ridged = BARE_RIDGES * bare;
		double h = 0, length = wavelength, height = HEIGHT * wavelength, cos = 1, sin = 0;
		for (int octave = 0; octave < octaves; octave++) {
			double u = (x * cos - z * sin) / length, v = (x * sin + z * cos) / length;
			double n = GeoNoise.gradient(seed + 1 + octave, u, v);
			h += height * ((1 - ridged) * n + ridged * 2 * (MEAN_MAGNITUDE - Math.abs(n)));
			length *= 0.5;
			height *= PERSISTENCE;
			double turned = cos * TURN_COS - sin * TURN_SIN;
			sin = sin * TURN_COS + cos * TURN_SIN;
			cos = turned;
		}
		h *= rough;
		if (scarpHeight > 0) {
			double where = GeoNoise.smoothstep(SCARPS_FROM, SCARPS_FULL,
				GeoNoise.gradient(seed - 1, x / region, z / region));
			if (where > 0) {
				double s = SCARP_STEPS * GeoNoise.gradient(seed - 2, x / scarpWavelength, z / scarpWavelength);
				double f = s - Math.floor(s);
				// A sawtooth with a mean of zero: the tread falls back across the step, the face rises at its end.
				double tooth = GeoNoise.smoothstep(1 - RISER, 1, f) - f + 0.5 - RISER / 2;
				h += scarpHeight * where * (COVERED_SCARPS + (1 - COVERED_SCARPS) * bare) * tooth;
			}
		}
		return h;
	}
}
