package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLakesTest {
	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	private static final CellLakes.Climate WET = (i, j) -> 2f;
	/** A climate that fills a basin half way up. */
	private static final CellLakes.Climate HALF = (i, j) -> 0.8f;
	private static final long SEED = 1L;

	/** Elevation at a pixel of the world. */
	interface Terrain {
		float at(int i, int j);
	}

	/** A plateau at 100 m with a round bowl {@code depth} meters deep and {@code radius} pixels across. */
	private static Terrain bowl(int ci, int cj, float radius, float depth) {
		return (i, j) -> {
			float di = i - ci, dj = j - cj;
			return 100f - depth * Math.max(0f, 1f - (di * di + dj * dj) / (radius * radius));
		};
	}

	private static float[] paddedTile(Terrain terrain, int ti, int tj) {
		float[] tile = new float[SPAN * SPAN];
		for (int k = 0; k < tile.length; k++) {
			tile[k] = terrain.at(ti * TILE + k / SPAN - PAD, tj * TILE + k % SPAN - PAD);
		}
		return tile;
	}

	/** Which pixels of the world lie in a valley the ice drowned. */
	interface Drowned {
		boolean at(int i, int j);
	}

	private static long[] paddedDrowned(Drowned drowned, int ti, int tj) {
		if (drowned == null) {
			return null;
		}
		long[] mask = new long[(SPAN * SPAN + 63) >> 6];
		for (int k = 0; k < SPAN * SPAN; k++) {
			if (drowned.at(ti * TILE + k / SPAN - PAD, tj * TILE + k % SPAN - PAD)) {
				mask[k >> 6] |= 1L << k;
			}
		}
		return mask;
	}

	/** Where rivers cross the sides of each tile; none by default. */
	interface Flows {
		CellFlows of(int ti, int tj);
	}

	/** The blocks built so far, by everything they are built from: each tile of a test asks for the same ones. */
	private static final Map<List<Object>, BlockLakes> BLOCKS = new ConcurrentHashMap<>();

	/** Block {@code (bi, bj)} of {@code n} by {@code n} tiles. */
	private static BlockLakes block(int n, Terrain terrain, Drowned drowned, Flows flows, CellLakes.Climate climate,
									int bi, int bj) {
		return BLOCKS.computeIfAbsent(List.of(n, terrain, Optional.ofNullable(drowned), flows, climate, bi, bj),
			key -> build(n, terrain, drowned, flows, climate, bi, bj));
	}

	private static BlockLakes build(int n, Terrain terrain, Drowned drowned, Flows flows, CellLakes.Climate climate,
									int bi, int bj) {
		float[][] tiles = new float[n * n][];
		long[][] masks = new long[n * n][];
		CellFlows[] crossings = new CellFlows[n * n];
		for (int t = 0; t < n * n; t++) {
			tiles[t] = paddedTile(terrain, bi + t / n, bj + t % n);
			masks[t] = paddedDrowned(drowned, bi + t / n, bj + t % n);
			crossings[t] = flows.of(bi + t / n, bj + t % n);
		}
		IntFunction<CellRivers> rivers = t -> CellRivers.build(tiles[t], CellBasins.of(tiles[t], masks[t], null),
			crossings[t], SEED, bi + t / n, bj + t % n, null);
		return BlockLakes.build(n, tiles, masks, crossings, rivers, climate, bi, bj);
	}

	private static BlockLakes block(int n, Terrain terrain, Flows flows, int bi, int bj) {
		return block(n, terrain, null, flows, WET, bi, bj);
	}

	/** The lakes of a tile without rivers of its own, over the blocks around it, as Rivers puts them together. */
	private static CellLakes lakes(Terrain terrain, Drowned drowned, Flows flows, CellLakes.Climate climate, int ti,
								   int tj, float[] raise) {
		float[] tile = paddedTile(terrain, ti, tj);
		float[] level = new float[CellLakes.SIDE * CellLakes.SIDE];
		boolean[] fed = new boolean[level.length];
		Arrays.fill(level, Float.NaN);
		Arrays.fill(raise, Float.NaN);
		for (int n = 2; n <= Rivers.RING + 1; n++) {
			for (int oi = 0; oi < n; oi++) {
				for (int oj = 0; oj < n; oj++) {
					block(n, terrain, drowned, flows, climate, ti - oi, tj - oj)
						.paint(oi, oj, CellLakes.MARGIN, level, fed, raise);
				}
			}
		}
		CellBasins basins = CellBasins.of(tile, paddedDrowned(drowned, ti, tj), raise);
		return CellLakes.build(tile, basins, null, CellRivers.EMPTY, climate, ti, tj, level, fed);
	}

	private static CellLakes lakes(Terrain terrain, Flows flows, CellLakes.Climate climate, int ti, int tj,
								   float[] raise) {
		return lakes(terrain, null, flows, climate, ti, tj, raise);
	}

	private static CellLakes lakes(Terrain terrain, Flows flows, int ti, int tj, float[] raise) {
		return lakes(terrain, flows, WET, ti, tj, raise);
	}

	private static CellLakes lakes(Terrain terrain, int ti, int tj) {
		return lakes(terrain, (i, j) -> CellFlows.NONE, ti, tj, new float[SPAN * SPAN]);
	}

	/** Asserts that two tiles see the same water over every pixel both of them know. */
	private static void assertAgree(CellLakes a, int ai, int aj, CellLakes b, int bi, int bj) {
		int di = (bi - ai) * TILE, dj = (bj - aj) * TILE, m = CellLakes.MARGIN;
		for (int i = -m; i < TILE + m; i++) {
			for (int j = -m; j < TILE + m; j++) {
				int oi = i - di, oj = j - dj;
				if (oi < -m || oj < -m || oi >= TILE + m || oj >= TILE + m) {
					continue;
				}
				int ra = a.run(i, j), rb = b.run(oi, oj);
				assertEquals(ra < 0, rb < 0, "water at " + i + ", " + j);
				if (ra >= 0) {
					assertEquals(a.runLevel(ra), b.runLevel(rb), 0f);
					assertEquals(a.runFed(ra), b.runFed(rb));
				}
			}
		}
	}

	@Test
	void aBowlAcrossASideFillsOnBothSides() {
		Terrain terrain = bowl(128, TILE, 20f, 10f);
		CellLakes west = lakes(terrain, 0, 0), east = lakes(terrain, 0, 1);
		assertEquals(0, west.lakeCount(), "the tile's own basins leave it out");
		assertEquals(100f, west.level(128, TILE - 1), 0f);
		assertEquals(100f, west.level(128, TILE - 19), 0f);
		assertEquals(100f, east.level(128, 0), 0f);
		assertEquals(100f, east.level(128, 18), 0f);
		assertTrue(Float.isNaN(east.level(128, 22)), "the plateau is dry");
		assertEquals(100f, west.level(128, TILE + 1), 0f, "a tile sees the lake beside it");
		assertAgree(west, 0, 0, east, 0, 1);
	}

	@Test
	void aBowlWiderThanATileFillsTheFourTilesAroundACorner() {
		Terrain terrain = bowl(TILE, TILE, 200f, 30f);
		CellLakes[] tiles = new CellLakes[4];
		for (int t = 0; t < 4; t++) {
			tiles[t] = lakes(terrain, t >> 1, t & 1);
			assertEquals(100f, tiles[t].level(TILE / 2, TILE / 2), 0f);
		}
		for (int a = 0; a < 4; a++) {
			for (int b = a + 1; b < 4; b++) {
				assertAgree(tiles[a], a >> 1, a & 1, tiles[b], b >> 1, b & 1);
			}
		}
	}

	@Test
	void aBowlAtTheSideOfATileFillsAndTheTileBesideItSeesItsShore() {
		// It reaches two pixels from the side without crossing it: too near the side for the tile alone to fill it.
		Terrain terrain = bowl(128, TILE - 6, 5f, 10f);
		CellLakes west = lakes(terrain, 0, 0), east = lakes(terrain, 0, 1);
		assertEquals(0, west.lakeCount());
		assertEquals(100f, west.level(128, TILE - 2), 0f);
		assertTrue(Float.isNaN(west.level(128, TILE - 1)));
		assertEquals(100f, east.level(128, -2), 0f);
		assertAgree(west, 0, 0, east, 0, 1);
	}

	@Test
	void aBowlWiderThanTwoTilesFillsTheNineTilesItCrosses() {
		// From pixel 164 to 604 each way: across tiles 0 to 2, which only the block of three by three tiles at (0, 0)
		// holds.
		Terrain terrain = bowl(384, 384, 220f, 30f);
		for (int bi = -1; bi <= 1; bi++) {
			for (int bj = -1; bj <= 1; bj++) {
				assertTrue(block(2, terrain, (i, j) -> CellFlows.NONE, bi, bj).isEmpty());
			}
		}
		CellLakes[] tiles = new CellLakes[9];
		for (int t = 0; t < 9; t++) {
			tiles[t] = lakes(terrain, t / 3, t % 3);
			assertEquals(0, tiles[t].lakeCount(), "the tile's own basins leave it out");
		}
		assertEquals(100f, tiles[4].level(TILE / 2, TILE / 2), 0f);
		assertEquals(100f, tiles[0].level(TILE - 1, TILE - 1), 0f);
		assertTrue(Float.isNaN(tiles[0].level(10, 10)), "the plateau is dry");
		for (int a = 0; a < 9; a++) {
			for (int b = a + 1; b < 9; b++) {
				assertAgree(tiles[a], a / 3, a % 3, tiles[b], b / 3, b % 3);
			}
		}
	}

	@Test
	void aBowlAcrossFourTilesStaysDry() {
		Terrain terrain = bowl(TILE, TILE, 320f, 50f);
		for (int t = 0; t < 4; t++) {
			assertTrue(lakes(terrain, t >> 1, t & 1).isEmpty());
		}
	}

	@Test
	void aValleyDrownedBehindAStepAcrossASideHoldsALakeAboveTheSea() {
		// The sea north of row 40, and a trough 150 m below it that the ice drowned, across the side between tiles
		// (0, 0) and (0, 1), cut off from the sea by a step 20 m above it.
		Terrain terrain = (i, j) -> i < 40 ? -100f : i < 200 && j >= 200 && j < 320 ? i < 50 ? 20f : -150f : 200f;
		Drowned drowned = (i, j) -> i >= 40 && terrain.at(i, j) < 0f;
		Flows none = (i, j) -> CellFlows.NONE;
		CellLakes west = lakes(terrain, drowned, none, WET, 0, 0, new float[SPAN * SPAN]);
		CellLakes east = lakes(terrain, drowned, none, WET, 0, 1, new float[SPAN * SPAN]);
		assertEquals(20f, west.level(120, TILE - 1), 0f);
		assertEquals(20f, west.level(120, 200), 0f);
		assertEquals(20f, east.level(120, 0), 0f);
		assertEquals(20f, east.level(120, 320 - TILE - 1), 0f);
		assertTrue(Float.isNaN(west.level(45, 250)), "the step is dry");
		assertAgree(west, 0, 0, east, 0, 1);
		assertTrue(lakes(terrain, 0, 0).isEmpty(), "where the ice drowned nothing, all ground below the sea is sea");
	}

	@Test
	void aBowlInsideOneTileIsLeftToTheTile() {
		Terrain terrain = bowl(128, 128, 20f, 10f);
		for (int bi = -1; bi <= 0; bi++) {
			for (int bj = -1; bj <= 0; bj++) {
				assertTrue(block(2, terrain, (i, j) -> CellFlows.NONE, bi, bj).isEmpty());
			}
		}
		for (int bi = -2; bi <= 0; bi++) {
			for (int bj = -2; bj <= 0; bj++) {
				assertTrue(block(3, terrain, (i, j) -> CellFlows.NONE, bi, bj).isEmpty());
			}
		}
		CellLakes lakes = lakes(terrain, 0, 0);
		assertEquals(1, lakes.lakeCount());
		assertEquals(100f, lakes.level(128, 128), 0f);
	}

	/** A river that leaves tile (0, 0) east at {@code level} and runs on east across (0, 1). */
	private static Flows riverEast(float level) {
		return (ti, tj) -> {
			if (ti != 0 || tj != 0 && tj != 1) {
				return CellFlows.NONE;
			}
			CellFlows flows = new CellFlows();
			if (tj == 0) {
				flows.addOut(3, level, 10f);
			} else {
				flows.addIn(2, 3, level, 10f);
				flows.addOut(3, 50f, 10f);
			}
			return flows;
		};
	}

	@Test
	void aRiverAcrossTheSideFillsTheBowlBelowItsRim() {
		Terrain terrain = bowl(128, TILE, 20f, 10f);
		float[] raise = new float[SPAN * SPAN];
		CellLakes west = lakes(terrain, riverEast(120f), 0, 0, raise);
		assertEquals(100f - CellRivers.DROP, west.level(128, TILE - 1), 0f);
		assertTrue(west.runFed(west.run(128, TILE - 1)));
		assertEquals(100f, raise[(128 + PAD) * SPAN + TILE - 1 + PAD], 0f, "the river runs over the water");
		assertTrue(Float.isNaN(raise[(10 + PAD) * SPAN + 10 + PAD]));
		assertAgree(west, 0, 0, lakes(terrain, riverEast(120f), 0, 1, new float[SPAN * SPAN]), 0, 1);
	}

	@Test
	void aLowRiverHoldsTheBowlAtItsOwnLevel() {
		Terrain terrain = bowl(128, TILE, 20f, 10f);
		CellLakes west = lakes(terrain, riverEast(96f), 0, 0, new float[SPAN * SPAN]);
		assertEquals(96f, west.level(128, TILE - 1), 0f);
		assertTrue(lakes(terrain, riverEast(90.5f), 0, 0, new float[SPAN * SPAN]).isEmpty(),
			"a river below the bottom leaves it dry");
	}

	@Test
	void aTileDrawsItsRiversAlongTheSamePathsOverTheLakesOfTheBlocksAround() {
		// The river fills the bowl across the side and runs on over it into tile (0, 1), whose rivers the blocks draw
		// without the bowl's water.
		Terrain terrain = bowl(128, TILE, 50f, 10f);
		Flows flows = riverEast(120f);
		float[] raise = new float[SPAN * SPAN];
		assertEquals(100f - CellRivers.DROP, lakes(terrain, flows, 0, 1, raise).level(128, 40), 0f);
		float[] tile = paddedTile(terrain, 0, 1);
		CellRivers over = CellRivers.build(tile, CellBasins.of(tile, raise), flows.of(0, 1), SEED, 0, 1, null);
		CellRivers alone = CellRivers.build(tile, CellBasins.of(tile), flows.of(0, 1), SEED, 0, 1, null);
		CellRivers.Hit a = new CellRivers.Hit(), b = new CellRivers.Hit();
		boolean higher = false;
		for (int i = 0; i < TILE; i++) {
			for (int j = 0; j < TILE; j++) {
				boolean near = over.nearest(i + 0.5, j + 0.5, CellLakes.WIDEST, a);
				assertEquals(near, alone.nearest(i + 0.5, j + 0.5, CellLakes.WIDEST, b), "river at " + i + ", " + j);
				if (near) {
					assertEquals(a.distance, b.distance, 0f);
					higher |= a.level > b.level;
				}
			}
		}
		assertTrue(higher, "over the water, the river runs higher");
	}

	/**
	 * A plateau at 100 m with a bowl 10 m deep and 30 pixels across the side between tiles (0, 0) and (0, 1), and a
	 * valley 5 m deep down tile (0, 1) at tile-local column {@code column}, which carries the river of {@link #DOWN}.
	 */
	private static Terrain bowlBesideAValley(int column) {
		Terrain bowl = bowl(128, TILE + 10, 30f, 10f);
		return (i, j) -> Math.abs(j - TILE - column) <= 1 ? 95f : bowl.at(i, j);
	}

	/** A river down tile (0, 1), from its north side to its south side. */
	private static final Flows DOWN = (ti, tj) -> {
		if (ti != 0 || tj != 1) {
			return CellFlows.NONE;
		}
		CellFlows flows = new CellFlows();
		flows.addIn(0, 1, 90f, 10f);
		flows.addOut(1, 85f, 10f);
		return flows;
	};

	@Test
	void aRiverWithinReachOfTheWaterKeepsTheRainOut() {
		// The full bowl reaches column 39 of tile (0, 1), within reach of the river; the plateau keeps it apart.
		assertTrue(lakes(bowlBesideAValley(67), DOWN, 0, 0, new float[SPAN * SPAN]).isEmpty());
		CellLakes west = lakes(bowlBesideAValley(150), DOWN, 0, 0, new float[SPAN * SPAN]);
		assertEquals(100f, west.level(128, TILE - 1), 0f, "a river elsewhere in the tile leaves the rain");
		assertFalse(west.runFed(west.run(128, TILE - 1)));
	}

	@Test
	void aRiverWithinReachOfOnlyTheShoreLeavesTheRain() {
		// Half full, the bowl reaches column 31 of tile (0, 1), out of the river's reach; its shore does not.
		Terrain terrain = bowlBesideAValley(67);
		CellLakes west = lakes(terrain, DOWN, HALF, 0, 0, new float[SPAN * SPAN]);
		assertEquals(95f, west.level(128, TILE - 1), 0f);
		assertAgree(west, 0, 0, lakes(terrain, DOWN, HALF, 0, 1, new float[SPAN * SPAN]), 0, 1);
	}
}
