package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.Glaciation;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.jspecify.annotations.Nullable;

/**
 * The ground of a grid some of whose terrain tiles are not computed yet ({@link LodSampler.Detail#PARTIAL}), without
 * running the models: all that is known about it, so that the grid stands where the terrain it waits for will.
 *
 * <p>On the tiles computed it is the terrain itself. On the others it starts from the coarse map, sunk into the long
 * fjords, which the coarse map alone sets: one pixel of that map is kilometres of ground, whose mean can stand a
 * thousand blocks over the sea of the fjord through it. Around a tile computed comes the terrain's relief over the
 * coarse map: what the tile holds as far inside its edge as the point lies out of it, mirrored across the edge, so that
 * the ground meets the tile at its height and goes on as rugged as it is, fading into the coarse map over
 * {@value #REACH} pixels. Several tiles take turns by how near each is, so the ground is one surface whichever grid
 * samples it. A sample is a function of the point and of the tiles computed, which is what lets grids sampled at the
 * same moment meet, and {@link #computed} says when that has changed.
 */
final class RoughGround {
	/** Pixels the relief of a tile reaches out of it: under a tile, so that its mirror stays within it. */
	static final int REACH = 64;
	/** Pixels from a tile not computed within which the terrain goes without the shapes of the shore, which read inland. */
	private static final int SHORE = 32;

	private final TerrainField field;
	private final WorldScale scale;
	private final TerrainSurface surface;
	private final int ti0;
	private final int tj0;
	private final int ti1;
	private final int tj1;
	private final long computed;
	private final Glaciation.@Nullable Carving carving;

	/** The ground of a grid over pixels {@code [i0, i1] x [j0, j1]}, as the tiles stand now. */
	RoughGround(TerrainField field, WorldScale scale, double i0, double j0, double i1, double j1) {
		this.field = field;
		this.scale = scale;
		this.surface = field.surface(scale);
		this.ti0 = TerrainField.tileOf(i0 - REACH);
		this.tj0 = TerrainField.tileOf(j0 - REACH);
		this.ti1 = TerrainField.tileOf(i1 + REACH);
		this.tj1 = TerrainField.tileOf(j1 + REACH);
		this.computed = computed(field, i0, j0, i1, j1);
		// With the mirrors of the columns.
		int windowI = (int) Math.floor(i0) - 2 * REACH, windowJ = (int) Math.floor(j0) - 2 * REACH;
		this.carving = field.carving(windowI, windowJ, (int) Math.ceil(i1) + 2 * REACH - windowI,
			(int) Math.ceil(j1) + 2 * REACH - windowJ);
	}

	/**
	 * Which tiles shape the ground of a grid over pixels {@code [i0, i1] x [j0, j1]}, a bit each. A grid of
	 * {@value LodSampler#FULL_DETAIL_TILES} tiles at the most, which is as wide as this is asked of, touches 7 across.
	 */
	static long computed(TerrainField field, double i0, double j0, double i1, double j1) {
		int ti0 = TerrainField.tileOf(i0 - REACH), tj0 = TerrainField.tileOf(j0 - REACH);
		int ti1 = TerrainField.tileOf(i1 + REACH), tj1 = TerrainField.tileOf(j1 + REACH);
		int columns = tj1 - tj0 + 1;
		long computed = 0L;
		for (int ti = ti0; ti <= ti1; ti++) {
			for (int tj = tj0; tj <= tj1; tj++) {
				if (field.tileReady(ti, tj)) {
					computed |= 1L << (ti - ti0) * columns + tj - tj0;
				}
			}
		}
		return computed;
	}

	private boolean computed(int ti, int tj) {
		return ti >= ti0 && ti <= ti1 && tj >= tj0 && tj <= tj1
			&& (computed >>> (ti - ti0) * (tj1 - tj0 + 1) + tj - tj0 & 1L) != 0L;
	}

	/** The ground at the centre of column {@code (x, z)}, as {@link TerrainSurface#shore} has it where it can. */
	float surfaceY(int x, int z) {
		double i = scale.toPixel(z), j = scale.toPixel(x);
		if (!computed(TerrainField.tileOf(i), TerrainField.tileOf(j))) {
			return scale.surfaceY(elevation(i, j));
		}
		boolean inside = computed(TerrainField.tileOf(i - SHORE), TerrainField.tileOf(j - SHORE))
			&& computed(TerrainField.tileOf(i - SHORE), TerrainField.tileOf(j + SHORE))
			&& computed(TerrainField.tileOf(i + SHORE), TerrainField.tileOf(j - SHORE))
			&& computed(TerrainField.tileOf(i + SHORE), TerrainField.tileOf(j + SHORE));
		return inside ? surface.shore(x, z) : scale.surfaceY(field.elevation(i, j));
	}

	/** The elevation at a point of a tile that is not computed, in meters. */
	private float elevation(double i, double j) {
		double weights = 0, relief = 0, nearest = REACH;
		int lastI = TerrainField.tileOf(i + REACH), lastJ = TerrainField.tileOf(j + REACH);
		for (int ti = TerrainField.tileOf(i - REACH); ti <= lastI; ti++) {
			for (int tj = TerrainField.tileOf(j - REACH); tj <= lastJ; tj++) {
				if (!computed(ti, tj)) {
					continue;
				}
				double mirrorI = mirror(i, ti), mirrorJ = mirror(j, tj);
				double distance = Math.hypot(mirrorI - i, mirrorJ - j) / 2;
				if (distance >= REACH) {
					continue;
				}
				// Nothing at the end of its reach, and all there is at its edge.
				double near = (REACH - distance) / (REACH * Math.max(distance, 1e-3));
				double weight = near * near;
				weights += weight;
				relief += weight * (field.elevation(mirrorI, mirrorJ) - coarse(mirrorI, mirrorJ));
				nearest = Math.min(nearest, distance);
			}
		}
		float ground = coarse(i, j);
		if (weights == 0) {
			return ground;
		}
		double t = nearest / REACH;
		return (float) (ground + (1 - t * t * (3 - 2 * t)) * relief / weights);
	}

	/** The coarse map at a point, sunk into the fjords. */
	private float coarse(double i, double j) {
		float elevation = field.coarseElevation(i, j);
		return carving == null ? elevation : carving.sink(i, j, elevation);
	}

	/** The coordinate within tile {@code tile}, along one axis, as far inside its edge as {@code pixel} lies out of it. */
	private static double mirror(double pixel, int tile) {
		// As TerrainField.tileOf cuts the pixels into tiles.
		double low = tile * (double) TerrainField.TILE + 0.5, high = low + TerrainField.TILE;
		return pixel < low ? 2 * low - pixel : pixel >= high ? Math.nextDown(2 * high - pixel) : pixel;
	}
}
