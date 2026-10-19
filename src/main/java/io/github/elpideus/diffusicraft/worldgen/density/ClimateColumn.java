package io.github.elpideus.diffusicraft.worldgen.density;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;

/**
 * The biome parameters of a column. Vanilla's climate sampler reads all six parameters at the same position in a row,
 * and the biome source walks whole columns, so each thread keeps the column it last asked about ({@link #at}).
 *
 * <p>Columns in the valley of a river get the parameters of vanilla's river biomes, so rivers are rivers on the map
 * and freeze where it is cold. Along the coast, where the sea cliffs reshape the ground, the column reads the
 * {@link io.github.elpideus.diffusicraft.worldgen.TerrainSurface ground} itself: the foot of a cliff is a beach, its
 * top is not.
 */
public final class ClimateColumn {
	/** Highest erosion at which vanilla still picks a river biome for weirdness zero, inland. */
	private static final float RIVER_EROSION = -0.36f;
	private static final ThreadLocal<ClimateColumn> LAST = ThreadLocal.withInitial(ClimateColumn::new);

	private TerrainField field;
	private WorldScale scale;
	private boolean coarse;
	private int blockX;
	private int blockZ;
	public final TerrainField.Column column = new TerrainField.Column();
	/** The 2D biome parameters, indexed as in {@link ClimateMapping}. */
	public final float[] parameters = new float[ClimateMapping.PARAMETERS];
	/** The ground, in blocks: exact along the coast, the smooth surface elsewhere. */
	public float surfaceY;
	/** Whether the column lies in a river's valley. */
	public boolean valley;

	/** The column at a block, from the coarse map inside {@link CoarseClimate#call}. */
	public static ClimateColumn at(TerrainField field, WorldScale scale, int blockX, int blockZ) {
		ClimateColumn c = LAST.get();
		boolean coarse = CoarseClimate.active();
		if (c.field != field || c.scale != scale || c.coarse != coarse || c.blockX != blockX || c.blockZ != blockZ) {
			c.sample(field, scale, blockX, blockZ);
		}
		return c;
	}

	/** Fills this column for a block, from the coarse map inside {@link CoarseClimate#call}. */
	public void sample(TerrainField field, WorldScale scale, int blockX, int blockZ) {
		boolean coarse = CoarseClimate.active();
		this.field = null;
		if (coarse) {
			field.coarseColumn(scale.toPixel(blockZ), scale.toPixel(blockX), column);
		} else {
			field.column(scale.toPixel(blockZ), scale.toPixel(blockX), column);
		}
		double mpb = scale.metersPerBlock();
		column.variant = field.variant(blockX * mpb, blockZ * mpb);
		surfaceY = column.coast >= 0f && column.coast < ClimateMapping.maxCoastWidth(scale)
			? field.surface(scale).y(blockX, blockZ)
			: scale.surfaceY(column.elevation);
		ClimateMapping.compute(scale, column, surfaceY, parameters);
		valley = !coarse && Rivers.of(field).column(scale, blockX, blockZ).valley;
		if (valley) {
			// Vanilla picks its river biomes where weirdness is near zero, unless the land is very eroded.
			parameters[ClimateMapping.WEIRDNESS] = 0f;
			parameters[ClimateMapping.EROSION] = Math.max(parameters[ClimateMapping.EROSION], RIVER_EROSION);
		}
		this.field = field;
		this.scale = scale;
		this.coarse = coarse;
		this.blockX = blockX;
		this.blockZ = blockZ;
	}

	/** Whether this column holds (x, z) of a world, sampled the way {@link #sample} would sample it now. */
	public boolean isAt(TerrainField field, WorldScale scale, int blockX, int blockZ) {
		return this.field == field && this.scale == scale && coarse == CoarseClimate.active() && this.blockX == blockX
			&& this.blockZ == blockZ;
	}

	public ClimateColumn() {
	}

	public int blockX() {
		return blockX;
	}

	public int blockZ() {
		return blockZ;
	}
}
