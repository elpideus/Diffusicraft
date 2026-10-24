package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Gullies;
import io.github.elpideus.diffusicraft.worldgen.geology.Roughness;

import java.util.ArrayList;
import java.util.List;

/**
 * The height of the ground, in blocks: the terrain's elevation through the world's {@link WorldScale}, which the
 * {@link TerrainField} has already eased down to the sea along the coasts ({@link CoastSlope}), with the knolls and
 * small scarps of its {@link Roughness}, the
 * {@link Gullies} cut into its slopes, the {@link Geology#ledge ledges} of the sedimentary beds standing out of them,
 * and cliffs along some of the sea's shores. The density, the materials, the rivers, the Hollows, the ceiling of each
 * chunk and the levels of detail all read the ground here, so they agree on where it is. The climate and the biomes
 * keep the smooth elevation, except on the coast: the other details are too small to change them.
 *
 * <p>They take their character from how much soil and plants cover the ground, which the climate decides: it takes
 * rain (by the same aridity the biomes use) and warmth. Deserts, tundra and the ground above the tree line are bare.
 * The gullies and the ledges take it from the rock under the slope too, as the world's {@link Geology} has it. Ledges
 * stand out most on bare, steep slopes; soil creeping down the slope rounds them off. The roughness grows in from
 * {@value #ROUGH_SHORE} blocks above the sea and from the inland end of the strip of cliffs, so the beaches stay
 * smooth, and it never lowers the ground to less than a block above the sea.
 *
 * <p>The coasts ease down to the water: the terrain holds the land within reach of the coastline under an envelope
 * that climbs gently from the waterline and lets any ground through farther inland, so a hill or a mountain by the sea
 * has a slope at its foot rather than a wall ({@link CoastSlope}). On a few stretches of coast, where the land stands
 * high a short way inland, the first part of the rise is gathered into a sea cliff. The strip along the coastline that
 * {@link #cliffZone} measures becomes a beach or a rock shelf a block above the water, then a face up to the cliff's
 * crest, then a bench at the crest until the ground climbs past it, or a slope at half the face's pace where the
 * ground stands higher. A face is never sheer: it climbs {@value #FACE_STEEP_MIN} to {@value #FACE_STEEP_MAX} blocks
 * a block on average, steepest halfway up, with its foot and crest rounded off, and takes at most
 * {@value #CLIFF_HEIGHT_MIN} to {@value #CLIFF_HEIGHT_MAX} m of the rise, by the stretch of coast; a lower coast's
 * whole rise. The crest rises and falls along the coast, down to {@value #CREST_LOW} of that. Noise moves the foot
 * across the strip, from the waterline to halfway inland, and in and out round buttresses and coves a few blocks
 * across. The faces are too steep for the terrain's interpolation between cells, so the density takes the columns of
 * a cliff and those beside it one by one ({@link #cliff}).
 *
 * <p>The terrain's seas are flats near the shore, a metre deep or less for hundreds of meters, where real ones deepen
 * along a beach's equilibrium profile. Out to sea, the floor lies at least as deep as that profile reaches, up to
 * {@link TerrainField#COAST_REACH} from the coastline and as deep as it reaches there beyond it: 5 m. Only the two
 * largest sizes show it; the others keep the shallowest sea a block deep.
 *
 * <p>Off rocky coasts lie islets, the skerries of a drowned rocky shore: low mounds with an irregular outline, from
 * a wash rock barely above the water to a hummock {@value #ISLET_HEIGHT_MAX} m high but no higher than three fifths
 * of its radius, {@value #ISLET_RADIUS} to {@value #ISLET_RADIUS_MAX} m in radius, which rise from the sea floor with
 * sloping sides, drawn out along the coast up to twice as long as wide, past at least
 * {@value #ISLET_GAP} m of open water and within {@value #ISLET_BAND} m beyond it. The places for them lie on a
 * jittered grid {@value #ISLET_SPACING} m apart; noise picks the stretches of coast that have them and gathers them
 * into clusters, and islets that meet merge. The density takes them block by block, as it does cliffs, so the
 * smaller worlds keep them at a few blocks across, within the 11 blocks of sea the smallest's coastline reaches.
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
	/** Height above the sea over which the roughness grows in, in blocks. */
	private static final double ROUGH_SHORE = 8;
	/** Distance inland of the strip of cliffs over which the roughness grows in, in pixels: 60 m. */
	private static final double ROUGH_COAST = 2;
	/** Real width of the strip along the coastline that cliffs reshape, in meters. */
	private static final double CLIFF_ZONE = 60;
	/** Narrowest that strip gets, in blocks, so that the smaller scales still have cliffs. */
	private static final double CLIFF_ZONE_BLOCKS = 6;
	/** Real wavelength of the noise that picks the stretches of coast with cliffs, in meters. */
	private static final double CLIFF_WAVELENGTH = 1500;
	/** The noise, in about [-1, 1], from which a stretch of coast has cliffs, and at which it has them whole. */
	private static final double CLIFF_FROM = 0.3;
	private static final double CLIFF_TO = 0.4;
	/**
	 * Real height of the ground at the inland end of the strip, in meters, below which there is no cliff and above which
	 * the cliff is whole. In blocks, at least {@link #LOW_CLIFF_BLOCKS} and {@link #HIGH_CLIFF_BLOCKS}.
	 */
	private static final float LOW_CLIFF = 8;
	private static final float HIGH_CLIFF = 20;
	private static final double LOW_CLIFF_BLOCKS = 1.5;
	private static final double HIGH_CLIFF_BLOCKS = 3;
	/**
	 * Real height of the tallest face a cliff takes, in meters, on the stretches of coast with the lowest cliffs and
	 * with the highest; in blocks, at least {@link #CLIFF_HEIGHT_BLOCKS_MIN} and {@link #CLIFF_HEIGHT_BLOCKS_MAX}.
	 */
	private static final float CLIFF_HEIGHT_MIN = 6;
	private static final float CLIFF_HEIGHT_MAX = 16;
	private static final double CLIFF_HEIGHT_BLOCKS_MIN = 4;
	private static final double CLIFF_HEIGHT_BLOCKS_MAX = 8;
	/** The least share of that height the crest keeps as it rises and falls along the coast. */
	private static final double CREST_LOW = 0.4;
	/** Real wavelength of the noise that raises and lowers the crest, in meters. */
	private static final double CREST_WAVELENGTH = 400;
	/** How steep a face climbs on average, in blocks per block: from 50° to 72°. */
	private static final double FACE_STEEP_MIN = 1.2;
	static final double FACE_STEEP_MAX = 3;
	/** Real wavelength of the noise that sets how steep the faces climb, in meters. */
	private static final double FACE_STEEP_WAVELENGTH = 300;
	/**
	 * The most of the strip's width a face runs across: the narrow strips of the smaller sizes keep their cliffs
	 * steeper than {@link #FACE_STEEP_MAX}.
	 */
	static final double FACE_RUN = 0.35;
	/** How much the face's foot and crest are rounded off: half as steep as its average, and its middle steeper. */
	static final double FACE_ROUNDING = 0.5;
	/** How steep the ground climbs above the crest until it meets the land, as a share of the face's steepness. */
	private static final double ABOVE_CREST = 0.5;
	/** Where the face's foot stands across the strip, as a share of its width: its mean and how far noise moves it. */
	private static final double FACE = 0.25;
	private static final double FACE_SPREAD = 0.25;
	/** Wavelength of the noise that moves the foot, in strip widths. */
	private static final double FACE_WAVELENGTH = 3;
	/**
	 * How far the foot moves in and out round the buttresses and coves of the face, as a share of the strip's width,
	 * and the wavelength of the noise that moves it, in strip widths.
	 */
	private static final double FACE_JAG = 0.08;
	private static final double FACE_JAG_WAVELENGTH = 0.3;
	/**
	 * Blocks out to sea from the coastline that the density still takes one by one beside a cliff: the interpolation
	 * would smear the cliff over a cell, 4 blocks, into the sea.
	 */
	static final double CLIFF_MARGIN = 5;
	/**
	 * The shape of the sea floor off a beach, in m^(1/3): the depth {@code d} meters out to sea is {@code A d^(2/3)}
	 * (Dean's equilibrium profile, for fine sand).
	 */
	private static final double SHOREFACE = 0.1;
	/** Real spacing of the places out to sea that can hold an islet, in meters, and the least in blocks. */
	private static final double ISLET_SPACING = 70;
	private static final double ISLET_SPACING_BLOCKS = 10;
	/** Real radius of an islet, in meters, and in blocks at least, from the smallest to the largest. */
	private static final double ISLET_RADIUS = 8;
	private static final double ISLET_RADIUS_MAX = 30;
	private static final double ISLET_RADIUS_BLOCKS = 2;
	private static final double ISLET_RADIUS_BLOCKS_MAX = 4;
	/** Real open water between an islet and the coastline, in meters, and the least in blocks. */
	private static final double ISLET_GAP = 20;
	private static final double ISLET_GAP_BLOCKS = 2;
	/** Real width of the band beyond that water where islets lie, in meters, and the least in blocks. */
	private static final double ISLET_BAND = 240;
	private static final double ISLET_BAND_BLOCKS = 10;
	/** Real height of an islet's top above the sea, in meters, and in blocks at least, from the lowest to the highest. */
	private static final float ISLET_HEIGHT_MIN = 2;
	private static final float ISLET_HEIGHT_MAX = 12;
	private static final double ISLET_HEIGHT_BLOCKS_MIN = 1.5;
	private static final double ISLET_HEIGHT_BLOCKS_MAX = 4;
	/** An islet's top stays this far below the land at the end of the strip, as a share of the land's height. */
	private static final double ISLET_BELOW_LAND = 0.8;
	/** The share of the places in the band that hold an islet, in the thick of a cluster off the rockiest coasts. */
	private static final double ISLET_SHARE = 0.55;
	/** Real wavelengths of the noise that picks the stretches of coast with islets, and of the clusters along them. */
	private static final double ISLET_WAVELENGTH = 4000;
	private static final double CLUSTER_WAVELENGTH = 350;
	/** The most an islet's top stands above the sea, as a share of its radius: wide mounds, not columns. */
	private static final double ISLET_STEEPNESS = 0.6;
	/** The share of an islet's radius over which its sides fall from the top to the sea floor; the rest is top. */
	private static final double ISLET_SIDES = 0.75;
	/** How many times longer than wide an islet is along the coast, at most: the ice and the waves drew them out. */
	private static final double ISLET_ASPECT_MAX = 2;
	/** How far noise moves the outline of an islet in or out, as a share of its radius. */
	private static final double ISLET_ROUGHNESS = 0.4;

	/**
	 * An islet, in blocks: its centre, the direction of the coast there, its half-width across the coast and its
	 * half-length along it, and the height of its top.
	 */
	private record Islet(double x, double z, double tx, double tz, double radius, double length, double top) {
	}

	/**
	 * A place in a cell of the grid that can hold an islet, from the cell's hashes: the share it is drawn at, its
	 * centre, its half-width and its half-length along the coast.
	 */
	private record Place(int cellX, int cellZ, double chance, double x, double z, double radius, double length) {
		/** The farthest the outline reaches from the centre. */
		double extent() {
			return length * (1 + ISLET_ROUGHNESS);
		}
	}

	private final TerrainField field;
	private final WorldScale scale;
	private final Gullies gullies;
	private final Roughness roughness;
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
	/** The tallest face a cliff takes, in blocks, on the stretches with the lowest cliffs and with the highest. */
	private final double cliffHeightMin;
	private final double cliffHeightMax;
	/** The ground under a cliff, a block above the water. */
	private final double shelf;
	/** Whether the profile of the sea floor off the shore lies deeper anywhere than the shallowest sea. */
	private final boolean shoreface;
	private final int sea;
	private final long isletSeed;
	/** The islets' spacing, open water, band, heights and the farthest any reaches from its centre, in blocks. */
	private final double isletSpacing;
	private final double isletGap;
	private final double isletBand;
	private final double isletHeightMin;
	private final double isletHeightMax;
	private final double isletExtent;

	TerrainSurface(TerrainField field, WorldScale scale) {
		this.field = field;
		this.scale = scale;
		this.gullies = new Gullies(field.seed(), scale.metersPerBlock());
		this.roughness = new Roughness(field.seed(), scale.metersPerBlock(), scale.verticalMetersPerBlock());
		// Only the deep layers depend on the bottom of the world, and the shape of the slopes reads none of them.
		this.geology = new Geology(field.geologySeed(), 0, scale.metersPerBlock(), scale.verticalMetersPerBlock());
		this.pixelsPerBlock = scale.pixelsPerBlock();
		this.lowest = scale.minSurfaceY();
		this.highest = scale.maxSurfaceY();
		this.cliffSeed = field.seed() ^ 0x5EAC11FF5L;
		this.zone = cliffZone(scale) * pixelsPerBlock;
		this.margin = CLIFF_MARGIN * pixelsPerBlock;
		this.sea = scale.seaLevel();
		this.lowCliff = Math.max(scale.surfaceY(LOW_CLIFF) - sea, LOW_CLIFF_BLOCKS);
		this.highCliff = Math.max(scale.surfaceY(HIGH_CLIFF) - sea, HIGH_CLIFF_BLOCKS);
		this.cliffHeightMin = cliffHeightMin(scale);
		this.cliffHeightMax = cliffHeightMax(scale);
		this.shelf = sea + 0.5;
		this.shoreface = shoreface(-Coastline.REACH) < sea - 1;
		double mpb = scale.metersPerBlock();
		this.isletSeed = field.seed() ^ 0x57AC4A2C11L;
		this.isletSpacing = Math.max(ISLET_SPACING / mpb, ISLET_SPACING_BLOCKS);
		this.isletGap = Math.max(ISLET_GAP / mpb, ISLET_GAP_BLOCKS);
		this.isletBand = Math.max(ISLET_BAND / mpb, ISLET_BAND_BLOCKS);
		this.isletHeightMin = Math.max(scale.surfaceY(ISLET_HEIGHT_MIN) - sea, ISLET_HEIGHT_BLOCKS_MIN);
		this.isletHeightMax = Math.max(scale.surfaceY(ISLET_HEIGHT_MAX) - sea, ISLET_HEIGHT_BLOCKS_MAX);
		this.isletExtent = radius(1) * ISLET_ASPECT_MAX * (1 + ISLET_ROUGHNESS);
	}

	/** Width of the strip along the coastline that cliffs reshape at a scale, in blocks. */
	public static double cliffZone(WorldScale scale) {
		return Math.max(CLIFF_ZONE / scale.metersPerBlock(), CLIFF_ZONE_BLOCKS);
	}

	/** The tallest face a cliff takes at a scale, in blocks, on the stretches of coast with the lowest cliffs. */
	static double cliffHeightMin(WorldScale scale) {
		return Math.max(scale.surfaceY(CLIFF_HEIGHT_MIN) - scale.seaLevel(), CLIFF_HEIGHT_BLOCKS_MIN);
	}

	/** The tallest face a cliff takes at a scale, in blocks, on the stretches of coast with the highest cliffs. */
	static double cliffHeightMax(WorldScale scale) {
		return Math.max(scale.surfaceY(CLIFF_HEIGHT_MAX) - scale.seaLevel(), CLIFF_HEIGHT_BLOCKS_MAX);
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
		float coast = field.coast(i, j);
		// Meters per pixel to blocks per block.
		double rate = scale.surfaceRate(elevation) * pixelsPerBlock;
		double gx = gradient[1] * rate, gz = gradient[0] * rate;
		double y = scale.surfaceY(elevation);
		double slope = Math.sqrt(gx * gx + gz * gz);
		TerrainField.Column column = null;
		double cover = 0;
		if (roughness.any() && elevation > 0f && coast > zone) {
			column = new TerrainField.Column();
			field.column(i, j, column);
			cover = cover(column);
			double rough = roughness.height(x, z, cover, column.relief)
				* GeoNoise.smoothstep(zone, zone + ROUGH_COAST, coast) * GeoNoise.smoothstep(sea, sea + ROUGH_SHORE, y);
			y = Math.max(y + rough, Math.min(y, sea + 1));
		}
		if (slope > SMOOTH_SLOPE) {
			if (column == null) {
				column = new TerrainField.Column();
				field.column(i, j, column);
				cover = cover(column);
			}
			Geology.Site site = geology.site(x, z, scale.surfaceY(column.meanElevation), column.meanElevation,
				column.relief);
			if (gullies.cuts(gx, gz)) {
				y += gullies.depth(x, z, gx, gz, geology.hardness(site, y), cover);
			}
			// The ledges follow the beds through the gullies, round their spurs and into their heads.
			double ledges = GeoNoise.smoothstep(SMOOTH_SLOPE, LEDGE_SLOPE, slope) * (1 - (1 - COVERED_LEDGES) * cover);
			y += geology.ledge(site, y, ledges);
			if (elevation < 0f) {
				// The ledges never lift the sea floor out of the sea, as off a cliff's foot in the larger sizes.
				y = Math.min(y, sea - 1);
			}
		}
		return Math.clamp(shore(x, z, i, j, coast, elevation, y), lowest, highest);
	}

	/**
	 * The ground at the centre of column {@code (x, z)} for the levels of detail too coarse for the gullies and the
	 * ledges: the terrain's height with only the shapes of the shore.
	 */
	public float shore(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float coast = field.coast(i, j);
		float elevation = field.elevation(i, j);
		double y = scale.surfaceY(elevation);
		return (float) Math.clamp(shore(cx, cz, i, j, coast, elevation, y), lowest, highest);
	}

	/**
	 * The ground {@code y} at a point of the given elevation, {@code coast} pixels from the coastline, with the sea
	 * cliffs, the profile of the sea floor and the islets.
	 */
	private double shore(double x, double z, double i, double j, float coast, float elevation, double y) {
		if (coast > 0f && coast < zone) {
			y = sharpen(x, z, i, j, coast, y);
		} else if (coast < 0f && elevation < 0f && shoreface) {
			y = Math.min(y, shoreface(coast));
		}
		if (coast < 0f) {
			for (Islet islet : islets(x, z, 0)) {
				y = islet(islet, x, z, y);
			}
		}
		return y;
	}

	/** The highest the sea floor lies {@code coast} pixels from the coastline, out to sea, in blocks. */
	private double shoreface(float coast) {
		return scale.surfaceY((float) (-SHOREFACE * Math.cbrt(Math.pow(coast * WorldScale.METERS_PER_PIXEL, 2))));
	}

	/**
	 * The ground of column {@code (x, z)}, as {@link #y(int, int)} has it, where a cliff shapes it or stands beside it,
	 * out to sea within {@link #CLIFF_MARGIN} blocks of the coastline, and on an islet and within that margin of it;
	 * NaN elsewhere.
	 */
	public float cliff(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float coast = field.coast(i, j);
		boolean cliff = coast > -margin && coast < zone
			&& strength(cx, cz, i, j, coast, new double[1], new float[2]) > 0;
		if (!cliff && !(coast < margin && !islets(cx, cz, CLIFF_MARGIN).isEmpty())) {
			return Float.NaN;
		}
		return (float) y(cx, cz);
	}

	/** How much of a cliff stands at column {@code (x, z)}, from 0 to 1, as {@link #strength} has it. */
	double cliffStrength(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float coast = field.coast(i, j);
		return coast > 0f && coast < zone ? strength(cx, cz, i, j, coast, new double[1], new float[2]) : 0;
	}

	/**
	 * Gathers the first of the rise of the ground across the strip of cliffs into a face, at a point {@code coast}
	 * pixels inland.
	 */
	private double sharpen(double x, double z, double i, double j, float coast, double y) {
		double[] top = new double[1];
		float[] normal = new float[2];
		double k = strength(x, z, i, j, coast, top, normal);
		if (k <= 0) {
			return y;
		}
		// The cliff fades into the ground's details at the end of the strip.
		k *= 1 - GeoNoise.smoothstep(0.7 * zone, zone, coast);
		// The cliff's shape changes along the coast, never across it: it is read at the coastline, down the normal.
		double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]), back = coast / length / pixelsPerBlock;
		double cx = x - normal[1] * back, cz = z - normal[0] * back, mpb = scale.metersPerBlock();
		// The crest: the stretch's tallest face, rising and falling along the coast, and never above the land behind.
		double tallest = lerp(GeoNoise.smoothstep(-0.5, 0.5,
			GeoNoise.fractal(cliffSeed + 2, cx * mpb / CLIFF_WAVELENGTH, cz * mpb / CLIFF_WAVELENGTH, 2)),
			cliffHeightMin, cliffHeightMax);
		double rises = lerp(GeoNoise.smoothstep(-0.5, 0.5,
			GeoNoise.fractal(cliffSeed + 3, cx * mpb / CREST_WAVELENGTH, cz * mpb / CREST_WAVELENGTH, 2)), CREST_LOW, 1);
		double crest = Math.max(Math.min(top[0], sea + tallest * rises), shelf + 1);
		double height = crest - shelf;
		// How steep the face climbs, in blocks per block, and so how far across the strip it runs, in pixels.
		double steep = Math.max(lerp(GeoNoise.smoothstep(-0.5, 0.5, GeoNoise.fractal(cliffSeed + 4,
				cx * mpb / FACE_STEEP_WAVELENGTH, cz * mpb / FACE_STEEP_WAVELENGTH, 2)), FACE_STEEP_MIN, FACE_STEEP_MAX),
			height * pixelsPerBlock / (FACE_RUN * zone));
		double run = height / steep * pixelsPerBlock;
		// The foot wanders across the strip along the coast, and in and out round buttresses and coves; the face stays
		// on land and ends before the cliff fades.
		double wavelength = FACE_WAVELENGTH * zone / pixelsPerBlock, jag = FACE_JAG_WAVELENGTH * zone / pixelsPerBlock;
		double foot = Math.clamp(zone * (FACE + FACE_SPREAD * GeoNoise.fractal(cliffSeed + 1, cx / wavelength,
			cz / wavelength, 2) + FACE_JAG * GeoNoise.fractal(cliffSeed + 5, cx / jag, cz / jag, 3)), 0,
			0.7 * zone - run);
		// Seaward of the foot, the ground comes down to the shelf. Up the face, it is the face, steepest halfway up.
		// Above the crest it climbs on at half the face's pace until it meets the ground, or stays at the crest as a
		// bench until the ground climbs past it.
		double t = (coast - foot) / run, sharp;
		if (t <= 0) {
			sharp = Math.min(y, shelf);
		} else if (t < 1) {
			sharp = shelf + height * (t + FACE_ROUNDING * (t * t * (3 - 2 * t) - t));
		} else {
			sharp = Math.clamp(y, crest, crest + ABOVE_CREST * steep * (coast - foot - run) / pixelsPerBlock);
		}
		return y + k * (sharp - y);
	}

	/**
	 * How much of a cliff stands at a point {@code coast} pixels from the coastline, from 0 to 1: how {@link #rocky}
	 * the coast is there, on a stretch of coast with cliffs. The height of the land at the end of the strip goes into
	 * {@code top[0]} and the coast's normal, in pixels along {@code i} and {@code j}, into {@code normal}.
	 */
	private double strength(double x, double z, double i, double j, float coast, double[] top, float[] normal) {
		double rocky = rocky(i, j, coast, top, normal);
		if (rocky <= 0) {
			return 0;
		}
		double mpb = scale.metersPerBlock();
		return rocky * GeoNoise.smoothstep(CLIFF_FROM, CLIFF_TO,
			GeoNoise.fractal(cliffSeed, x * mpb / CLIFF_WAVELENGTH, z * mpb / CLIFF_WAVELENGTH, 2));
	}

	/**
	 * How rocky the coast is at a point {@code coast} pixels from the coastline, from 0 to 1: how far above the sea the
	 * eased ground stands at the end of the strip, inland along the coast's normal, on a straight coast. That height
	 * goes into {@code top[0]} and the normal, in pixels along {@code i} and {@code j}, into {@code normal}.
	 */
	private double rocky(double i, double j, float coast, double[] top, float[] normal) {
		field.coast(i, j, normal);
		// Between two coasts close together, as across a spit, the normal shrinks and turns: no cliffs there.
		double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
		double straight = GeoNoise.smoothstep(0.5, 0.8, length);
		if (straight <= 0) {
			top[0] = sea;
			return 0;
		}
		double reach = (zone - coast) / length;
		double ei = i + normal[0] * reach, ej = j + normal[1] * reach;
		top[0] = scale.surfaceY(field.elevation(ei, ej));
		return straight * GeoNoise.smoothstep(lowCliff, highCliff, top[0] - sea);
	}

	/** The islets whose outline comes within {@code reach} blocks of {@code (x, z)}: seldom more than one or two. */
	private List<Islet> islets(double x, double z, double reach) {
		List<Islet> found = List.of();
		double far = isletExtent + reach;
		int x0 = (int) Math.floor((x - far) / isletSpacing), x1 = (int) Math.floor((x + far) / isletSpacing);
		int z0 = (int) Math.floor((z - far) / isletSpacing), z1 = (int) Math.floor((z + far) / isletSpacing);
		for (int cellZ = z0; cellZ <= z1; cellZ++) {
			for (int cellX = x0; cellX <= x1; cellX++) {
				Islet islet = islet(cellX, cellZ, x, z, reach);
				if (islet != null) {
					if (found.isEmpty()) {
						found = new ArrayList<>(2);
					}
					found.add(islet);
				}
			}
		}
		return found;
	}

	/**
	 * The islet of a cell of the grid of places that can hold one, if it has one whose outline comes within
	 * {@code reach} blocks of {@code (x, z)}; {@code null} otherwise. The hashes rule out most places before the
	 * terrain is read, so the open sea, which asks about every column, costs a few hashes a column.
	 */
	private Islet islet(int cellX, int cellZ, double x, double z, double reach) {
		Place candidate = place(cellX, cellZ);
		if (candidate == null) {
			return null;
		}
		double px = candidate.x(), pz = candidate.z(), extent = candidate.extent();
		double dx = x - px, dz = z - pz, near = extent + reach;
		if (dx * dx + dz * dz > near * near) {
			return null;
		}
		// Out to sea past open water, within the band, its centre within the coastline's reach: the columns of its
		// outline and the margin around it need no distance of their own.
		double i = pz * pixelsPerBlock, j = px * pixelsPerBlock;
		float coast = field.coast(i, j);
		double clear = isletGap + extent, out = -coast / pixelsPerBlock;
		if (out < clear || out > clear + isletBand || coast <= 0.5 - Coastline.REACH) {
			return null;
		}
		// Off a rocky coast, on a stretch of coast with islets, in a cluster.
		double[] top = new double[1];
		float[] normal = new float[2];
		double rocky = rocky(i, j, coast, top, normal);
		if (rocky <= 0) {
			return null;
		}
		double mpb = scale.metersPerBlock();
		double stretch = GeoNoise.smoothstep(-0.1, 0.5,
			GeoNoise.fractal(isletSeed, px * mpb / ISLET_WAVELENGTH, pz * mpb / ISLET_WAVELENGTH, 2));
		double cluster = GeoNoise.smoothstep(-0.3, 0.4,
			GeoNoise.fractal(isletSeed + 2, px * mpb / CLUSTER_WAVELENGTH, pz * mpb / CLUSTER_WAVELENGTH, 2));
		if (candidate.chance() >= ISLET_SHARE * stretch * cluster * GeoNoise.smoothstep(0.3, 0.8, rocky)) {
			return null;
		}
		long height = GeoNoise.hash(isletSeed, cellX, 3, cellZ);
		double radius = candidate.radius();
		double peak = sea + Math.min(Math.min(lerp(GeoNoise.unit(height), isletHeightMin, isletHeightMax),
			ISLET_BELOW_LAND * (top[0] - sea)), Math.max(ISLET_STEEPNESS * radius, ISLET_HEIGHT_BLOCKS_MIN));
		if (peak < sea + 1.5) {
			return null;
		}
		// The coast runs across the normal, which points inland: along z, then x, in pixels.
		double n = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
		return new Islet(px, pz, -normal[0] / n, normal[1] / n, radius, candidate.length(), peak);
	}

	/**
	 * The place for an islet in a cell of the grid, from the cell's hashes alone; {@code null} for the cells without
	 * one.
	 */
	private Place place(int cellX, int cellZ) {
		long size = GeoNoise.hash(isletSeed, cellX, 0, cellZ);
		double chance = GeoNoise.unit(size);
		if (chance >= ISLET_SHARE) {
			return null;
		}
		long place = GeoNoise.hash(isletSeed, cellX, 1, cellZ), form = GeoNoise.hash(isletSeed, cellX, 2, cellZ);
		double radius = radius(GeoNoise.unit2(size));
		return new Place(cellX, cellZ, chance, (cellX + GeoNoise.unit(place)) * isletSpacing,
			(cellZ + GeoNoise.unit2(place)) * isletSpacing, radius, radius * lerp(GeoNoise.unit(form), 1, ISLET_ASPECT_MAX));
	}

	/**
	 * The ground at {@code (x, z)} with an islet on it, given the {@code ground} without it: a mound rising from the
	 * sea floor to a rounded top, steepest halfway up its sides, inside an outline drawn out along the coast that
	 * noise moves in and out.
	 */
	private double islet(Islet islet, double x, double z, double ground) {
		double dx = x - islet.x(), dz = z - islet.z();
		double along = dx * islet.tx() + dz * islet.tz(), across = dz * islet.tx() - dx * islet.tz();
		double wavelength = Math.max(islet.radius(), 3);
		double u = Math.hypot(along / islet.length(), across / islet.radius())
			+ ISLET_ROUGHNESS * GeoNoise.fractal(isletSeed + 1, x / wavelength, z / wavelength, 2);
		if (u >= 1) {
			return ground;
		}
		double rise = GeoNoise.smoothstep(0, ISLET_SIDES, 1 - Math.max(u, 0));
		return ground + Math.max(0, islet.top() - ground) * rise;
	}

	/** The radius of an islet in blocks, from the smallest at {@code h} 0 to the largest at 1. */
	private double radius(double h) {
		return Math.max(lerp(h, ISLET_RADIUS, ISLET_RADIUS_MAX) / scale.metersPerBlock(),
			lerp(h, ISLET_RADIUS_BLOCKS, ISLET_RADIUS_BLOCKS_MAX));
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	/**
	 * How much soil and plants cover the ground of a column, from 0 to 1: none in deserts or where the ground is frozen
	 * most of the year, full where it is humid and mild.
	 */
	static double cover(TerrainField.Column column) {
		return GeoNoise.smoothstep(0.5, 1.5, ClimateMapping.aridityRatio(column))
			* GeoNoise.smoothstep(-4, 4, column.temperature);
	}
}
