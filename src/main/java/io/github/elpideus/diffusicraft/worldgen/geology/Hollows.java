package io.github.elpideus.diffusicraft.worldgen.geology;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;

import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.fractal;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.gradient;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.gradient3;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.hash;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.mix;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.unit;
import static io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise.unit2;

/**
 * The Hollows: caverns far larger than vanilla's caves, larger the deeper they lie, in the spirit of <i>Journey to
 * the Center of the Earth</i>. From the top down:
 * <ul>
 *     <li><b>Hollows</b>, caverns a few dozen blocks across in the basement, above y=0;</li>
 *     <li><b>Deep Hollows</b>, larger, in the deepslate;</li>
 *     <li><b>Grand Hollows</b>, cavern worlds a few hundred blocks across with domed ceilings, flat floors, rock
 *     pillars and patches of light in the ceiling, and a basin sunk into the middle of the floor that holds a lake
 *     where the land above is wet enough;</li>
 *     <li><b>Ancient Grand Hollows</b>, rarer and larger still, deeper, with thicker pillars;</li>
 *     <li><b>Deep Grand Hollows</b> just above the magma layer, whose floors dip under the lava level into lava seas;</li>
 *     <li><b>deep noodle caverns</b>, winding tunnels below y=0 that run between them.</li>
 * </ul>
 * The deep tiers share out the room between the lava level and y=0, so in the tall worlds the Grand Hollows are a
 * hundred blocks high or more. Each cavern is an ellipsoid with a flattened floor, warped by noise, placed at random in
 * one cell of a grid per tier. All of it is a density, negative in the open, that the world's final density takes the
 * minimum with. Pure and thread-safe; each thread keeps its own {@link Column}.
 */
public final class Hollows {
	/** The density deep in solid rock, and the most open. */
	public static final float SOLID = 8f;
	public static final float OPEN = -1f;
	/**
	 * Rock always left between a hollow and the surface. The final density uses the hollows only from 25 blocks below
	 * the surface; see {@code tools/worldgen_data.py}.
	 */
	private static final int ROOF = 28;
	/** Chambers are skipped past this normalised distance, beyond where the warp could reach. */
	private static final double REACH = 1.35;
	/**
	 * Rock under the floor of a Grand Hollow and over its ceiling that still counts as part of it, in blocks, beyond
	 * the chamber's reach: see {@link #nearGrandHollow}.
	 */
	private static final int GRAND_FLOOR_MARGIN = 12;
	private static final int GRAND_CEILING_MARGIN = 8;
	/**
	 * The basin of a Grand Hollow sinks the middle of its floor by up to this share of the floor's depth under the
	 * centre, out to this squared normalised distance from it.
	 */
	private static final double BASIN_DEPTH = 0.8;
	private static final double BASIN_REACH2 = 0.36;
	/**
	 * A lake fills its basin up to this share of the floor's depth under the centre: just under the plain floor at the
	 * basin's rim, so that the rock around it holds the water.
	 */
	private static final double LAKE_LEVEL = 0.92;
	/** Rock under the floor of a basin that belongs to its lake, in blocks, beyond the chamber's reach. */
	private static final int LAKE_MARGIN = 3;
	private static final int MAX_CANDIDATES = 32;
	private static final int PILLAR_CELL = 22;
	/** Deep noodle caverns run where a regional noise is above this: in about two fifths of the world. */
	private static final double NOODLE_MASK = 0.05;

	private static final Map<Key, Hollows> CACHE = new ConcurrentHashMap<>();

	public enum Kind {
		HOLLOW, DEEP_HOLLOW, GRAND_HOLLOW, ANCIENT_GRAND_HOLLOW, DEEP_GRAND_HOLLOW, DEEP_NOODLE;

		/** Whether the ceiling of this kind of hollow has patches of light. */
		public boolean lit() {
			return this == GRAND_HOLLOW || this == ANCIENT_GRAND_HOLLOW;
		}

		/** Whether this is one of the Grand Hollows. */
		public boolean grand() {
			return this == GRAND_HOLLOW || this == ANCIENT_GRAND_HOLLOW || this == DEEP_GRAND_HOLLOW;
		}
	}

	private record Key(long seed, int minY) {
	}

	private record Tier(Kind kind, int cell, double chance, double minRadius, double maxRadius, double minHeight,
						double maxHeight, double minCenterY, double maxCenterY, double pillarScale, long salt) {
	}

