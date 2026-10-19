package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Gullies;

/**
 * The height of the ground, in blocks: the terrain's elevation through the world's {@link WorldScale}, with the
 * {@link Gullies} cut into its slopes, the {@link Geology#ledge ledges} of the sedimentary beds standing out of
 * them, and cliffs along the sea. The density, the materials, the rivers, the Hollows, the ceiling of each chunk and
 * the levels of detail all read the ground here, so they agree on where it is. The climate and the biomes keep the
 * smooth elevation, except on the coast: the other details are too small to change them.
 *
 * <p>Both take their character from the rock under the slope, as the world's {@link Geology} has it, and from how much
 * soil and plants cover it, which the climate decides: it takes rain (by the same aridity the biomes use) and warmth.
 * Deserts, tundra and the ground above the tree line are bare. Ledges stand out most on bare, steep slopes; soil
 * creeping down the slope rounds them off.
 *
 * <p>The terrain comes in pixels 30 m wide, too coarse for a cliff: its coasts are gentle slopes. Where the land
 * stands high a short way inland, on some stretches of coast, the rise is gathered into a cliff. The strip along the
 * coastline that {@link #cliffZone} measures becomes a beach or a rock shelf a block above the water, then a face, then
 * a flat top at the height of the ground where the strip ends. Noise moves the face across the strip, from the
 * waterline, where the cliff drops straight into the sea, to most of the way inland. The faces are too steep for the
 * terrain's interpolation between cells, so the density takes the columns of a cliff and those beside it one by one
 * ({@link #cliff}).
 *
 * <p>Points are given in blocks, block {@code x} spanning {@code [x, x + 1)}, as {@link Erosion.Surface} has them.
 * Get one from {@link TerrainField#surface}; it holds no state of its own and is safe on any thread.
 */
public final class TerrainSurface implements Erosion.Surface {
	/** Slope, blocks per block, below which the ground keeps its shape: about 9°. */
	private static final double SMOOTH_SLOPE = 0.15;
	/** Slope at which the ledges stand out fully: about 27°. */
	private static final double LEDGE_SLOPE = 0.5;
	/** The share of the ledges left under a full cover of soil and plants. */
	private static final double COVERED_LEDGES = 0.4;
	/** Real width of the strip along the coastline that cliffs reshape, in meters. */
	private static final double CLIFF_ZONE = 60;
	/** Narrowest that strip gets, in blocks, so that the smaller scales still have cliffs. */
	private static final double CLIFF_ZONE_BLOCKS = 6;
	/** Real wavelength of the noise that picks the stretches of coast with cliffs, in meters. */
	private static final double CLIFF_WAVELENGTH = 1500;
	/**
	 * Real height of the ground at the inland end of the strip, in meters, below which there is no cliff and above which
	 * the cliff is whole. In blocks, at least {@link #LOW_CLIFF_BLOCKS} and {@link #HIGH_CLIFF_BLOCKS}.
	 */
	private static final float LOW_CLIFF = 4;
	private static final float HIGH_CLIFF = 15;
	private static final double LOW_CLIFF_BLOCKS = 1.5;
	private static final double HIGH_CLIFF_BLOCKS = 3;
	/** Where the face stands across the strip, as a share of its width: on average and how far noise moves it. */
	private static final double FACE = 0.35;
	private static final double FACE_SPREAD = 0.4;
	/** Wavelength of the noise that moves the face, in strip widths. */
	private static final double FACE_WAVELENGTH = 3;
	/**
	 * Blocks out to sea from the coastline that the density still takes one by one beside a cliff: the interpolation
	 * would smear the cliff over a cell, 4 blocks, into the sea.
	 */
	static final double CLIFF_MARGIN = 5;

	private final TerrainField field;
	private final WorldScale scale;
	private final Gullies gullies;
	private final Geology geology;
	private final double pixelsPerBlock;
	private final double lowest;
	private final double highest;
	private final long cliffSeed;
	/** Width of the strip of cliffs in pixels, and the margin beyond it out to sea. */
	private final double zone;
	private final double margin;
	private final double lowCliff;
	private final double highCliff;
	/** The ground under a cliff, a block above the water. */
	private final double shelf;

	TerrainSurface(TerrainField field, WorldScale scale) {
		this.field = field;
		this.scale = scale;
		this.gullies = new Gullies(field.seed(), scale.metersPerBlock());
		// Only the deep layers depend on the bottom of the world, and the shape of the slopes reads none of them.
		this.geology = new Geology(field.geologySeed(), 0, scale.metersPerBlock(), scale.verticalMetersPerBlock());
		this.pixelsPerBlock = scale.pixelsPerBlock();
		this.lowest = scale.minSurfaceY();
		this.highest = scale.maxSurfaceY();
		this.cliffSeed = field.seed() ^ 0x5EAC11FF5L;
		this.zone = cliffZone(scale) * pixelsPerBlock;
		this.margin = CLIFF_MARGIN * pixelsPerBlock;
		int sea = scale.seaLevel();
		this.lowCliff = Math.max(scale.surfaceY(LOW_CLIFF) - sea, LOW_CLIFF_BLOCKS);
		this.highCliff = Math.max(scale.surfaceY(HIGH_CLIFF) - sea, HIGH_CLIFF_BLOCKS);
		this.shelf = sea + 0.5;
	}

