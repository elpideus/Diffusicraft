package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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

	private static BlockLakes block(Terrain terrain, Drowned drowned, Flows flows, CellLakes.Climate climate, int bi,
									int bj) {
		float[][] tiles = new float[4][];
		long[][] masks = new long[4][];
		CellFlows[] crossings = new CellFlows[4];
		for (int t = 0; t < 4; t++) {
			tiles[t] = paddedTile(terrain, bi + (t >> 1), bj + (t & 1));
			masks[t] = paddedDrowned(drowned, bi + (t >> 1), bj + (t & 1));
			crossings[t] = flows.of(bi + (t >> 1), bj + (t & 1));
		}
		return BlockLakes.build(tiles, masks, crossings, climate, bi, bj);
	}

	private static BlockLakes block(Terrain terrain, Flows flows, int bi, int bj) {
		return block(terrain, null, flows, WET, bi, bj);
	}

	/** The lakes of a tile without rivers of its own, over the blocks around it, as Rivers puts them together. */
	private static CellLakes lakes(Terrain terrain, Drowned drowned, Flows flows, CellLakes.Climate climate, int ti,
								   int tj, float[] raise) {
		float[] tile = paddedTile(terrain, ti, tj);
		float[] level = new float[CellLakes.SIDE * CellLakes.SIDE];
		boolean[] fed = new boolean[level.length];
		Arrays.fill(level, Float.NaN);
		Arrays.fill(raise, Float.NaN);
		for (int oi = 0; oi < 2; oi++) {
			for (int oj = 0; oj < 2; oj++) {
				block(terrain, drowned, flows, climate, ti - oi, tj - oj)
					.paint(oi, oj, CellLakes.MARGIN, level, fed, raise);
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
	void aBowlLargerThanABlockStaysDry() {
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
				assertTrue(block(terrain, (i, j) -> CellFlows.NONE, bi, bj).isEmpty());
			}
		}
		CellLakes lakes = lakes(terrain, 0, 0);
		assertEquals(1, lakes.lakeCount());
		assertEquals(100f, lakes.level(128, 128), 0f);
	}

	/** A river that leaves tile (0, 0) east at {@code level} and enters (0, 1). */
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
	void aRiverElsewhereInItsTilesKeepsTheRainOut() {
		Terrain terrain = bowl(128, TILE, 20f, 10f);
		Flows north = (ti, tj) -> {
			if (ti != 0 || tj != 1) {
				return CellFlows.NONE;
			}
			CellFlows flows = new CellFlows();
			flows.addOut(0, 50f, 10f);
			return flows;
		};
		assertTrue(lakes(terrain, north, 0, 0, new float[SPAN * SPAN]).isEmpty());
		Flows far = (ti, tj) -> {
			if (ti != 1 || tj != 1) {
				return CellFlows.NONE;
			}
			CellFlows flows = new CellFlows();
			flows.addOut(0, 50f, 10f);
			return flows;
		};
		assertFalse(lakes(terrain, far, 0, 0, new float[SPAN * SPAN]).isEmpty(), "a tile the lake does not reach");
	}

	@Test
	void aRiverOnlyTheShoreReachesLeavesTheRain() {
		// A bowl across the east side of tile (0, 0), whose water, half way up, stays inside it, and a river in the
		// tile east of it, which only the bowl's dry shore reaches.
		Terrain terrain = bowl(128, TILE - 26, 30f, 10f);
		Flows east = (ti, tj) -> {
			if (ti != 0 || tj != 1) {
				return CellFlows.NONE;
			}
			CellFlows flows = new CellFlows();
			flows.addOut(0, 50f, 10f);
			return flows;
		};
		CellLakes west = lakes(terrain, east, HALF, 0, 0, new float[SPAN * SPAN]);
		assertEquals(95f, west.level(128, TILE - 26), 0f);
		assertAgree(west, 0, 0, lakes(terrain, east, HALF, 0, 1, new float[SPAN * SPAN]), 0, 1);
	}
}