	/**
	 * One cavern: its kind, a hash that tells it from every other, its centre, its radii in plan, how far it is turned,
	 * how far its ceiling rises over the centre and its floor sinks under it, and how much deeper its basin sinks the
	 * middle of the floor.
	 */
	public record Chamber(Kind kind, long id, double x, double y, double z, double rx, double rz, double cos,
						  double sin, double up, double down, double basin, double wallScale, double pillarScale) {
		/** How far the floor sinks under the centre at a squared normalised distance from it, basin and all. */
		double floor(double h2) {
			if (basin == 0) {
				return down;
			}
			double s = Math.max(0, 1 - h2 / BASIN_REACH2);
			return down + basin * s * s;
		}

		/** The lowest height a lake in the basin leaves dry: water fills the open blocks below it. */
		public int lakeLevel() {
			return (int) Math.floor(y - LAKE_LEVEL * down);
		}
	}

	/**
	 * The hollows near one column. The caller fills it with {@link #column} and then asks for densities along Y. Not
	 * thread-safe: keep one per thread.
	 */
	public static final class Column {
		private int x;
		private int z;
		private boolean prepared;
		private float surfaceY;
		private int count;
		private final Chamber[] chambers = new Chamber[MAX_CANDIDATES];
		private final double[] horizontal = new double[MAX_CANDIDATES];
		/** Top and bottom of everything that can be open in this column. */
		private double top;
		private double bottom;
		/** Top and bottom of the Grand Hollows in this column alone. */
		private double grandTop;
		private double grandBottom;
		private double pillarDistance;
		private double pillarRadius;
		private double noodleMask;
		/** Whether the basin of a Grand Hollow lies under this column. */
		private boolean basins;
		private Kind kind;
		private double value;

		/** Whether this column was last prepared for (x, z). */
		public boolean isAt(int x, int z) {
			return prepared && this.x == x && this.z == z;
		}

		/** Whether nothing in this column can be open, so every density is {@link #SOLID}. */
		public boolean solid() {
			return top < bottom;
		}

		public double top() {
			return top;
		}

		public double bottom() {
			return bottom;
		}

		/** The highest block a Grand Hollow can open in this column; below {@link #grandBottom()} if none comes near. */
		public int grandTop() {
			return (int) Math.floor(Math.min(grandTop, top));
		}

		public int grandBottom() {
			return (int) Math.ceil(Math.max(grandBottom, bottom));
		}
	}

	private final long seed;
	private final int floorLimit;
	private final int noodleTop;
	private final int highestLake;
	private final Tier[] tiers;

	/**
	 * @param seed the hollows seed of the world
	 * @param minY bottom of the world
	 */
	public Hollows(long seed, int minY) {
		this.seed = seed;
		int lava = minY + 10;
		double room = -lava;
		// Above the bedrock, with up to four blocks of lava over the floor of the deepest hollows.
		floorLimit = minY + 6;
		noodleTop = -4;
		tiers = new Tier[] {
			new Tier(Kind.HOLLOW, 112, 0.32, 14, 30, 10, 22, 6, 6 + Math.max(30, 0.45 * room), 0, 1),
			new Tier(Kind.DEEP_HOLLOW, 144, 0.32, 20, 42, 14, 28, lava + 0.5 * room, -8, 0, 2),
			new Tier(Kind.GRAND_HOLLOW, 448, 0.18, 70, 160, Math.max(22, 0.35 * room), Math.max(30, 0.55 * room),
				lava + 0.38 * room, lava + 0.6 * room, 1, 3),
			new Tier(Kind.ANCIENT_GRAND_HOLLOW, 896, 0.12, 150, 320, Math.max(26, 0.45 * room),
				Math.max(36, 0.7 * room), lava + 0.3 * room, lava + 0.45 * room, 1.6, 4),
			new Tier(Kind.DEEP_GRAND_HOLLOW, 640, 0.14, 100, 230, Math.max(18, 0.3 * room), Math.max(26, 0.45 * room),
				lava + 0.06 * room, lava + 0.14 * room, 0, 5),
		};
		double lakes = Double.NEGATIVE_INFINITY;
		for (Tier tier : tiers) {
			if (hasBasins(tier.kind)) {
				lakes = Math.max(lakes, tier.maxCenterY);
			}
		}
		highestLake = (int) Math.ceil(lakes);
	}

	/** Whether chambers of a kind have basins: the Grand Hollows, but not the deep ones, which hold lava seas instead. */
	private static boolean hasBasins(Kind kind) {
		return kind == Kind.GRAND_HOLLOW || kind == Kind.ANCIENT_GRAND_HOLLOW;
	}

