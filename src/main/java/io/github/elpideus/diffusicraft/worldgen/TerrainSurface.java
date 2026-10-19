package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Gullies;

import java.util.ArrayList;
import java.util.List;

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
 * <p>The terrain's seas are flats near the shore, a metre deep or less for hundreds of meters, where real ones deepen
 * along a beach's equilibrium profile. Out to sea, the floor lies at least as deep as that profile reaches, up to
 * {@link TerrainField#COAST_REACH} from the coastline and as deep as it reaches there beyond it: 5 m. Only the
 * Earth 1:1 world shows it; the others keep the shallowest sea a block deep.
 *
 * <p>Off the cliffs stand sea stacks, the remains of headlands the sea has cut off: columns with sheer sides and a
 * gently domed top, 6 to 25 m in radius and from half as high as the cliff behind them to as high, past at least
 * 20 m of open water and within 150 m beyond it. The places for them lie on a jittered grid 150 m apart, and hashes
 * keep them from touching; noise along the coast picks some, more on some stretches of cliffs than on others. One in
 * five is an arch, two to three times longer along the coast than wide, with a rounded opening that the sea flows
 * through ({@code hole} in {@link #cliff(int, int, float[])}). The density takes stacks block by block, as it does
 * cliffs. The smaller worlds keep them at least 3 to 5 blocks wide, 2 blocks of water from the coast.
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
	/**
	 * The shape of the sea floor off a beach, in m^(1/3): the depth {@code d} meters out to sea is {@code A d^(2/3)}
	 * (Dean's equilibrium profile, for fine sand).
	 */
	private static final double SHOREFACE = 0.1;
	/** Real spacing of the places out to sea that can hold a stack, in meters, and the least in blocks. */
	private static final double STACK_SPACING = 150;
	private static final double STACK_SPACING_BLOCKS = 16;
	/** Real radius of a stack, in meters, and in blocks at least, from the thinnest to the stoutest. */
	private static final double STACK_RADIUS = 6;
	private static final double STACK_RADIUS_MAX = 25;
	private static final double STACK_RADIUS_BLOCKS = 1.5;
	private static final double STACK_RADIUS_BLOCKS_MAX = 2.5;
	/** Real open water between a stack and the coastline, in meters, and the least in blocks. */
	private static final double STACK_GAP = 20;
	private static final double STACK_GAP_BLOCKS = 2;
	/** Real width of the band beyond that water where stacks stand, in meters, and the least in blocks. */
	private static final double STACK_BAND = 150;
	private static final double STACK_BAND_BLOCKS = 6;
	/** The share of the places in the band that hold a stack, off the stretches of cliffs richest in them. */
	private static final double STACK_SHARE = 0.6;
	/** Real wavelength of the noise that picks those stretches, in meters. */
	private static final double STACK_WAVELENGTH = 5000;
	/** How far noise moves the outline of a stack in or out, as a share of its radius. */
	private static final double STACK_ROUGHNESS = 0.2;
	/** The share of stacks that are arches, and how many times longer than wide those are. */
	private static final double ARCH_SHARE = 0.2;
	private static final double ARCH_ASPECT = 2.2;
	private static final double ARCH_ASPECT_MAX = 3;
	/** Least height of an arch's opening above the sea, and of the rock over it, in blocks. */
	private static final double ARCH_OPENING_BLOCKS = 3;
	private static final double ARCH_ROOF_BLOCKS = 2;

	/**
	 * A sea stack or an arch, in blocks: its centre, the direction of the coast there, its radius and its half-length
	 * along the coast, the height of its top, and for an arch the height of the opening above the sea and its
	 * half-width along the coast (0 for a stack).
	 */
	private record Stack(double x, double z, double tx, double tz, double radius, double length, double top,
						 double opening, double span) {
	}

	/**
	 * A place in a cell of the grid that can hold a stack, from the cell's hashes: the share it is drawn at, its centre,
	 * its radius and its half-length along the coast, longer than the radius where it would be an arch.
	 */
	private record Place(int cellX, int cellZ, double chance, double x, double z, double radius, double length) {
		/** The farthest the outline reaches from the centre. */
		double extent() {
			return length * (1 + STACK_ROUGHNESS);
		}
	}

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
	/** Whether the profile of the sea floor off the shore lies deeper anywhere than the shallowest sea. */
	private final boolean shoreface;
	private final int sea;
	private final long stackSeed;
	/** The stacks' spacing, open water, band and the farthest any reaches from its centre, in blocks. */
	private final double stackSpacing;
	private final double stackGap;
	private final double stackBand;
	private final double stackExtent;
	/** Farthest out to sea, in pixels, that a stack or the margin around it reaches. */
	private final double stackReach;

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
		this.sea = scale.seaLevel();
		this.lowCliff = Math.max(scale.surfaceY(LOW_CLIFF) - sea, LOW_CLIFF_BLOCKS);
		this.highCliff = Math.max(scale.surfaceY(HIGH_CLIFF) - sea, HIGH_CLIFF_BLOCKS);
		this.shelf = sea + 0.5;
		this.shoreface = shoreface(-Coastline.REACH) < sea - 1;
		double mpb = scale.metersPerBlock();
		this.stackSeed = field.seed() ^ 0x57AC4A2C11L;
		this.stackSpacing = Math.max(STACK_SPACING / mpb, STACK_SPACING_BLOCKS);
		this.stackGap = Math.max(STACK_GAP / mpb, STACK_GAP_BLOCKS);
		this.stackBand = Math.max(STACK_BAND / mpb, STACK_BAND_BLOCKS);
		this.stackExtent = radius(1) * ARCH_ASPECT_MAX * (1 + STACK_ROUGHNESS);
		// The coastline's distance reads no farther than its reach: a stack stays inside it, margin and all.
		double farthest = stackGap + radius(1) * (1 + STACK_ROUGHNESS) + stackBand + stackExtent + CLIFF_MARGIN;
		this.stackReach = Math.min(farthest * pixelsPerBlock, Coastline.REACH - 1);
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
		return y(x, z, null);
	}

	/** The ground at a point, with the opening of an arch through it in {@code hole}, if one is given. */
	private double y(double x, double z, float[] hole) {
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
		return Math.clamp(shore(x, z, i, j, elevation, y, hole), lowest, highest);
	}

	/**
	 * The ground at the centre of column {@code (x, z)} for the levels of detail too coarse for the gullies and the
	 * ledges: the terrain's height with only the shapes of the shore. The arches are solid there.
	 */
	public float shore(int x, int z) {
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float elevation = field.elevation(i, j);
		return (float) Math.clamp(shore(cx, cz, i, j, elevation, scale.surfaceY(elevation), null), lowest, highest);
	}

	/**
	 * The ground {@code y} at a point of the given elevation, with the sea cliffs, the profile of the sea floor and the
	 * stacks, and the opening of an arch in {@code hole} if one is given.
	 */
	private double shore(double x, double z, double i, double j, float elevation, double y, float[] hole) {
		if (hole != null) {
			hole[0] = hole[1] = Float.NaN;
		}
		float coast = field.coast(i, j);
		if (coast > 0f && coast < zone) {
			y = sharpen(x, z, i, j, coast, y);
		} else if (coast < 0f && elevation < 0f && shoreface) {
			y = Math.min(y, shoreface(coast));
		}
		if (coast > -stackReach - 0.5 && coast < zone) {
			for (Stack stack : stacks(x, z, 0)) {
				y = stack(stack, x, z, y, hole);
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
	 * out to sea within {@link #CLIFF_MARGIN} blocks of the coastline, and on a stack and within that margin of it;
	 * NaN elsewhere.
	 */
	public float cliff(int x, int z) {
		return cliff(x, z, null);
	}

	/**
	 * {@link #cliff(int, int)}, with the opening of an arch through the column in {@code hole}: water or air from
	 * {@code hole[0]} up to {@code hole[1]}, both NaN where there is none.
	 */
	public float cliff(int x, int z, float[] hole) {
		if (hole != null) {
			hole[0] = hole[1] = Float.NaN;
		}
		double cx = x + 0.5, cz = z + 0.5, i = cz * pixelsPerBlock, j = cx * pixelsPerBlock;
		float coast = field.coast(i, j);
		boolean cliff = coast > -margin && coast < zone
			&& strength(cx, cz, i, j, coast, new double[1], new float[2]) > 0;
		if (!cliff && !(coast > -stackReach - 0.5 && coast < zone && !stacks(cx, cz, CLIFF_MARGIN).isEmpty())) {
			return Float.NaN;
		}
		return (float) y(cx, cz, hole);
	}

	/**
	 * Gathers the rise of the ground across the strip of cliffs into a face, at a point {@code coast} pixels inland.
	 */
	private double sharpen(double x, double z, double i, double j, float coast, double y) {
		double[] top = new double[1];
		double k = strength(x, z, i, j, coast, top, new float[2]);
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
	 * top in {@code top[0]}: the smooth ground inland along the coast's normal, where the strip ends. The normal, in
	 * pixels along {@code i} and {@code j}, goes into {@code normal}.
	 */
	private double strength(double x, double z, double i, double j, float coast, double[] top, float[] normal) {
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

	/** The stacks whose outline comes within {@code reach} blocks of {@code (x, z)}: seldom more than one. */
	private List<Stack> stacks(double x, double z, double reach) {
		List<Stack> found = List.of();
		double far = stackExtent + reach;
		int x0 = (int) Math.floor((x - far) / stackSpacing), x1 = (int) Math.floor((x + far) / stackSpacing);
		int z0 = (int) Math.floor((z - far) / stackSpacing), z1 = (int) Math.floor((z + far) / stackSpacing);
		for (int cellZ = z0; cellZ <= z1; cellZ++) {
			for (int cellX = x0; cellX <= x1; cellX++) {
				Stack stack = stack(cellX, cellZ, x, z, reach);
				if (stack != null) {
					if (found.isEmpty()) {
						found = new ArrayList<>(2);
					}
					found.add(stack);
				}
			}
		}
		return found;
	}

	/**
	 * The stack of a cell of the grid of places that can hold one, if it has one whose outline comes within
	 * {@code reach} blocks of {@code (x, z)}; {@code null} otherwise. The hashes rule out most places before the
	 * terrain is read.
	 */
	private Stack stack(int cellX, int cellZ, double x, double z, double reach) {
		Place candidate = place(cellX, cellZ);
		if (candidate == null) {
			return null;
		}
		double px = candidate.x(), pz = candidate.z(), radius = candidate.radius(), length = candidate.length();
		double extent = candidate.extent();
		double dx = x - px, dz = z - pz, near = extent + reach;
		if (dx * dx + dz * dz > near * near) {
			return null;
		}
		// Out to sea past open water, within the band, and with the margin around it inside the coastline's reach.
		double i = pz * pixelsPerBlock, j = px * pixelsPerBlock;
		float coast = field.coast(i, j);
		double clear = stackGap + radius * (1 + STACK_ROUGHNESS), out = -coast / pixelsPerBlock;
		if (out < clear || out > clear + stackBand || -coast + (extent + CLIFF_MARGIN) * pixelsPerBlock > stackReach) {
			return null;
		}
		// Off a cliff, on a stretch of coast with stacks.
		double[] top = new double[1];
		float[] normal = new float[2];
		double cliff = strength(px, pz, i, j, coast, top, normal);
		double mpb = scale.metersPerBlock();
		double stretch = GeoNoise.smoothstep(-0.3, 0.3,
			GeoNoise.fractal(stackSeed, px * mpb / STACK_WAVELENGTH, pz * mpb / STACK_WAVELENGTH, 2));
		if (candidate.chance() >= STACK_SHARE * stretch * GeoNoise.smoothstep(0.3, 0.8, cliff) || !apart(candidate)) {
			return null;
		}
		long height = GeoNoise.hash(stackSeed, cellX, 3, cellZ);
		double peak = sea + (top[0] - sea) * lerp(GeoNoise.unit(height), 0.55, 1);
		if (peak < sea + 2) {
			return null;
		}
		// The coast runs across the normal, which points inland: along z, then x, in pixels.
		double n = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
		double tx = -normal[0] / n, tz = normal[1] / n;
		double opening = 0, span = 0;
		if (length > radius) {
			opening = (peak - sea) * lerp(GeoNoise.unit2(height), 0.4, 0.6);
			// Both ends of an arch stand in the sea too, where the coast curves.
			boolean open = opening >= ARCH_OPENING_BLOCKS && peak - sea - opening >= ARCH_ROOF_BLOCKS + dome(radius, peak)
				&& field.coast(i + tz * extent * pixelsPerBlock, j + tx * extent * pixelsPerBlock) < -stackGap * pixelsPerBlock
				&& field.coast(i - tz * extent * pixelsPerBlock, j - tx * extent * pixelsPerBlock) < -stackGap * pixelsPerBlock;
			if (open) {
				span = 0.5 * length;
			} else {
				opening = 0;
				length = radius;
			}
		}
		return new Stack(px, pz, tx, tz, radius, length, peak, opening, span);
	}

	/**
	 * The place for a stack in a cell of the grid, from the cell's hashes alone; {@code null} for the cells without
	 * one.
	 */
	private Place place(int cellX, int cellZ) {
		long size = GeoNoise.hash(stackSeed, cellX, 0, cellZ);
		double chance = GeoNoise.unit(size);
		if (chance >= STACK_SHARE) {
			return null;
		}
		long place = GeoNoise.hash(stackSeed, cellX, 1, cellZ), form = GeoNoise.hash(stackSeed, cellX, 2, cellZ);
		double radius = radius(GeoNoise.unit2(size));
		double length = GeoNoise.unit(form) < ARCH_SHARE
			? radius * lerp(GeoNoise.unit2(form), ARCH_ASPECT, ARCH_ASPECT_MAX)
			: radius;
		return new Place(cellX, cellZ, chance, (cellX + GeoNoise.unit(place)) * stackSpacing,
			(cellZ + GeoNoise.unit2(place)) * stackSpacing, radius, length);
	}

	/**
	 * Whether a place stands apart from the places around it that come before it, those with a lower chance: stacks
	 * never touch, so the sea flows through every arch. This reads only hashes.
	 */
	private boolean apart(Place place) {
		double far = place.extent() + stackExtent + 1;
		int x0 = (int) Math.floor((place.x() - far) / stackSpacing), x1 = (int) Math.floor((place.x() + far) / stackSpacing);
		int z0 = (int) Math.floor((place.z() - far) / stackSpacing), z1 = (int) Math.floor((place.z() + far) / stackSpacing);
		for (int cellZ = z0; cellZ <= z1; cellZ++) {
			for (int cellX = x0; cellX <= x1; cellX++) {
				Place other = cellX == place.cellX() && cellZ == place.cellZ() ? null : place(cellX, cellZ);
				if (other == null || other.chance() > place.chance()) {
					continue;
				}
				double apart = place.extent() + other.extent() + 1;
				double dx = other.x() - place.x(), dz = other.z() - place.z();
				if (dx * dx + dz * dz < apart * apart) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The ground at {@code (x, z)} with a stack on it, given the {@code ground} without it; the opening of an arch goes
	 * into {@code hole}, from the sea floor up, if one is given. The sides are sheer, the top a gentle dome.
	 */
	private double stack(Stack stack, double x, double z, double ground, float[] hole) {
		double dx = x - stack.x(), dz = z - stack.z();
		double along = dx * stack.tx() + dz * stack.tz(), across = dz * stack.tx() - dx * stack.tz();
		// An arch's legs are thin: its outline wanders half as much.
		double rough = stack.opening() > 0 ? STACK_ROUGHNESS / 2 : STACK_ROUGHNESS;
		double wavelength = Math.max(stack.radius(), 2);
		double u = Math.hypot(along / stack.length(), across / stack.radius())
			+ rough * GeoNoise.fractal(stackSeed + 1, x / wavelength, z / wavelength, 2);
		if (u >= 1) {
			return ground;
		}
		u = Math.max(u, 0);
		double top = stack.top() - dome(stack.radius(), stack.top()) * u * u;
		if (hole != null && Math.abs(along) < stack.span()) {
			double t = along / stack.span();
			double roof = sea + stack.opening() * Math.sqrt(1 - t * t);
			if (roof > ground) {
				hole[0] = (float) ground;
				hole[1] = (float) roof;
			}
		}
		return Math.max(ground, top);
	}

	/** How far the top of a stack falls from its middle to its edge, in blocks. */
	private double dome(double radius, double top) {
		return Math.min(0.25 * radius, 0.1 * (top - sea));
	}

	/** The radius of a stack in blocks, from the thinnest at {@code h} 0 to the stoutest at 1. */
	private double radius(double h) {
		return Math.max(lerp(h, STACK_RADIUS, STACK_RADIUS_MAX) / scale.metersPerBlock(),
			lerp(h, STACK_RADIUS_BLOCKS, STACK_RADIUS_BLOCKS_MAX));
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
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
