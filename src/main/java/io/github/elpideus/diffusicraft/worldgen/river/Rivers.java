package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * blend into the terrain around. The water follows the ground in blocks ({@link RiverCourses}), so the rivers fall
 * where it drops away, over the ledges and cliffs in their way. Closed basins of the fine terrain hold lakes ({@link CellLakes}): the rivers fill the
 * basins they cross, and the rain the others. Basins that reach past the sides of a tile are found over blocks of two
 * by two and three by three tiles ({@link BlockLakes}), so the rivers and lakes of a tile need the terrain of the
 * {@value #RING} rings of tiles around it.
 * The depressions of the coarse map hold the largest lakes ({@link GreatLake}), found over all their tiles, which a
 * tile next to one needs too. Everything is computed on demand, on any thread, and comes out the same.
 */
public final class Rivers {
	private static final Map<TerrainField, Rivers> ALL = new ConcurrentHashMap<>();
	/** Cells whose rivers, blocks whose lakes, and tiles whose river paths are kept in memory, each. */
	private static final int MAX_CELLS = 256;
	/** Tiles on each side of a tile whose terrain its rivers and lakes need: those of its widest lake blocks. */
	public static final int RING = 2;
	/** Tiles across the two sizes of lake block. */
	private static final int SMALL = 2, WIDE = RING + 1;
	/** Widest a river gets, half-width in pixels (240 m). */
	static final float MAX_HALF_WIDTH = 8f;
	/** How much steeper than one block per block, horizontally, the banks are: horizontal blocks per block of height. */
	private static final float BANK_RUN = 1.5f;
	/** The same for the side of a bank built up on ground below the water, which falls steeply to the ground. */
	private static final float LEVEE_RUN = 0.5f;
	/** Blocks beyond the water that still count as the river's valley for biomes. */
	private static final float VALLEY = 3f;
	/** Blocks of ground built under a river that runs above the terrain, below the lower of bed and ground. */
	private static final int FOOTING = 4;
	/**
	 * Blocks around a channel whose banks rise above its water. Where the water steps down a block, the higher water
	 * runs over the step into the air above the lower water. A bank lower than the higher water would let it out, to
	 * run along the foot of the bank as a second stream.
	 */
	static final int HOLD = 2;
	/** Columns a thread keeps for the chunk it is generating, with a border of {@link #HOLD} blocks. */
	private static final int SPAN = 16 + 2 * HOLD;

	private final TerrainField field;
	private final RiverNetwork network;
	private final Map<Long, CompletableFuture<Cell>> cells = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Block>> blocks = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Block>> wideBlocks = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Paths>> paths = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Sea>> seas = new ConcurrentHashMap<>();
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
	 *
	 * <p>A thread can ask again while it is still computing a column, if it is handed other generation work while it
	 * waits for the terrain. The columns being computed must stay where they are, so the inner question gets columns
	 * of its own ({@link ChunkColumns#nested}).
	 */
	public RiverColumn column(WorldScale scale, int x, int z) {
		ChunkColumns cache = chunks.get();
		while (cache.busy) {
			cache = cache.nested();
		}
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
			cache.busy = true;
			try {
				column.set(raw(scale, x, z, cache));
				if (column.river && !column.channel) {
					hold(scale, x, z, column, cache);
				}
			} finally {
				cache.busy = false;
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
		Shore shore = cache.shore;
		shore.find(cell.lakes, i - ti * TerrainField.TILE, j - tj * TerrainField.TILE);
		boolean near = cell.rivers.nearest(i - ti * TerrainField.TILE, j - tj * TerrainField.TILE, width(scale), hit);
		if (!near && Float.isNaN(shore.outer)) {
			return;
		}
		float terrain = field.surface(scale).y(x, z);
		// A lake takes in the rivers that flow through it.
		if (lake(scale, terrain, shore, out)) {
			return;
		}
		if (!near) {
			shore(scale, terrain, shore, out);
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
		// The water of a river follows the ground in blocks; that of a pond lies at its level.
		int waterTop = (hit.path < 0 ? (int) Math.ceil(scale.surfaceY(hit.level))
			: courses(cell, ti, tj, scale).top(hit.path, hit.along)) - 1;
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
		if (terrain < top) {
			// Ground below the water: a bank as high as the water reaches past the columns that hold it, and its side
			// falls steeply to the ground, so that a river along a hillside rests on the hillside rather than on an
			// embankment over it.
			float levee = top - Math.max(0f, distance - halfWidth - HOLD - 1) / LEVEE_RUN;
			if (levee <= terrain) {
				shore(scale, terrain, shore, out);
				return;
			}
			out.river = true;
			out.surface = levee;
			out.bandBottom = terrain - FOOTING;
			return;
		}
		float bank = Math.clamp(BANK_RUN * (terrain - top) + 2f, 2f, Math.max(2f, reach - 0.5f));
		float t = (distance - halfWidth) / bank;
		if (t >= 1f) {
			shore(scale, terrain, shore, out);
			return;
		}
		float blend = t * t * (3f - 2f * t);
		out.river = true;
		out.surface = top + (terrain - top) * blend;
		out.bandBottom = out.surface - FOOTING;
	}

	/**
	 * Fills the column with the lake it lies in, if any. A column lies in a lake where one of the four pixels around it
	 * does and its ground is below the water. Returns whether it did.
	 */
	private static boolean lake(WorldScale scale, float terrain, Shore shore, RiverColumn out) {
		if (Float.isNaN(shore.inner)) {
			return false;
		}
		int waterTop = waterTop(scale, shore.inner, shore.innerFed);
		if (terrain > waterTop) {
			return false;
		}
		out.river = true;
		out.channel = true;
		out.lake = true;
		out.surface = waterTop + 1;
		out.waterTop = waterTop;
		out.bed = (int) Math.ceil(terrain) - 1;
		out.bandBottom = out.bed - FOOTING;
		out.level = shore.inner;
		out.discharge = 0f;
		out.valley = false;
		return true;
	}

	/**
	 * Makes the column a lake's shore, if it lies within two pixels of one. The shore keeps its ground but is solid from
	 * below the water up, so no cave opens beside the lake to drain it. Where it still lies below the water, {@link #hold}
	 * raises it into a bank.
	 */
	private static void shore(WorldScale scale, float terrain, Shore shore, RiverColumn out) {
		if (Float.isNaN(shore.outer)) {
			return;
		}
		int waterTop = waterTop(scale, shore.outer, shore.outerFed);
		out.river = true;
		out.surface = terrain;
		out.bandBottom = Math.min(terrain, waterTop + 1) - FOOTING;
		out.waterTop = waterTop;
		out.level = shore.outer;
		out.discharge = 0f;
	}

	/**
	 * The highest water block of a lake. The water of a lake that the rain fills stops at the last whole block below
	 * its level, so it never stands above the rim of its basin. A lake that a river fills is level with the river,
	 * which flows in and out of it, and its rim is held by the banks of the river and the shores.
	 */
	private static int waterTop(WorldScale scale, float level, boolean fed) {
		double y = scale.surfaceY(level);
		return (int) (fed ? Math.ceil(y) : Math.floor(y)) - 1;
	}

	/** The lakes around a column: the four pixels around it, and the ring of pixels around those. */
	private static final class Shore {
		/** Highest lake level of the four pixels around the column, or NaN. */
		float inner;
		boolean innerFed;
		/** Highest lake level of the sixteen pixels around the column, or NaN. */
		float outer;
		boolean outerFed;

		void find(CellLakes lakes, double u, double v) {
			inner = Float.NaN;
			outer = Float.NaN;
			if (lakes.isEmpty()) {
				return;
			}
			int iu = (int) Math.floor(u - 0.5), iv = (int) Math.floor(v - 0.5);
			for (int di = -1; di <= 2; di++) {
				for (int dj = -1; dj <= 2; dj++) {
					int run = lakes.run(iu + di, iv + dj);
					if (run < 0) {
						continue;
					}
					float level = lakes.runLevel(run);
					boolean fed = lakes.runFed(run);
					if (!(outer >= level)) {
						outer = level;
						outerFed = fed;
					}
					if (di >= 0 && di <= 1 && dj >= 0 && dj <= 1 && !(inner >= level)) {
						inner = level;
						innerFed = fed;
					}
				}
			}
		}
	}

	/**
	 * The tiles of the lake of the coarse map on tile {@code (ti, tj)}, as pairs {@code (ti, tj)}, or none. The rivers
	 * and lakes of the tile and of the tiles around it need the terrain of them all.
	 */
	public int[] lakeTiles(int ti, int tj) {
		RiverNetwork.Lake lake = network == null ? null : network.lakeAt(ti, tj);
		return lake == null ? new int[0] : lake.cells().clone();
	}

	/**
	 * The tiles whose terrain the rivers and lakes of tiles {@code ti0} to {@code ti1} down and {@code tj0} to
	 * {@code tj1} across need, as pairs {@code (ti, tj)}, the nearest first: those of the {@value #RING} rings around
	 * them, and those of the lakes of the coarse map beside them whose water has not been found yet. The tiles beside a
	 * river's mouth look at the seas of a few more.
	 */
	public int[] tilesNeeded(int ti0, int tj0, int ti1, int tj1) {
		Set<Long> needed = new LinkedHashSet<>();
		int reach = network == null ? 0 : RING;
		for (int ring = 0; ring <= reach; ring++) {
			for (int ti = ti0 - ring; ti <= ti1 + ring; ti++) {
				for (int tj = tj0 - ring; tj <= tj1 + ring; tj++) {
					needed.add(RiverNetwork.key(ti, tj));
				}
			}
		}
		if (network != null) {
			for (int ti = ti0 - 1; ti <= ti1 + 1; ti++) {
				for (int tj = tj0 - 1; tj <= tj1 + 1; tj++) {
					RiverNetwork.Lake lake = network.lakeAt(ti, tj);
					if (lake != null && !network.hasGreatLake(lake)) {
						int[] cells = lake.cells();
						for (int c = 0; c < cells.length; c += 2) {
							needed.add(RiverNetwork.key(cells[c], cells[c + 1]));
						}
					}
				}
			}
		}
		int[] tiles = new int[2 * needed.size()];
		int t = 0;
		for (long key : needed) {
			tiles[t++] = (int) (key >> 32);
			tiles[t++] = (int) key;
		}
		return tiles;
	}

	/**
	 * Finds the water of the lake of the coarse map on tile {@code (ti, tj)}, if there is one, from the terrain of all
	 * its {@link #lakeTiles}. The prefetcher calls it once it has computed them, so the lake never waits for those of
	 * them the tile cache has evicted since, and chunk generation finds the water ready.
	 */
	public void prefetchLake(int ti, int tj) {
		RiverNetwork.Lake lake = network == null ? null : network.lakeAt(ti, tj);
		if (lake != null) {
			network.greatLake(lake);
		}
	}

	/** Whether the water of the lake of the coarse map on tile {@code (ti, tj)} has been found; false without one. */
	boolean hasGreatLake(int ti, int tj) {
		RiverNetwork.Lake lake = network == null ? null : network.lakeAt(ti, tj);
		return lake != null && network.hasGreatLake(lake);
	}

	/** The lakes of tile {@code (ti, tj)}. */
	CellLakes lakes(int ti, int tj) {
		return network == null ? CellLakes.EMPTY : cell(ti, tj, chunks.get()).lakes;
	}

	/** Where the water of the rivers of tile {@code (ti, tj)}, whose cell is {@code cell}, stands at a world size. */
	private RiverCourses courses(Cell cell, int ti, int tj, WorldScale scale) {
		return RiverNetwork.once(cell.courses, scale.ordinal(), () -> RiverCourses.of(cell.rivers, cell.lakes, ti, tj,
			scale, field.surface(scale)::y, width(scale)));
	}

	private Cell cell(int ti, int tj, ChunkColumns cache) {
		if (cache.cell != null && cache.cellI == ti && cache.cellJ == tj) {
			return cache.cell;
		}
		Cell cell = RiverNetwork.once(cells, RiverNetwork.key(ti, tj), () -> {
			CellFlows flows = flows(ti, tj);
			float[] tile = field.paddedTile(ti, tj);
			// The lakes of the blocks the tile lies in, four of two by two tiles and nine of three by three, and of the
			// coarse map, and the ground their rivers see.
			float[] level = new float[CellLakes.SIDE * CellLakes.SIDE];
			boolean[] fed = new boolean[level.length];
			Arrays.fill(level, Float.NaN);
			float[] raise = null;
			for (int n = SMALL; n <= WIDE; n++) {
				for (int oi = 0; oi < n; oi++) {
					for (int oj = 0; oj < n; oj++) {
						BlockLakes block = block(n, ti - oi, tj - oj);
						if (!block.isEmpty()) {
							raise = raise == null ? nan(tile.length) : raise;
							block.paint(oi, oj, CellLakes.MARGIN, level, fed, raise);
						}
					}
				}
			}
			for (GreatLake lake : network.greatLakesAround(ti, tj)) {
				if (!lake.isEmpty()) {
					raise = raise == null ? nan(tile.length) : raise;
					lake.paint(ti, tj, CellLakes.MARGIN, level, fed, raise);
				}
			}
			CellBasins basins = CellBasins.of(tile, field.paddedDrowned(ti, tj), raise);
			float[] pathLevel = null;
			CellRivers rivers = CellRivers.EMPTY;
			if (!flows.isEmpty()) {
				pathLevel = new float[tile.length];
				Arrays.fill(pathLevel, Float.NaN);
				rivers = CellRivers.build(tile, basins, flows, field.seed(), ti, tj, pathLevel);
			}
			return new Cell(rivers, CellLakes.build(tile, basins, pathLevel, rivers, climate(), ti, tj, level, fed));
		});
		cell.lastUse = clock.incrementAndGet();
		if (cells.size() > MAX_CELLS) {
			evict(cells);
		}
		cache.cell = cell;
		cache.cellI = ti;
		cache.cellJ = tj;
		return cell;
	}

	/**
	 * The lakes of block {@code (bi, bj)} of {@code n} by {@code n} tiles, which covers tiles {@code bi} to
	 * {@code bi + n - 1} down, and so across.
	 */
	private BlockLakes block(int n, int bi, int bj) {
		Map<Long, CompletableFuture<Block>> map = n == WIDE ? wideBlocks : blocks;
		Block block = RiverNetwork.once(map, RiverNetwork.key(bi, bj), () -> {
			float[][] tiles = new float[n * n][];
			long[][] drowned = new long[n * n][];
			CellFlows[] flows = new CellFlows[n * n];
			for (int t = 0; t < n * n; t++) {
				tiles[t] = field.paddedTile(bi + t / n, bj + t % n);
				drowned[t] = field.paddedDrowned(bi + t / n, bj + t % n);
				flows[t] = flows(bi + t / n, bj + t % n);
			}
			return new Block(BlockLakes.build(n, tiles, drowned, flows, t -> paths(bi + t / n, bj + t % n),
				climate(), bi, bj));
		});
		block.lastUse = clock.incrementAndGet();
		if (map.size() > MAX_CELLS) {
			evict(map);
		}
		return block.lakes;
	}

	/**
	 * The rivers of tile {@code (ti, tj)} drawn without the lakes of the blocks around, which run where the tile's own
	 * rivers do, nowhere higher, and the pixels they run through: the blocks need them to tell which of their basins a
	 * river feeds or drains.
	 */
	private CellRivers paths(int ti, int tj) {
		Paths drawn = RiverNetwork.once(paths, RiverNetwork.key(ti, tj), () -> {
			float[] tile = field.paddedTile(ti, tj);
			return new Paths(CellRivers.paths(tile, CellBasins.of(tile, field.paddedDrowned(ti, tj), null),
				flows(ti, tj), field.seed(), ti, tj));
		});
		drawn.lastUse = clock.incrementAndGet();
		if (paths.size() > MAX_CELLS) {
			evict(paths);
		}
		return drawn.rivers;
	}

	/**
	 * Where rivers enter and leave tile {@code (ti, tj)}: as the network has them ({@link RiverNetwork#flows}), but out
	 * of a {@link RiverNetwork#MOUTH} whose sea lies at a side, outside the band its rivers run in, and nowhere in it.
	 * The coarse map sends them to that sea all the same, and they would end in a pond short of it. They leave across
	 * the side instead, at sea level, into the sea of the tile beyond, where its band has some: across the side whose
	 * node lies lowest, if more than one would do. The tiles beside the mouth decide this alike, from its terrain and
	 * theirs.
	 */
	CellFlows flows(int ti, int tj) {
		CellFlows flows = network.flows(ti, tj);
		int out = outlet(ti, tj, flows);
		if (out >= 0) {
			CellFlows through = new CellFlows();
			for (CellFlows.Inflow inflow : flows.inflows) {
				through.addIn(inflow.side(), out, flows.inLevel[inflow.side()], inflow.discharge());
			}
			through.addOut(out, 0f, inflow(flows));
			flows = through;
		}
		for (int side = 0; side < 4; side++) {
			int mi = ti + RiverNetwork.DI[side], mj = tj + RiverNetwork.DJ[side];
			if (network.direction(mi, mj) == RiverNetwork.MOUTH) {
				CellFlows mouth = network.flows(mi, mj);
				if (outlet(mi, mj, mouth) == (side ^ 1)) {
					flows = flows.isEmpty() ? new CellFlows() : flows;
					flows.addIn(side, CellFlows.SEA, 0f, inflow(mouth));
				}
			}
		}
		return flows;
	}

	/**
	 * The side across which the rivers of tile {@code (ti, tj)}, which the network has entering it as {@code flows},
	 * leave it for the sea of the tile beyond ({@link #flows}), or -1 where they do not.
	 */
	private int outlet(int ti, int tj, CellFlows flows) {
		if (flows.inflows.isEmpty() || network.direction(ti, tj) != RiverNetwork.MOUTH) {
			return -1;
		}
		Sea own = sea(ti, tj);
		int best = -1;
		for (int side = 0; side < 4; side++) {
			float node = own.node[side];
			// Not across a side that a river already enters by.
			if (Float.isNaN(node) || !Float.isNaN(flows.inLevel[side]) || best >= 0 && !(node < own.node[best])) {
				continue;
			}
			if (sea(ti + RiverNetwork.DI[side], tj + RiverNetwork.DJ[side]).band) {
				best = side;
			}
		}
		return best;
	}

	/** Where tile {@code (ti, tj)} has sea, as its own basins have it. */
	private Sea sea(int ti, int tj) {
		Sea sea = RiverNetwork.once(seas, RiverNetwork.key(ti, tj), () -> {
			float[] tile = field.paddedTile(ti, tj);
			CellBasins basins = CellBasins.of(tile, field.paddedDrowned(ti, tj), null);
			boolean band = CellRivers.seaInBand(basins);
			float[] node = nan(4);
			for (int side = 0; side < 4 && !band; side++) {
				node[side] = CellRivers.seaNode(tile, basins, side);
			}
			return new Sea(band, node);
		});
		sea.lastUse = clock.incrementAndGet();
		if (seas.size() > MAX_CELLS) {
			evict(seas);
		}
		return sea;
	}

	/** The discharge of the rivers that enter by {@code flows}. */
	private static float inflow(CellFlows flows) {
		float total = 0f;
		for (CellFlows.Inflow inflow : flows.inflows) {
			total += inflow.discharge();
		}
		return total;
	}

	private static float[] nan(int length) {
		float[] a = new float[length];
		Arrays.fill(a, Float.NaN);
		return a;
	}

	/** How far the rain fills a basin: as far as the precipitation outweighs the evaporation. For one thread. */
	private CellLakes.Climate climate() {
		TerrainField.Column column = new TerrainField.Column();
		return (i, j) -> {
			field.column(i + 0.5, j + 0.5, column);
			float evaporation = RiverNetwork.evapotranspiration(column.temperature);
			return evaporation <= 0f ? Float.POSITIVE_INFINITY : column.precipitation / evaporation;
		};
	}

	private synchronized <T extends Used> void evict(Map<Long, CompletableFuture<T>> map) {
		while (map.size() > MAX_CELLS) {
			long oldestKey = 0, oldestUse = Long.MAX_VALUE;
			for (Map.Entry<Long, CompletableFuture<T>> entry : map.entrySet()) {
				CompletableFuture<T> future = entry.getValue();
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
			map.remove(oldestKey);
		}
	}

	/**
	 * Half the width of a river, in pixels. Real rivers are about 4√Q meters wide; streams are widened at the smaller
	 * sizes so they stay at least two blocks wide and wide enough to look like rivers: by about 4.5 at 1:32, 1.6 at
	 * 1:4, not at all at 1:1.
	 */
	static float halfWidthPixels(WorldScale scale, float discharge) {
		double widening = Math.max(1.0, Math.sqrt(scale.metersPerBlock() / 1.6));
		double meters = Math.max(2.0 * Math.sqrt(discharge) * widening, scale.metersPerBlock());
		return (float) Math.min(meters / WorldScale.METERS_PER_PIXEL, MAX_HALF_WIDTH);
	}

	/** Deepest a river gets, in blocks: 3 at 1:32, 6 at 1:4, 10 at 1:1. */
	static int maxDepth(WorldScale scale) {
		return (int) Math.round(10 / Math.cbrt(scale.metersPerBlock()));
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

	/** Something kept in memory until it has gone unused the longest. */
	private abstract static class Used {
		volatile long lastUse;
	}

	private static final class Cell extends Used {
		final CellRivers rivers;
		final CellLakes lakes;
		/** Where the water of the rivers stands, by world size. */
		final Map<Long, CompletableFuture<RiverCourses>> courses = new ConcurrentHashMap<>();

		Cell(CellRivers rivers, CellLakes lakes) {
			this.rivers = rivers;
			this.lakes = lakes;
		}
	}

	private static final class Block extends Used {
		final BlockLakes lakes;

		Block(BlockLakes lakes) {
			this.lakes = lakes;
		}
	}

	private static final class Paths extends Used {
		final CellRivers rivers;

		Paths(CellRivers rivers) {
			this.rivers = rivers;
		}
	}

	/**
	 * Where a tile has sea: whether any lies in the band its rivers run in, and if none does, the height of the node of
	 * each side where some lies at the side, or NaN.
	 */
	private static final class Sea extends Used {
		final boolean band;
		final float[] node;

		Sea(boolean band, float[] node) {
			this.band = band;
			this.node = node;
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
		final Shore shore = new Shore();
		WorldScale scale;
		int chunkX;
		int chunkZ;
		Cell cell;
		int cellI;
		int cellJ;
		/** Whether the thread is computing one of these columns. */
		boolean busy;
		/** The columns for questions asked while {@link #busy}, made when first needed. */
		private ChunkColumns nested;

		ChunkColumns() {
			for (int k = 0; k < columns.length; k++) {
				columns[k] = new RiverColumn();
			}
			for (int k = 0; k < raw.length; k++) {
				raw[k] = new RiverColumn();
			}
		}

		ChunkColumns nested() {
			if (nested == null) {
				nested = new ChunkColumns();
			}
			return nested;
		}
	}
}
