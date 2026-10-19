package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellLakesTest {
	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	private static final CellLakes.Climate WET = (i, j) -> 2f;

	/**
	 * A plateau at 100 m with a round bowl {@code depth} meters deep and {@code radius} pixels across, centred on
	 * tile-local pixel {@code (ci, cj)}.
	 */
	private static float[] bowl(int ci, int cj, float radius, float depth) {
		float[] tile = new float[SPAN * SPAN];
		for (int k = 0; k < tile.length; k++) {
			int di = k / SPAN - PAD - ci, dj = k % SPAN - PAD - cj;
			float r2 = (di * di + dj * dj) / (radius * radius);
			tile[k] = 100f - depth * Math.max(0f, 1f - r2);
		}
		return tile;
	}

	/** The lakes of a tile without rivers. */
	private static CellLakes lakes(float[] tile, CellLakes.Climate climate, int ti, int tj) {
		return CellLakes.build(tile, CellBasins.of(tile), null, CellRivers.EMPTY, climate, ti, tj);
	}

	@Test
	void aBowlFillsToItsRim() {
		CellLakes lakes = lakes(bowl(128, 128, 20f, 10f), WET, 3, -2);
		assertEquals(1, lakes.lakeCount());
		assertEquals(100f, lakes.level(128, 128), 0f);
		assertEquals(100f, lakes.level(128 + 19, 128), 0f);
		assertTrue(Float.isNaN(lakes.level(128 + 21, 128)), "the plateau is dry");
		assertTrue(Float.isNaN(lakes.level(0, 0)));
	}

	@Test
	void aDryClimateFillsItLessOrNotAtAll() {
		float[] tile = bowl(128, 128, 20f, 10f);
		CellLakes half = lakes(tile, (i, j) -> 0.8f, 0, 0);
		assertEquals(95f, half.level(128, 128), 1e-4f);
		assertTrue(Float.isNaN(half.level(128 + 19, 128)), "the shallow rim of the bowl is dry");
		assertTrue(lakes(tile, (i, j) -> 0.3f, 0, 0).isEmpty(), "a desert has no lake");
	}

	@Test
	void theClimateIsReadAtTheBottomOfTheBasin() {
		int[] at = new int[2];
		lakes(bowl(100, 60, 20f, 10f), (i, j) -> {
			at[0] = i;
			at[1] = j;
			return 2f;
		}, 2, 3);
		assertEquals(2 * TILE + 100, at[0]);
		assertEquals(3 * TILE + 60, at[1]);
	}

	@Test
	void shallowAndEdgeBasinsHoldNoLake() {
		assertTrue(lakes(bowl(128, 128, 20f, 0.5f), WET, 0, 0).isEmpty(), "too shallow");
		assertTrue(lakes(bowl(128, 128, 0.9f, 10f), WET, 0, 0).isEmpty(), "too small");
		// Lakes reaching into the tile's margin would need the neighbouring tile to decide their columns.
		assertTrue(lakes(bowl(10, 128, 20f, 10f), WET, 0, 0).isEmpty(), "on the edge");
		assertTrue(lakes(bowl(TILE - 1, 128, 20f, 10f), WET, 0, 0).isEmpty());
	}

	@Test
	void basinsAtOrBelowTheSeaHoldNoLake() {
		float[] tile = bowl(128, 128, 20f, 10f);
		for (int k = 0; k < tile.length; k++) {
			tile[k] -= 100f;
		}
		assertTrue(lakes(tile, WET, 0, 0).isEmpty());
	}

	@Test
	void eachBasinIsItsOwnLake() {
		float[] tile = bowl(80, 80, 15f, 10f);
		float[] other = bowl(180, 170, 25f, 30f);
		for (int k = 0; k < tile.length; k++) {
			tile[k] = Math.min(tile[k], other[k]);
		}
		CellLakes lakes = lakes(tile, WET, 0, 0);
		assertEquals(2, lakes.lakeCount());
		assertEquals(100f, lakes.level(80, 80), 0f);
		assertEquals(100f, lakes.level(180, 170), 0f);
	}

	@Test
	void aRiverFillsTheBasinItCrossesAndFlowsOnOutOfIt() {
		// A valley falling 100 m from north to south, with a bowl 20 m deep in it and a river running down it.
		float[] tile = new float[SPAN * SPAN];
		for (int k = 0; k < tile.length; k++) {
			int di = k / SPAN - PAD - 128, dj = k % SPAN - PAD - 128;
			float r2 = (di * di + dj * dj) / (30f * 30f);
			tile[k] = 200f - 100f * (k / SPAN - PAD) / TILE + 0.2f * Math.abs(dj) - 20f * Math.max(0f, 1f - r2);
		}
		CellFlows flows = new CellFlows();
		flows.addIn(0, 1, 199f, 50f);
		flows.addOut(1, 99f, 50f);
		CellBasins basins = CellBasins.of(tile);
		float[] pathLevel = new float[tile.length];
		Arrays.fill(pathLevel, Float.NaN);
		CellRivers rivers = CellRivers.build(tile, basins, flows, 1L, 0, 0, pathLevel);
		CellLakes lakes = CellLakes.build(tile, basins, pathLevel, rivers, (i, j) -> 0f, 0, 0);

		float spill = basins.spill(basins.basin((128 + PAD) * SPAN + 128 + PAD));
		assertEquals(1, lakes.lakeCount());
		int run = lakes.run(128, 128);
		assertTrue(run >= 0 && lakes.runFed(run), "the river fills the bowl, even in a desert");
		assertEquals(spill - CellRivers.DROP, lakes.runLevel(run), 1e-3f, "just below its lowest point");
		// The river flows on out of the bowl and down the slope.
		CellRivers.Hit hit = new CellRivers.Hit();
		boolean below = false;
		for (int j = CellRivers.LO; j < CellRivers.HI; j++) {
			if (rivers.nearest(200.5, j + 0.5, discharge -> 1f, hit) && hit.distance < 1f) {
				below = true;
				assertTrue(hit.level < spill - CellRivers.DROP, "the river runs on downhill: " + hit.level);
			}
		}
		assertTrue(below, "the river leaves the bowl");
	}
}
