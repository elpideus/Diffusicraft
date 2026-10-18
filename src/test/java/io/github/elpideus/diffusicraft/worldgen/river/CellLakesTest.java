package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import org.junit.jupiter.api.Test;

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

	@Test
	void aBowlFillsToItsRim() {
		CellLakes lakes = CellLakes.build(bowl(128, 128, 20f, 10f), CellRivers.EMPTY, WET, 3, -2);
		assertEquals(1, lakes.lakeCount());
		assertEquals(100f, lakes.level(128, 128), 0f);
		assertEquals(100f, lakes.level(128 + 19, 128), 0f);
		assertTrue(Float.isNaN(lakes.level(128 + 21, 128)), "the plateau is dry");
		assertTrue(Float.isNaN(lakes.level(0, 0)));
	}

	@Test
	void aDryClimateFillsItLessOrNotAtAll() {
		float[] tile = bowl(128, 128, 20f, 10f);
		CellLakes half = CellLakes.build(tile, CellRivers.EMPTY, (i, j) -> 0.8f, 0, 0);
		assertEquals(95f, half.level(128, 128), 1e-4f);
		assertTrue(Float.isNaN(half.level(128 + 19, 128)), "the shallow rim of the bowl is dry");
		assertTrue(CellLakes.build(tile, CellRivers.EMPTY, (i, j) -> 0.3f, 0, 0).isEmpty(), "a desert has no lake");
	}

	@Test
	void theClimateIsReadAtTheBottomOfTheBasin() {
		int[] at = new int[2];
		CellLakes.build(bowl(100, 60, 20f, 10f), CellRivers.EMPTY, (i, j) -> {
			at[0] = i;
			at[1] = j;
			return 2f;
		}, 2, 3);
		assertEquals(2 * TILE + 100, at[0]);
		assertEquals(3 * TILE + 60, at[1]);
	}

	@Test
	void shallowAndEdgeBasinsHoldNoLake() {
		assertTrue(CellLakes.build(bowl(128, 128, 20f, 0.5f), CellRivers.EMPTY, WET, 0, 0).isEmpty(), "too shallow");
		assertTrue(CellLakes.build(bowl(128, 128, 0.9f, 10f), CellRivers.EMPTY, WET, 0, 0).isEmpty(), "too small");
		// Lakes reaching into the tile's margin would need the neighbouring tile to decide their columns.
		assertTrue(CellLakes.build(bowl(10, 128, 20f, 10f), CellRivers.EMPTY, WET, 0, 0).isEmpty(), "on the edge");
		assertTrue(CellLakes.build(bowl(TILE - 1, 128, 20f, 10f), CellRivers.EMPTY, WET, 0, 0).isEmpty());
	}

	@Test
	void basinsAtOrBelowTheSeaHoldNoLake() {
		float[] tile = bowl(128, 128, 20f, 10f);
		for (int k = 0; k < tile.length; k++) {
			tile[k] -= 100f;
		}
		assertTrue(CellLakes.build(tile, CellRivers.EMPTY, WET, 0, 0).isEmpty());
	}

	@Test
	void eachBasinIsItsOwnLake() {
		float[] tile = bowl(80, 80, 15f, 10f);
		float[] other = bowl(180, 170, 25f, 30f);
		for (int k = 0; k < tile.length; k++) {
			tile[k] = Math.min(tile[k], other[k]);
		}
		CellLakes lakes = CellLakes.build(tile, CellRivers.EMPTY, WET, 0, 0);
		assertEquals(2, lakes.lakeCount());
		assertEquals(100f, lakes.level(80, 80), 0f);
		assertEquals(100f, lakes.level(180, 170), 0f);
	}
}
