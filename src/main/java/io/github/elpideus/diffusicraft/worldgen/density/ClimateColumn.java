package io.github.elpideus.diffusicraft.worldgen.density;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;

/**
 * The biome parameters of the column a thread last asked about. Vanilla's climate sampler reads all six parameters at
 * the same position in a row, and the biome source walks whole columns, so one entry per thread saves most of the
 * work.
 *
 * <p>Columns in the valley of a river get the parameters of vanilla's river biomes, so rivers are rivers on the map
 * and freeze where it is cold.
 */
final class ClimateColumn {
	/** Highest erosion at which vanilla still picks a river biome for weirdness zero, inland. */
	private static final float RIVER_EROSION = -0.36f;
	private static final ThreadLocal<ClimateColumn> LAST = ThreadLocal.withInitial(ClimateColumn::new);

	private TerrainField field;
	private WorldScale scale;
	private boolean coarse;
	private int blockX;
	private int blockZ;
	final TerrainField.Column column = new TerrainField.Column();
	final float[] parameters = new float[ClimateMapping.PARAMETERS];
	float surfaceY;

	/** The column at a block, from the coarse map inside {@link CoarseClimate#call}. */
	static ClimateColumn at(TerrainField field, WorldScale scale, int blockX, int blockZ) {
		ClimateColumn c = LAST.get();
		boolean coarse = CoarseClimate.active();
		if (c.field != field || c.scale != scale || c.coarse != coarse || c.blockX != blockX || c.blockZ != blockZ) {
			c.field = null;
			if (coarse) {
				field.coarseColumn(scale.toPixel(blockZ), scale.toPixel(blockX), c.column);
			} else {
				field.column(scale.toPixel(blockZ), scale.toPixel(blockX), c.column);
			}
			double mpb = scale.metersPerBlock();
			c.column.variant = field.variant(blockX * mpb, blockZ * mpb);
			ClimateMapping.compute(scale, c.column, c.parameters);
			if (!coarse && Rivers.of(field).column(scale, blockX, blockZ).valley) {
				// Vanilla picks its river biomes where weirdness is near zero, unless the land is very eroded.
				c.parameters[ClimateMapping.WEIRDNESS] = 0f;
				c.parameters[ClimateMapping.EROSION] = Math.max(c.parameters[ClimateMapping.EROSION], RIVER_EROSION);
			}
			c.surfaceY = scale.surfaceY(c.column.elevation);
			c.field = field;
			c.scale = scale;
			c.coarse = coarse;
			c.blockX = blockX;
			c.blockZ = blockZ;
		}
		return c;
	}

	private ClimateColumn() {
	}
}