	/** A height above every lake: {@link #lakeAt} finds none from here up. */
	public int highestLake() {
		return highestLake;
	}

	/** The shared instance for a world. */
	public static Hollows of(long seed, int minY) {
		return CACHE.computeIfAbsent(new Key(seed, minY), k -> new Hollows(k.seed, k.minY));
	}

	public static void clearCache() {
		CACHE.clear();
	}

	/**
	 * Prepares a column. {@code surfaceY} is the terrain surface; hollows stay {@value #ROOF} blocks below it. It is
	 * asked for only if a hollow comes near the column, as computing it can be costly.
	 */
	public void column(int x, int z, IntBinaryOperator surfaceY, Column c) {
		c.x = x;
		c.z = z;
		c.prepared = true;
		c.count = 0;
		c.top = Double.NEGATIVE_INFINITY;
		c.bottom = Double.POSITIVE_INFINITY;
		c.grandTop = Double.NEGATIVE_INFINITY;
		c.grandBottom = Double.POSITIVE_INFINITY;
		c.pillarDistance = Double.POSITIVE_INFINITY;
		c.pillarRadius = 0;
		c.basins = false;
		boolean pillars = false;
		for (Tier tier : tiers) {
			int cx = Math.floorDiv(x, tier.cell);
			int cz = Math.floorDiv(z, tier.cell);
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					Chamber chamber = chamber(tier, cx + dx, cz + dz);
					if (chamber == null || c.count == MAX_CANDIDATES) {
						continue;
					}
					double ox = x + 0.5 - chamber.x;
					double oz = z + 0.5 - chamber.z;
					double u = (ox * chamber.cos + oz * chamber.sin) / chamber.rx;
					double v = (oz * chamber.cos - ox * chamber.sin) / chamber.rz;
					if (u * u + v * v >= 2 * REACH * REACH) {
						continue;
					}
					// Lobes and bays a few times smaller than the chamber, so that no two outlines are alike.
					double lobes = 0.25 * gradient(chamber.id, ox / (chamber.rx * 0.4), oz / (chamber.rx * 0.4));
					double h = Math.max(0, Math.sqrt(u * u + v * v) + lobes);
					double h2 = h * h;
					if (h2 >= REACH * REACH) {
						continue;
					}
					c.chambers[c.count] = chamber;
					c.horizontal[c.count++] = h2;
					c.top = Math.max(c.top, chamber.y + chamber.up * REACH);
					c.bottom = Math.min(c.bottom, chamber.y - (chamber.down + chamber.basin) * REACH);
					if (chamber.kind.grand()) {
						c.grandTop = Math.max(c.grandTop, chamber.y + chamber.up * REACH);
						c.grandBottom = Math.min(c.grandBottom, chamber.y - (chamber.down + chamber.basin) * REACH);
					}
					pillars |= chamber.pillarScale > 0;
					c.basins |= chamber.basin > 0 && h2 < 1;
				}
			}
		}
		c.noodleMask = fractal(seed + 40, x / 600.0, z / 600.0, 2);
		if (c.noodleMask > NOODLE_MASK) {
			c.top = Math.max(c.top, noodleTop + 8);
			c.bottom = Math.min(c.bottom, floorLimit);
		}
		if (c.top >= c.bottom) {
			c.surfaceY = surfaceY.applyAsInt(x, z);
			c.top = Math.min(c.top, c.surfaceY - ROOF + 4);
			c.bottom = Math.max(c.bottom, floorLimit - 4);
		}
		if (pillars) {
			pillar(x, z, c);
		}
	}

	/** The density at a height of a prepared column: negative in the open, {@link #SOLID} far from any hollow. */
	public float density(Column c, int y) {
		if (y > c.top || y < c.bottom) {
			c.kind = null;
			c.value = SOLID;
			return SOLID;
		}
		double value = SOLID;
		Kind kind = null;
		double warp = Double.NaN;
		// No other hollow opens into a lake, or it would drain: the basin and the rock under it stay its own.
		Chamber lake = lakeAt(c, y);
		for (int i = 0; i < c.count; i++) {
			Chamber chamber = c.chambers[i];
			if (lake != null && chamber != lake) {
				continue;
			}
			double dy = y + 0.5 - chamber.y;
			double v = dy >= 0 ? dy / chamber.up : -dy / chamber.floor(c.horizontal[i]);
			if (v >= REACH) {
				continue;
			}
			// A domed ceiling over a flat floor, and the basin in the middle of it.
			double v2 = v * v;
			double d2 = c.horizontal[i] + (dy >= 0 ? v2 : v2 * v2);
			if (d2 >= REACH * REACH) {
				continue;
			}
			if (Double.isNaN(warp)) {
				warp = warp(c, y);
			}
			double density = (Math.sqrt(d2) - 1 + warp) * chamber.wallScale;
			if (chamber.pillarScale > 0 && c.pillarRadius > 0) {
				// Pillars flare where they meet the floor and the ceiling.
				double radius = c.pillarRadius * chamber.pillarScale * (0.55 + 0.75 * Math.min(1, v2));
				density = Math.max(density, (radius - c.pillarDistance) * 0.6);
			}
			if (density < value) {
				value = density;
				kind = chamber.kind;
			}
		}
		if (lake == null && c.noodleMask > NOODLE_MASK && y <= noodleTop + 8) {
			double noodle = noodle(c, y);
			if (noodle < value) {
				value = noodle;
				kind = Kind.DEEP_NOODLE;
			}
		}
		if (value < SOLID) {
			value = Math.max(value, (y - (c.surfaceY - ROOF)) * 0.25);
			value = Math.max(value, (floorLimit - y) * 0.5);
		}
		c.kind = kind;
		c.value = value;
		return (float) Math.max(OPEN, Math.min(SOLID, value));
	}

	/** The kind of hollow open at a height of a prepared column, or {@code null} if it is rock there. */
	public Kind openKind(Column c, int y) {
		return kindNear(c, y, 0);
	}

	/**
	 * The kind of hollow whose density at a height is below {@code margin}, or {@code null}. The world interpolates
	 * the density between samples a few blocks apart, so the cave it carves differs from the exact density by a block
	 * or two; a small margin finds the hollow from a block of its wall.
	 */
	public Kind kindNear(Column c, int y, double margin) {
		density(c, y);
		return c.value < margin ? c.kind : null;
	}

	/**
	 * Whether a height of a prepared column lies in a Grand Hollow or in the rock around it: within the chamber's
	 * reach, which takes in a few dozen blocks of its walls, and {@value #GRAND_FLOOR_MARGIN} blocks more under its
	 * floor and {@value #GRAND_CEILING_MARGIN} over its ceiling, where the reach is only a few blocks thick. Unlike
	 * {@link #kindNear}, it counts the hollow's pillars as part of it, and a smaller hollow or a noodle cavern cutting
	 * into it does not hide it.
	 */
	public boolean nearGrandHollow(Column c, int y) {
		return grandHollowNear(c, y) != null;
	}

	/**
	 * The Grand Hollow that {@link #nearGrandHollow} finds at a height of a prepared column, or {@code null}. Where two
	 * reach that far, the one the height lies deeper in.
	 */
	public @Nullable Chamber grandHollowNear(Column c, int y) {
		if (y > c.top + GRAND_CEILING_MARGIN || y < c.bottom - GRAND_FLOOR_MARGIN) {
			return null;
		}
		Chamber nearest = null;
		double best = REACH * REACH;
		for (int i = 0; i < c.count; i++) {
			Chamber chamber = c.chambers[i];
			if (!chamber.kind.grand()) {
				continue;
			}
			// Measured as if the floor and the ceiling were moved out by the margins.
			double dy = y + 0.5 - chamber.y;
			double v = dy >= 0 ? Math.max(0, dy - GRAND_CEILING_MARGIN) / chamber.up
				: Math.max(0, -dy - GRAND_FLOOR_MARGIN) / chamber.floor(c.horizontal[i]);
			double v2 = v * v;
			double d2 = c.horizontal[i] + (dy >= 0 ? v2 : v2 * v2);
			if (d2 < best) {
				best = d2;
				nearest = chamber;
			}
		}
		return nearest;
	}

	/**
	 * The Grand Hollow whose lake takes in a height of a prepared column, or {@code null}: one whose basin lies under
	 * the column, below its {@link Chamber#lakeLevel()}, and within its outline and {@value #LAKE_MARGIN} blocks of
	 * rock under its floor. No other hollow opens there, so the basin holds its water, and every open block there
	 * belongs to the lake. Whether the lake holds water is up to the climate over it.
	 */
	public @Nullable Chamber lakeAt(Column c, int y) {
		if (!c.basins || y >= highestLake || y < c.bottom - LAKE_MARGIN) {
			return null;
		}
		for (int i = 0; i < c.count; i++) {
			Chamber chamber = c.chambers[i];
			double h2 = c.horizontal[i];
			if (chamber.basin <= 0 || h2 >= 1 || y >= chamber.lakeLevel()) {
				continue;
			}
			double v = Math.max(0, chamber.y - (y + 0.5) - LAKE_MARGIN) / chamber.floor(h2);
			double v2 = v * v;
			if (h2 + v2 * v2 < REACH * REACH) {
				return chamber;
			}
		}
		return null;
	}

	/** Whether the ceiling of a lit hollow glows at this column: patches of light about a tenth of the ceiling. */
	public boolean glows(int x, int z) {
		return gradient(seed + 50, x / 7.0, z / 7.0) > 0.5;
	}

	/** Noise that roughens the walls of every chamber. */
	private double warp(Column c, int y) {
		return 0.12 * gradient3(seed + 30, c.x / 24.0, y / 12.0, c.z / 24.0)
			+ 0.04 * gradient3(seed + 31, c.x / 8.0, y / 5.0, c.z / 8.0);
	}

	private double noodle(Column c, int y) {
		// Tunnels run where two independent noises are both near zero, as vanilla's noodle caves do. The noises change
		// faster with height, which lays their zero sheets down, so the tunnels mostly wind sideways.
		double n1 = Math.abs(gradient3(seed + 41, c.x / 72.0, y / 44.0, c.z / 72.0)) / 0.07;
		double n2 = Math.abs(gradient3(seed + 42, c.x / 72.0, y / 44.0, c.z / 72.0)) / 0.07;
		double value = (Math.max(n1, n2) - 1) * 2.5;
		value += Math.max(0, NOODLE_MASK + 0.05 - c.noodleMask) * 40;
		value += Math.max(0, y - (noodleTop - 8)) * 0.3;
		value += Math.max(0, floorLimit + 8 - y) * 0.3;
		return value;
	}

	private Chamber chamber(Tier tier, int cx, int cz) {
		long h = hash(seed + tier.salt * 0x51ED27L, cx, 0, cz);
		if (unit(h) >= tier.chance) {
			return null;
		}
		long h2 = mix(h + 1);
		double radius = tier.minRadius + (tier.maxRadius - tier.minRadius) * unit2(h);
		double x = cx * tier.cell + tier.cell * unit(h2);
		double z = cz * tier.cell + tier.cell * unit2(h2);
		long h3 = mix(h2 + 1);
		double y = tier.minCenterY + (tier.maxCenterY - tier.minCenterY) * unit(h3);
		double height = tier.minHeight + (tier.maxHeight - tier.minHeight) * unit2(h3);
		long h4 = mix(h3 + 1);
		double angle = Math.PI * unit(h4);
		double rz = radius * (0.55 + 0.45 * unit2(h4));
		double up = height * 0.65;
		// The deepest hollows reach under the floor limit, which cuts them flat well under the lava level: lava seas.
		// The world interpolates the density over eight blocks of height and fades it to solid towards the bottom,
		// which would lift a floor that only just dips under the lava out of it again.
		double down = tier.kind == Kind.DEEP_GRAND_HOLLOW ? y - (floorLimit - 4)
			: Math.max(2, Math.min(height * 0.35, y - floorLimit));
		// The basin keeps two blocks over the lava level, which is four over the floor limit.
		double basin = hasBasins(tier.kind) ? Math.max(0, Math.min(BASIN_DEPTH * down, y - down - (floorLimit + 6)))
			: 0;
		// Densities fall by about a quarter per block near the walls, like vanilla's caves.
		double wallScale = Math.min(Math.min(radius, rz), up + down) / 4;
		return new Chamber(tier.kind, h4, x, y, z, radius, rz, Math.cos(angle), Math.sin(angle), up, down, basin,
			wallScale, tier.pillarScale);
	}

	private void pillar(int x, int z, Column c) {
		int cx = Math.floorDiv(x, PILLAR_CELL);
		int cz = Math.floorDiv(z, PILLAR_CELL);
		double best = Double.POSITIVE_INFINITY;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				long h = hash(seed + 60, cx + dx, 0, cz + dz);
				if (unit(h) >= 0.3) {
					continue;
				}
				long h2 = mix(h + 1);
				double px = (cx + dx + 0.15 + 0.7 * unit2(h)) * PILLAR_CELL;
				double pz = (cz + dz + 0.15 + 0.7 * unit(h2)) * PILLAR_CELL;
				double radius = 2.5 + 3.5 * unit2(h2);
				double distance = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
				if (distance / radius < best) {
					best = distance / radius;
					c.pillarDistance = distance;
					c.pillarRadius = radius;
				}
			}
		}
	}
}
