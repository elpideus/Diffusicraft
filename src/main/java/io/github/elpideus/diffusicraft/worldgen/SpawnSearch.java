package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the column of the world spawn near the origin, where the geography put the kind of place the seed drew
 * ({@link SpawnKind}). The geography maps the world to the kilometre; the coarse model draws on it at 7.7 km a pixel
 * and the full model fills in the rest, so the shore or the summit lies a little away from where the map has it. The
 * search reads the coarse map within {@value #REACH} coarse pixels of the origin's, then the full terrain of the few
 * coarse pixels it picks:
 * <ul>
 *     <li><b>coasts and islands</b>: the shore nearest the origin, on dry land within {@value #SHORE} native pixels of
 *     the water, a beach where there is one;</li>
 *     <li><b>peaks</b>: the highest point around, followed across coarse pixels until nothing nearby is higher;</li>
 *     <li><b>land inland</b>: the origin, or a dry and gentler coarse pixel near it.</li>
 * </ul>
 * The Ancient Grand Hollows lie under the land, and {@link WorldSpawn} finds them. Everything here is a function of the
 * terrain, so a world always gets the same spawn.
 */
public final class SpawnSearch {
	/** Coarse pixels around the origin's that the search reads, some 46 km either way. */
	static final int REACH = 6;
	/** Native pixels from the water within which dry land is the shore (180 m). */
	static final float SHORE = 6;
	/** The most coarse pixels whose full terrain the search for a shore or a summit reads. */
	static final int MAX_AREAS = 6;
	/** A beach stands at most this many blocks over the sea, and a shore at most {@value #CLIFF}. */
	private static final float BEACH = 4;
	private static final float CLIFF = 40;
	/** Coarse pixels inland are judged by how far they lie and how rough they are: a pixel per this much relief. */
	private static final float RELIEF_PER_PIXEL = 100;
	/** A summit is at least this high, in meters; lower ground has no mountain to climb. */
	private static final float MOUNTAIN = 500;
	private static final int STEP = 2;

	/** A block column. */
	public record Target(int x, int z) {
	}

	private SpawnSearch() {
	}

	/** The column for a spawn of the given kind. Hollows fall back to the land over them. */
	public static Target find(TerrainField field, WorldScale scale, SpawnKind kind) {
		Target target = switch (kind) {
			case COAST, ISLAND -> shore(field, scale);
			case PEAK -> summit(field, scale);
			case INLAND, HOLLOW -> null;
		};
		return target != null ? target : inland(field, scale);
	}

	/**
	 * The origin if it lies on land, or the coarse pixel nearby that is dry and gentlest for how far it lies; the
	 * origin itself if none is dry.
	 */
	static Target inland(TerrainField field, WorldScale scale) {
		Grid grid = new Grid(field, scale);
		TerrainField.Column column = new TerrainField.Column();
		int bestI = 0, bestJ = 0;
		double best = Double.POSITIVE_INFINITY;
		for (int di = -REACH; di <= REACH; di++) {
			for (int dj = -REACH; dj <= REACH; dj++) {
				field.coarseColumn(grid.centre(grid.ci + di), grid.centre(grid.cj + dj), column);
				if (column.elevation < 20f) {
					continue;
				}
				double score = Math.sqrt(di * di + dj * dj) + column.relief / RELIEF_PER_PIXEL;
				if (score < best) {
					best = score;
					bestI = di;
					bestJ = dj;
				}
			}
		}
		if (best == Double.POSITIVE_INFINITY || bestI == 0 && bestJ == 0) {
			return new Target(0, 0);
		}
		return grid.target(grid.centre(grid.ci + bestI), grid.centre(grid.cj + bestJ));
	}

	/**
	 * The shore nearest the origin: dry land within {@value #SHORE} pixels of the sea, at most {@value #BEACH} blocks
	 * over it on a beach, or {@value #CLIFF} on a rocky coast if the nearest coast has no beach. It looks along the
	 * coast of the coarse map, nearest first: each coarse pixel on land beside one at sea, and that one.
	 */
	static Target shore(TerrainField field, WorldScale scale) {
		Grid grid = new Grid(field, scale);
		List<int[]> coasts = new ArrayList<>();
		for (int di = -REACH; di <= REACH; di++) {
			for (int dj = -REACH; dj <= REACH; dj++) {
				int ci = grid.ci + di, cj = grid.cj + dj;
				if (grid.land(ci, cj) && (!grid.land(ci - 1, cj) || !grid.land(ci + 1, cj) || !grid.land(ci, cj - 1)
					|| !grid.land(ci, cj + 1))) {
					coasts.add(new int[]{ci, cj, di * di + dj * dj});
				}
			}
		}
		coasts.sort(Comparator.<int[]>comparingInt(c -> c[2]).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));

		Shore shore = new Shore(field, scale, grid);
		int areas = 0;
		for (int[] coast : coasts) {
			int ci = coast[0], cj = coast[1];
			int[][] around = {{ci, cj}, {ci - 1, cj}, {ci + 1, cj}, {ci, cj - 1}, {ci, cj + 1}};
			for (int k = 0; k < around.length && areas < MAX_AREAS; k++) {
				int ai = around[k][0], aj = around[k][1];
				if (k > 0 && grid.land(ai, aj)) {
					continue;
				}
				shore.scan(ai, aj);
				areas++;
				if (shore.beach != null) {
					return shore.beach;
				}
			}
			if (shore.rocky != null || areas >= MAX_AREAS) {
				break;
			}
		}
		return shore.rocky;
	}

	/**
	 * The highest point near the origin: the highest of the coarse pixels around, then its highest native pixel,
	 * followed into the next coarse pixel while it lies on the edge. {@code null} if nothing around is a mountain.
	 */
	static Target summit(TerrainField field, WorldScale scale) {
		Grid grid = new Grid(field, scale);
		int bestI = 0, bestJ = 0;
		float best = Float.NEGATIVE_INFINITY;
		for (int di = -REACH; di <= REACH; di++) {
			for (int dj = -REACH; dj <= REACH; dj++) {
				float e = field.coarseElevation(grid.centre(grid.ci + di), grid.centre(grid.cj + dj));
				if (e > best) {
					best = e;
					bestI = di;
					bestJ = dj;
				}
			}
		}
		if (best < MOUNTAIN) {
			return null;
		}
		int ci = grid.ci + bestI, cj = grid.cj + bestJ, cs = grid.cs;
		double topI = 0, topJ = 0;
		float top = Float.NEGATIVE_INFINITY;
		for (int area = 0; area < MAX_AREAS; area++) {
			boolean rose = false;
			for (int i = ci * cs; i < (ci + 1) * cs; i += STEP) {
				for (int j = cj * cs; j < (cj + 1) * cs; j += STEP) {
					float e = field.elevation(i + 0.5, j + 0.5);
					if (e > top) {
						top = e;
						topI = i + 0.5;
						topJ = j + 0.5;
						rose = true;
					}
				}
			}
			if (!rose) {
				break;
			}
			// On the edge, the ground may still rise into the next coarse pixel.
			int ni = topI < ci * cs + STEP ? ci - 1 : topI > (ci + 1) * cs - STEP ? ci + 1 : ci;
			int nj = topJ < cj * cs + STEP ? cj - 1 : topJ > (cj + 1) * cs - STEP ? cj + 1 : cj;
			if (ni == ci && nj == cj) {
				break;
			}
			ci = ni;
			cj = nj;
		}
		// The finest pixel around the best one found on the coarser step.
		double fineI = topI, fineJ = topJ;
		for (int di = -STEP; di <= STEP; di++) {
			for (int dj = -STEP; dj <= STEP; dj++) {
				float e = field.elevation(topI + di, topJ + dj);
				if (e > top) {
					top = e;
					fineI = topI + di;
					fineJ = topJ + dj;
				}
			}
		}
		return grid.target(fineI, fineJ);
	}

	/** The coarse pixels around the origin's. */
	private static final class Grid {
		final TerrainField field;
		final WorldScale scale;
		final int cs;
		final int ci;
		final int cj;
		final double originI;
		final double originJ;

		Grid(TerrainField field, WorldScale scale) {
			this.field = field;
			this.scale = scale;
			this.cs = field.coarseScale();
			this.originI = scale.toPixel(0);
			this.originJ = scale.toPixel(0);
			this.ci = (int) Math.floor(originI / cs);
			this.cj = (int) Math.floor(originJ / cs);
		}

		/** The native pixel coordinate of a coarse pixel's centre. */
		double centre(int c) {
			return (c + 0.5) * cs;
		}

		boolean land(int ci, int cj) {
			return field.coarseElevation(centre(ci), centre(cj)) > 0f;
		}

		Target target(double i, double j) {
			double perBlock = scale.pixelsPerBlock();
			return new Target((int) Math.floor(j / perBlock), (int) Math.floor(i / perBlock));
		}

		double distance2(double i, double j) {
			return (i - originI) * (i - originI) + (j - originJ) * (j - originJ);
		}
	}

	/** The nearest beach and rocky shore found so far. */
	private static final class Shore {
		final TerrainField field;
		final WorldScale scale;
		final Grid grid;
		Target beach;
		Target rocky;
		double rockyDistance = Double.POSITIVE_INFINITY;

		Shore(TerrainField field, WorldScale scale, Grid grid) {
			this.field = field;
			this.scale = scale;
			this.grid = grid;
		}

		/** Reads the full terrain of one coarse pixel. */
		void scan(int ci, int cj) {
			int cs = grid.cs;
			double beachDistance = Double.POSITIVE_INFINITY;
			double beachI = 0, beachJ = 0;
			for (int i = ci * cs; i < (ci + 1) * cs; i += STEP) {
				for (int j = cj * cs; j < (cj + 1) * cs; j += STEP) {
					double pi = i + 0.5, pj = j + 0.5;
					float coast = field.coast(pi, pj);
					if (coast <= 0f || coast > SHORE) {
						continue;
					}
					float above = scale.surfaceY(field.elevation(pi, pj)) - scale.seaLevel();
					if (above < 1f || above > CLIFF) {
						continue;
					}
					double d = grid.distance2(pi, pj);
					if (above <= BEACH && d < beachDistance) {
						beachDistance = d;
						beachI = pi;
						beachJ = pj;
					}
					if (d < rockyDistance) {
						rockyDistance = d;
						rocky = grid.target(pi, pj);
					}
				}
			}
			if (beachDistance < Double.POSITIVE_INFINITY) {
				beach = grid.target(beachI, beachJ);
			}
		}
	}
}