	/** Width of the strip along the coastline that cliffs reshape at a scale, in blocks. */
	public static double cliffZone(WorldScale scale) {
		return Math.max(CLIFF_ZONE / scale.metersPerBlock(), CLIFF_ZONE_BLOCKS);
	}

	/** The ground at the centre of column {@code (x, z)}. */
	public float y(int x, int z) {
		return (float) y(x + 0.5, z + 0.5);
	}

	@Override
	public double y(double x, double z) {
		double i = z * pixelsPerBlock, j = x * pixelsPerBlock;
		float[] gradient = new float[2];
		float elevation = field.elevation(i, j, gradient);
		// Meters per pixel to blocks per block.
		double rate = scale.surfaceRate(elevation) * pixelsPerBlock;
		double gx = gradient[1] * rate, gz = gradient[0] * rate;
		double y = scale.surfaceY(elevation);
		double slope = Math.sqrt(gx * gx + gz * gz);
		if (slope > SMOOTH_SLOPE) {
			TerrainField.Column column = new TerrainField.Column();
			field.column(i, j, column);
			Geology.Site site = geology.site(x, z, scale.surfaceY(column.meanElevation), column.meanElevation,
				column.relief);
			double cover = cover(column.temperature, column.precipitation);
			if (gullies.cuts(gx, gz)) {
				y += gullies.depth(x, z, gx, gz, geology.hardness(site, y), cover);
			}
			// The ledges follow the beds through the gullies, round their spurs and into their heads.
			double ledges = GeoNoise.smoothstep(SMOOTH_SLOPE, LEDGE_SLOPE, slope) * (1 - (1 - COVERED_LEDGES) * cover);
			y += geology.ledge(site, y, ledges);
		}
		return Math.clamp(shore(x, z, i, j, elevation, y), lowest, highest);
	}

	/**
	 * The ground at the centre of column {@code (x, z)} for the levels of detail too coarse for the gullies and the
	 * ledges: the terrain's height with only the shapes of the shore.
	 */
	public float shore(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float elevation = field.elevation(i, j);
		return (float) Math.clamp(shore(cx, cz, i, j, elevation, scale.surfaceY(elevation)), lowest, highest);
	}

	/** The ground {@code y} at a point of the given elevation, with the sea cliffs. */
	private double shore(double x, double z, double i, double j, float elevation, double y) {
		float coast = field.coast(i, j);
		return coast > 0f && coast < zone ? sharpen(x, z, i, j, coast, y) : y;
	}

	/**
	 * The ground of column {@code (x, z)}, as {@link #y(int, int)} has it, where a cliff shapes it or stands beside it,
	 * out to sea within {@link #CLIFF_MARGIN} blocks of the coastline; NaN elsewhere.
	 */
	public float cliff(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float coast = field.coast(i, j);
		if (coast <= -margin || coast >= zone || strength(cx, cz, i, j, coast, new double[1]) <= 0) {
			return Float.NaN;
		}
		return (float) y(cx, cz);
	}

	/**
	 * Gathers the rise of the ground across the strip of cliffs into a face, at a point {@code coast} pixels inland.
	 */
	private double sharpen(double x, double z, double i, double j, float coast, double y) {
		double[] top = new double[1];
		double k = strength(x, z, i, j, coast, top);
		if (k <= 0) {
			return y;
		}
		// The top stays the ground's own height at the end of the strip; it fades there into the ground's details.
		k *= 1 - GeoNoise.smoothstep(0.7 * zone, zone, coast);
		double wavelength = FACE_WAVELENGTH * zone / pixelsPerBlock;
		double face = zone * (FACE + FACE_SPREAD * GeoNoise.fractal(cliffSeed + 1, x / wavelength, z / wavelength, 2));
		double sharp = coast < face ? Math.min(y, shelf) : Math.max(y, top[0]);
		return y + k * (sharp - y);
	}

	/**
	 * How much of a cliff stands at a point {@code coast} pixels from the coastline, from 0 to 1, with the height of its
	 * top in {@code top[0]}: the smooth ground inland along the coast's normal, where the strip ends.
	 */
	private double strength(double x, double z, double i, double j, float coast, double[] top) {
		float[] normal = new float[2];
		field.coast(i, j, normal);
		// Between two coasts close together, as across a spit, the normal shrinks and turns: no cliffs there.
		double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
		double straight = GeoNoise.smoothstep(0.5, 0.8, length);
		if (straight <= 0) {
			return 0;
		}
		double mpb = scale.metersPerBlock();
		double stretch = GeoNoise.smoothstep(-0.3, 0.2,
			GeoNoise.fractal(cliffSeed, x * mpb / CLIFF_WAVELENGTH, z * mpb / CLIFF_WAVELENGTH, 2));
		if (stretch <= 0) {
			return 0;
		}
		double reach = (zone - coast) / length;
		top[0] = scale.surfaceY(field.elevation(i + normal[0] * reach, j + normal[1] * reach));
		return straight * stretch * GeoNoise.smoothstep(lowCliff, highCliff, top[0] - scale.seaLevel());
	}

	/**
	 * How much soil and plants cover the ground, from 0 to 1, at an annual mean temperature in °C and annual
	 * precipitation in mm: none in deserts or where the ground is frozen most of the year, full where it is humid and
	 * mild.
	 */
	static double cover(float temperature, float precipitation) {
		return GeoNoise.smoothstep(0.5, 1.5, ClimateMapping.aridityRatio(temperature, precipitation))
			* GeoNoise.smoothstep(-4, 4, temperature);
	}
}
