package io.github.elpideus.diffusicraft.worldgen.density;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;

/**
 * The biome parameters of a column. Vanilla's climate sampler reads all six parameters at the same position in a row,
 * and the biome source walks whole columns, so each thread keeps the column it last asked about ({@link #at}).
 *
 * <p>The climate is the one the ground feels on its slope ({@link ClimateMapping#expose}): warmer and drier where
 * the land faces the sun, cooler and moister where it faces away. The coarse map has no slopes.
 *
 * <p>Columns in the valley of a river get the parameters of vanilla's river biomes, so rivers are rivers on the map
 * and freeze where it is cold. Along the coast, where the sea cliffs reshape the ground, the column reads the
 * {@link io.github.elpideus.diffusicraft.worldgen.TerrainSurface ground} itself: the foot of a cliff is a beach, its
 * top is not.
 *
 * <p>A lake whose water stands above the sea over ground below it, in a valley the ice drowned behind a step, is a
 * lake among the mountains and not a fjord: its columns get the climate of land at the lake's level, away from the
 * coast. Their ground stays where it is, so the depth below it does too.
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
	private final float[] slope = new float[2];
	/** Whether {@link #at} is sampling this column. */
	private boolean sampling;
	/** The column {@link #at} uses while this one is {@link #sampling}, made when first needed. */
	private ClimateColumn nested;

	/**
	 * The column at a block, from the coarse map inside {@link CoarseClimate#call}. A thread handed other generation
	 * work while it samples a column, as it waits for the terrain, gets another column for it, so the two do not mix.
	 */
	public static ClimateColumn at(TerrainField field, WorldScale scale, int blockX, int blockZ) {
		ClimateColumn c = LAST.get();
		while (c.sampling) {
			c = c.nested();
		}
		boolean coarse = CoarseClimate.active();
		if (c.field != field || c.scale != scale || c.coarse != coarse || c.blockX != blockX || c.blockZ != blockZ) {
			c.sampling = true;
			try {
				c.sample(field, scale, blockX, blockZ);
			} finally {
				c.sampling = false;
			}
		}
		return c;
	}

	private ClimateColumn nested() {
		if (nested == null) {
			nested = new ClimateColumn();
		}
		return nested;
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
		if (!coarse && column.elevation > 0f) {
			// Pixel i grows with Z and j with X.
			field.slope(scale.toPixel(blockZ), scale.toPixel(blockX), slope);
			ClimateMapping.expose(column, ClimateMapping.exposure(slope[1], slope[0]));
		}
		double mpb = scale.metersPerBlock();
		column.variant = field.variant(blockX * mpb, blockZ * mpb);
		surfaceY = column.coast >= 0f && column.coast < ClimateMapping.maxCoastWidth(scale)
			? field.surface(scale).y(blockX, blockZ)
			: scale.surfaceY(column.elevation);
		RiverColumn river = coarse ? null : Rivers.of(field).column(scale, blockX, blockZ);
		if (river != null && river.lake && river.level > 0f && column.elevation < 0f) {
			column.aboveLow += river.level - column.elevation;
			column.elevation = river.level;
			column.coast = TerrainField.COAST_REACH;
		}
		ClimateMapping.compute(scale, column, surfaceY, parameters);
		valley = river != null && river.valley;
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
