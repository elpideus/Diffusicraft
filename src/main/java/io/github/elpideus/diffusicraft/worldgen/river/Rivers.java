package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rivers of a world: where they run and what they do to each block column.
 *
 * <p>Rivers come from the drainage of the coarse map ({@link RiverNetwork}): a river starts wherever enough rain has
 * gathered and runs down to the sea or into a pond. Inside each coarse cell, which is one {@link TerrainField} tile,
 * it follows the low ground of the fine terrain ({@link CellRivers}). Its width grows with the square root of its
 * discharge, like real rivers, and is widened at the smaller scales so that streams are still visible.
 *
 * <p>A column near a river is cut down to the water, or built up where the ground lies below it, with banks that
 * blend into the terrain around. Away from the rivers, closed basins of the fine terrain hold lakes ({@link CellLakes}).
 * Everything is computed on demand, on any thread, and comes out the same.
 */
public final class Rivers {
	private static final Map<TerrainField, Rivers> ALL = new ConcurrentHashMap<>();
	/** Cells whose rivers are kept in memory. */
	private static final int MAX_CELLS = 256;
	/** Widest a river gets, half-width in pixels (240 m). */
	static final float MAX_HALF_WIDTH = 8f;
	/** How much steeper than one block per block, horizontally, the banks are: horizontal blocks per block of height. */
	private static final float BANK_RUN = 1.5f;
	/** Blocks beyond the water that still count as the river's valley for biomes. */
	private static final float VALLEY = 3f;
	/** Blocks of ground built under a river that runs above the terrain, below the lower of bed and ground. */
	private static final int FOOTING = 4;
	/**
	 * Blocks around a channel whose banks rise above its water. Where the water steps down a block, the higher water
	 * runs over the step into the air above the lower water. A bank lower than the higher water would let it out, to
	 * run along the foot of the bank as a second stream.
	 */
	private static final int HOLD = 2;
	/** Columns a thread keeps for the chunk it is generating, with a border of {@link #HOLD} blocks. */
	private static final int SPAN = 16 + 2 * HOLD;

	private final TerrainField field;
	private final RiverNetwork network;
	private final Map<Long, CompletableFuture<Cell>> cells = new ConcurrentHashMap<>();
	private final AtomicLong clock = new AtomicLong();
	private final ThreadLocal<ChunkColumns> chunks = ThreadLocal.withInitial(ChunkColumns::new);

	private Rivers(TerrainField field) {
		this.field = field;
		// Rivers need the coarse map at the tile size; test sources with another scale get none.
		this.network = field.coarseScale() == TerrainField.TILE ? new RiverNetwork(field) : null;
	}

	/** The rivers of the world whose terrain is {@code field}. */
	public static Rivers of(TerrainField field) {
		return ALL.computeIfAbsent(field, Rivers::new);
	}

	/** Forgets the rivers of every world; called when the server stops. */
	public static void clearCache() {
		ALL.clear();
	}

	/**
	 * What the rivers do to the column at block {@code (x, z)}. The result belongs to the calling thread and holds
	 * until it asks about another chunk.
	 */
	public RiverColumn column(WorldScale scale, int x, int z) {
		ChunkColumns cache = chunks.get();
		int chunkX = x >> 4, chunkZ = z >> 4;
		if (cache.scale != scale || cache.chunkX != chunkX || cache.chunkZ != chunkZ) {
			cache.scale = scale;
			cache.chunkX = chunkX;
			cache.chunkZ = chunkZ;
			Arrays.fill(cache.valid, false);
			Arrays.fill(cache.rawValid, false);
		}
		int index = (z & 15) << 4 | (x & 15);
		RiverColumn column = cache.columns[index];
		if (!cache.valid[index]) {
			column.set(raw(scale, x, z, cache));
			if (column.river && !column.channel) {
				hold(scale, x, z, column, cache);
			}
			cache.valid[index] = true;
		}
		return column;
	}

	/** The column as its own river shapes it, for a column within {@link #HOLD} blocks of the cached chunk. */
	private RiverColumn raw(WorldScale scale, int x, int z, ChunkColumns cache) {
		int index = (z - (cache.chunkZ << 4) + HOLD) * SPAN + x - (cache.chunkX << 4) + HOLD;
		RiverColumn column = cache.raw[index];
		if (!cache.rawValid[index]) {
			compute(scale, x, z, column, cache);
			cache.rawValid[index] = true;
		}
		return column;
	}

	/** Raises a bank above the water of every channel column within {@link #HOLD} blocks. */
	private void hold(WorldScale scale, int x, int z, RiverColumn bank, ChunkColumns cache) {
		int highest = Integer.MIN_VALUE;
		for (int dz = -HOLD; dz <= HOLD; dz++) {
			for (int dx = -HOLD; dx <= HOLD; dx++) {
				RiverColumn near = raw(scale, x + dx, z + dz, cache);
				if (near.channel) {
					highest = Math.max(highest, near.waterTop);
				}
			}
		}
		if (highest != Integer.MIN_VALUE) {
			bank.surface = Math.max(bank.surface, highest + 1);
		}
	}

