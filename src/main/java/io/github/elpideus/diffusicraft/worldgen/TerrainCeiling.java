package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;

/**
 * How high the ground of a chunk or a column can reach, so that the chunk generator fills only up to there instead of
 * the whole height of the world. In Earth 1:1 the world is 9,344 blocks tall but most ground lies in its lowest few
 * hundred; filling the rest would sample the density and run the aquifer for millions of blocks of air per chunk.
 *
 * <p>The ceiling is exact, not a guess. Above the terrain's surface the world's density only falls (caves, the fade
 * to air at the top), except where the rivers build up their banks and where structures adapt the terrain. The
 * terrain is interpolated between the corners of cells 4 blocks wide, so the surface between corners never rises
 * above the highest corner, and the corners of a chunk all lie within it or on its far edges. Rivers are not
 * interpolated, so their own columns count. Water fills the sea up to its level.
 */
public final class TerrainCeiling {
	/** Width of the cells the terrain is interpolated over. */
	private static final int CELL = 4;
	/** Room left above the highest block: one cell of the vertical interpolation. */
	private static final int MARGIN = 8;

	private TerrainCeiling() {
	}

	/**
	 * The highest block the chunk whose lowest corner is block {@code (minX, minZ)} can hold, apart from structures:
	 * the terrain at every corner of its cells, the 16 by 16 columns' rivers and the sea, plus {@link #MARGIN}.
	 */
	public static int ofChunk(TerrainField field, Rivers rivers, WorldScale scale, int seaLevel, int minX, int minZ) {
		float highest = seaLevel;
		for (int z = minZ; z <= minZ + 16; z += CELL) {
			for (int x = minX; x <= minX + 16; x += CELL) {
				highest = Math.max(highest, surface(field, scale, x, z));
			}
		}
		for (int z = minZ; z < minZ + 16; z++) {
			for (int x = minX; x < minX + 16; x++) {
				highest = Math.max(highest, riverTop(rivers, scale, x, z));
			}
		}
		return (int) Math.ceil(highest) + MARGIN;
	}

	/** The highest block column {@code (x, z)} can hold, as {@link #ofChunk} works it out for a whole chunk. */
	public static int ofColumn(TerrainField field, Rivers rivers, WorldScale scale, int seaLevel, int x, int z) {
		int cellX = Math.floorDiv(x, CELL) * CELL, cellZ = Math.floorDiv(z, CELL) * CELL;
		float highest = Math.max(seaLevel, riverTop(rivers, scale, x, z));
		for (int dz = 0; dz <= CELL; dz += CELL) {
			for (int dx = 0; dx <= CELL; dx += CELL) {
				highest = Math.max(highest, surface(field, scale, cellX + dx, cellZ + dz));
			}
		}
		return (int) Math.ceil(highest) + MARGIN;
	}

	private static float surface(TerrainField field, WorldScale scale, int x, int z) {
		return field.surface(scale).y(x, z);
	}

	private static float riverTop(Rivers rivers, WorldScale scale, int x, int z) {
		RiverColumn column = rivers.column(scale, x, z);
		return column.river ? Math.max(column.surface, column.waterTop) : Float.NEGATIVE_INFINITY;
	}
}