	private void compute(WorldScale scale, int x, int z, RiverColumn out, ChunkColumns cache) {
		out.clear();
		if (network == null) {
			return;
		}
		double i = scale.toPixel(z), j = scale.toPixel(x);
		int ti = Math.floorDiv((int) Math.floor(i), TerrainField.TILE);
		int tj = Math.floorDiv((int) Math.floor(j), TerrainField.TILE);
		Cell cell = cell(ti, tj, cache);
		CellRivers.Hit hit = cache.hit;
		if (!cell.rivers.nearest(i - ti * TerrainField.TILE, j - tj * TerrainField.TILE, width(scale), hit)) {
			lake(scale, i, j, cell.lakes, ti, tj, out);
			return;
		}
		float elevation = field.elevation(i, j);
		if (elevation < 0f) {
			return;
		}
		float blocksPerPixel = (float) (WorldScale.METERS_PER_PIXEL / scale.metersPerBlock());
		float distance = hit.distance * blocksPerPixel;
		float halfWidth = hit.halfWidth * blocksPerPixel;
		float reach = CellRivers.REACH * blocksPerPixel;
		float terrain = scale.surfaceY(elevation);
		int waterTop = (int) Math.ceil(scale.surfaceY(hit.level)) - 1;
		float top = waterTop + 1;

		out.level = hit.level;
		out.discharge = hit.discharge;
		out.waterTop = waterTop;
		out.valley = distance <= halfWidth + VALLEY;
		if (distance <= halfWidth) {
			int centreDepth = Math.clamp(Math.round(halfWidth * 0.5f), 1, maxDepth(scale));
			double across = distance / halfWidth;
			int depth = Math.max(1, (int) Math.round(centreDepth * Math.sqrt(1.0 - across * across)));
			out.river = true;
			out.channel = true;
			out.surface = top;
			out.bed = waterTop - depth;
			out.bandBottom = Math.min(terrain, out.bed) - FOOTING;
			return;
		}
		float bank = Math.clamp(BANK_RUN * Math.abs(terrain - top) + 2f, 2f, Math.max(2f, reach - 0.5f));
		float t = (distance - halfWidth) / bank;
		if (t >= 1f) {
			lake(scale, i, j, cell.lakes, ti, tj, out);
			return;
		}
		float blend = t * t * (3f - 2f * t);
		out.river = true;
		out.surface = top + (terrain - top) * blend;
		out.bandBottom = Math.min(terrain, out.surface) - FOOTING;
	}

	/**
	 * Fills the column with the lake it lies in, if any, or makes it the lake's shore. A column lies in a lake where
	 * one of the four pixels around it does and its ground is below the water. The water stops at the last whole block
	 * below the lake's level, so it never stands above the rim of its basin and the shores need no banks. The shore,
	 * within two pixels of a lake, keeps its ground but is solid from below the water up, so no cave opens beside the
	 * lake to drain it. Where it still lies below the water, {@link #hold} raises it into a bank.
	 */
	private void lake(WorldScale scale, double i, double j, CellLakes lakes, int ti, int tj, RiverColumn out) {
		if (lakes.isEmpty()) {
			return;
		}
		double u = i - ti * TerrainField.TILE - 0.5, v = j - tj * TerrainField.TILE - 0.5;
		int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
		float inner = Float.NaN, outer = Float.NaN;
		for (int di = -1; di <= 2; di++) {
			for (int dj = -1; dj <= 2; dj++) {
				float level = lakes.level(iu + di, iv + dj);
				if (!Float.isNaN(level)) {
					outer = Float.isNaN(outer) ? level : Math.max(outer, level);
					if (di >= 0 && di <= 1 && dj >= 0 && dj <= 1) {
						inner = Float.isNaN(inner) ? level : Math.max(inner, level);
					}
				}
			}
		}
		if (Float.isNaN(outer)) {
			return;
		}
		float terrain = scale.surfaceY(field.elevation(i, j));
		if (!Float.isNaN(inner)) {
			int waterTop = (int) Math.floor(scale.surfaceY(inner)) - 1;
			if (terrain <= waterTop) {
				out.river = true;
				out.channel = true;
				out.lake = true;
				out.surface = waterTop + 1;
				out.waterTop = waterTop;
				out.bed = (int) Math.ceil(terrain) - 1;
				out.bandBottom = out.bed - FOOTING;
				out.level = inner;
				out.discharge = 0f;
				out.valley = false;
				return;
			}
		}
		int waterTop = (int) Math.floor(scale.surfaceY(outer)) - 1;
		out.river = true;
		out.surface = terrain;
		out.bandBottom = Math.min(terrain, waterTop + 1) - FOOTING;
		out.waterTop = waterTop;
		out.level = outer;
		out.discharge = 0f;
	}

	private Cell cell(int ti, int tj, ChunkColumns cache) {
		if (cache.cell != null && cache.cellI == ti && cache.cellJ == tj) {
			return cache.cell;
		}
		Cell cell = RiverNetwork.once(cells, RiverNetwork.key(ti, tj), () -> {
			CellFlows flows = network.flows(ti, tj);
			float[] tile = field.paddedTile(ti, tj);
			CellRivers rivers = flows.isEmpty() ? CellRivers.EMPTY
				: CellRivers.build(tile, flows, field.seed(), ti, tj);
			return new Cell(rivers, lakes(tile, rivers, ti, tj));
		});
		cell.lastUse = clock.incrementAndGet();
		if (cells.size() > MAX_CELLS) {
			evict();
		}
		cache.cell = cell;
		cache.cellI = ti;
		cache.cellJ = tj;
		return cell;
	}

	/** The lakes of a tile, filled as far as its precipitation outweighs the evaporation. */
	private CellLakes lakes(float[] tile, CellRivers rivers, int ti, int tj) {
		TerrainField.Column column = new TerrainField.Column();
		return CellLakes.build(tile, rivers, (i, j) -> {
			field.column(i + 0.5, j + 0.5, column);
			float evaporation = RiverNetwork.evapotranspiration(column.temperature);
			return evaporation <= 0f ? Float.POSITIVE_INFINITY : column.precipitation / evaporation;
		}, ti, tj);
	}

	private synchronized void evict() {
		while (cells.size() > MAX_CELLS) {
			long oldestKey = 0, oldestUse = Long.MAX_VALUE;
			for (Map.Entry<Long, CompletableFuture<Cell>> entry : cells.entrySet()) {
				CompletableFuture<Cell> future = entry.getValue();
				if (future.isDone() && !future.isCompletedExceptionally()) {
					long use = future.join().lastUse;
					if (use < oldestUse) {
						oldestUse = use;
						oldestKey = entry.getKey();
					}
				}
			}
			if (oldestUse == Long.MAX_VALUE) {
				return;
			}
			cells.remove(oldestKey);
		}
	}

	/**
	 * Half the width of a river, in pixels. Real rivers are about 4√Q meters wide; streams are widened at the smaller
	 * scales so they stay at least two blocks wide and wide enough to look like rivers.
	 */
	static float halfWidthPixels(WorldScale scale, float discharge) {
		double widening = switch (scale) {
			case STANDARD -> 3.0;
			case GRAND -> 1.5;
			case EARTH -> 1.0;
		};
		double meters = Math.max(2.0 * Math.sqrt(discharge) * widening, scale.metersPerBlock());
		return (float) Math.min(meters / WorldScale.METERS_PER_PIXEL, MAX_HALF_WIDTH);
	}

	/** Deepest a river gets, in blocks. */
	static int maxDepth(WorldScale scale) {
		return switch (scale) {
			case STANDARD -> 4;
			case GRAND -> 6;
			case EARTH -> 10;
		};
	}

	private static CellRivers.Width width(WorldScale scale) {
		return WIDTHS[scale.ordinal()];
	}

	private static final CellRivers.Width[] WIDTHS = widths();

	private static CellRivers.Width[] widths() {
		WorldScale[] scales = WorldScale.values();
		CellRivers.Width[] widths = new CellRivers.Width[scales.length];
		for (WorldScale scale : scales) {
			widths[scale.ordinal()] = discharge -> halfWidthPixels(scale, discharge);
		}
		return widths;
	}

	private static final class Cell {
		final CellRivers rivers;
		final CellLakes lakes;
		volatile long lastUse;

		Cell(CellRivers rivers, CellLakes lakes) {
			this.rivers = rivers;
			this.lakes = lakes;
		}
	}

	/** One thread's columns of the chunk it is generating. */
	private static final class ChunkColumns {
		final RiverColumn[] columns = new RiverColumn[256];
		final boolean[] valid = new boolean[256];
		/** Columns before {@link #hold}, around and in the chunk. */
		final RiverColumn[] raw = new RiverColumn[SPAN * SPAN];
		final boolean[] rawValid = new boolean[SPAN * SPAN];
		final CellRivers.Hit hit = new CellRivers.Hit();
		WorldScale scale;
		int chunkX;
		int chunkZ;
		Cell cell;
		int cellI;
		int cellJ;

		ChunkColumns() {
			for (int k = 0; k < columns.length; k++) {
				columns[k] = new RiverColumn();
			}
			for (int k = 0; k < raw.length; k++) {
				raw[k] = new RiverColumn();
			}
		}
	}
}
