package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainPrefetcher;
import io.github.elpideus.diffusicraft.worldgen.TerrainSource;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreatLakeTest {
	private static final int TILE = TerrainField.TILE;
	/** Centre of the bowl in cells: on a slope near the ridge between two valleys, by the border of two regions. */
	private static final double CENTRE_I = 6.5;
	private static final double CENTRE_J = 0.3;
	/** Radius of the bowl in cells, and its depth in meters. */
	private static final double RADIUS = 2.0;
	private static final double DEPTH = 500.0;

	/** The valleys of {@link RiversTest} with a round bowl sunk into the slope, on the fine terrain and the coarse map. */
	static class BowlSource extends RiversTest.ValleySource {
		@Override
		float height(double i, double j) {
			double r = Math.hypot(i / TILE - CENTRE_I, j / TILE - CENTRE_J) / RADIUS;
			double bowl = r < 1.0 ? DEPTH * (1.0 - r * r) * (1.0 - r * r) : 0.0;
			return (float) (elevation(i, j) - bowl);
		}
	}

	private static TerrainField field() {
		return new TerrainField(1, new BowlSource());
	}

	@Test
	void theBowlHoldsALakeThatEveryRegionSeesTheSame() {
		RiverNetwork network = new RiverNetwork(field());
		// Cells on either side of the border between regions, computed apart.
		RiverNetwork.Lake west = network.lakeAt(6, -1), east = network.lakeAt(6, 0);
		assertNotNull(west, "a lake west of the border");
		assertNotNull(east, "a lake east of the border");
		assertEquals(west.id(), east.id());
		assertEquals(west.level(), east.level(), 0f);
		assertArrayEquals(west.cells(), east.cells());
		assertEquals(west.outI(), east.outI());
		assertEquals(west.outJ(), east.outJ());
		assertEquals(west.exitI(), east.exitI());
		assertEquals(west.exitJ(), east.exitJ());

		int[] cells = east.cells();
		assertTrue(cells.length / 2 >= 3 && cells.length / 2 <= RiverNetwork.MAX_LAKE_CELLS, "cells: " + cells.length / 2);
		for (int c = 0; c < cells.length; c += 2) {
			RiverNetwork.Lake here = network.lakeAt(cells[c], cells[c + 1]);
			assertNotNull(here);
			assertEquals(east.id(), here.id());
			assertEquals(east.level(), network.level(cells[c], cells[c + 1]), 0f, "the lake is level");
		}
		assertNull(network.lakeAt(east.exitI(), east.exitJ()), "the lake spills out of itself");
		assertTrue(network.level(east.exitI(), east.exitJ()) < east.level(), "the lake spills downhill");
		// Lakes never touch another lake's cells.
		for (int ci = 0; ci < 12; ci++) {
			for (int cj = -9; cj < 9; cj++) {
				RiverNetwork.Lake lake = network.lakeAt(ci, cj);
				assertTrue(lake == null || lake.id() == east.id(), "one lake, another at " + ci + ", " + cj);
			}
		}
	}

	@Test
	void waterCrossesTheLakeAndLeavesThroughItsOutlet() {
		RiverNetwork network = new RiverNetwork(field());
		RiverNetwork.Lake lake = network.lakeAt(6, 0);
		assertNotNull(lake);
		int[] cells = lake.cells();
		for (int c = 0; c < cells.length; c += 2) {
			int i = cells[c], j = cells[c + 1];
			for (int steps = 0; network.lakeAt(i, j) != null; steps++) {
				assertTrue(steps <= cells.length / 2, "the water leaves the lake from " + cells[c] + ", " + cells[c + 1]);
				int d = network.direction(i, j);
				assertTrue(d >= 0, "lake cells drain");
				if (i == lake.outI() && j == lake.outJ()) {
					assertEquals(lake.exitI(), i + RiverNetwork.DI[d]);
					assertEquals(lake.exitJ(), j + RiverNetwork.DJ[d]);
				}
				i += RiverNetwork.DI[d];
				j += RiverNetwork.DJ[d];
			}
			assertEquals(lake.exitI(), i, "every cell drains through the outlet");
			assertEquals(lake.exitJ(), j);
		}
		// The lake gathers the runoff of all its cells and more, and carries a river out.
		float runoff = RiverNetwork.runoff(10f, 1500f);
		assertTrue(network.discharge(lake.outI(), lake.outJ()) > runoff * cells.length / 2);
		assertTrue(network.discharge(lake.exitI(), lake.exitJ()) >= RiverNetwork.MIN_DISCHARGE);
		for (int ci = 0; ci < 12; ci++) {
			for (int cj = -9; cj < 9; cj++) {
				assertNotEquals(RiverNetwork.PIT, network.direction(ci, cj), "a pit at " + ci + ", " + cj);
			}
		}
	}

	@Test
	void riversCrossTheLakeAtTheLevelOfItsWater() {
		RiverNetwork network = new RiverNetwork(field());
		RiverNetwork.Lake lake = network.lakeAt(6, 0);
		GreatLake great = network.greatLake(lake);
		float water = great.water();
		assertFalse(great.isEmpty(), "the bowl holds water");
		assertTrue(water >= Math.max(0f, network.level(lake.exitI(), lake.exitJ())) && water <= lake.level(),
			"water " + water + " between the exit and the spill " + lake.level());
		int inLake = 0;
		for (int ci = 1; ci < 11; ci++) {
			for (int cj = -6; cj < 6; cj++) {
				CellFlows a = network.flows(ci, cj);
				for (int side = 0; side < 4; side++) {
					CellFlows b = network.flows(ci + RiverNetwork.DI[side], cj + RiverNetwork.DJ[side]);
					assertEquals(a.outLevel[side], b.inLevel[side ^ 1], 0f, "level at " + ci + "," + cj + " side " + side);
					assertEquals(a.outDischarge[side], b.inDischarge[side ^ 1], 0f);
				}
				for (CellFlows.Inflow inflow : a.inflows) {
					if (inflow.target() >= 0 && inflow.target() < 4) {
						assertTrue(a.outLevel[inflow.target()] <= a.inLevel[inflow.side()],
							"the river climbs at " + ci + ", " + cj);
					}
				}
				RiverNetwork.Lake here = network.lakeAt(ci, cj);
				if (here != null) {
					for (int side = 0; side < 4; side++) {
						if (!Float.isNaN(a.outLevel[side])) {
							assertEquals(water, a.outLevel[side], 0f, "rivers leave lake cells at its water");
							inLake++;
						}
						assertFalse(a.inLevel[side] < water, "rivers enter the lake from above its water");
					}
				}
			}
		}
		assertTrue(inLake > 0, "a river crosses the lake");
	}

	@Test
	void theLakeFillsTheBowlOnEveryTile() {
		TerrainField field = field();
		RiverNetwork.Lake lake = new RiverNetwork(field).lakeAt(6, 0);
		float water = new RiverNetwork(field).greatLake(lake).water();
		Rivers rivers = Rivers.of(field);
		int[] cells = lake.cells();
		int under = 0;
		for (int c = 0; c < cells.length; c += 2) {
			CellLakes lakes = rivers.lakes(cells[c], cells[c + 1]);
			for (int i = 0; i < TILE; i++) {
				for (int j = 0; j < TILE; j++) {
					int run = lakes.run(i, j);
					if (run >= 0 && lakes.runLevel(run) == water) {
						assertTrue(lakes.runFed(run), "the lake is fed");
						under++;
					}
				}
			}
		}
		assertTrue(under > TILE * TILE / 4, "the lake covers the bowl, pixels: " + under);
		// Tiles agree on the water along their sides, in and around the lake.
		int m = CellLakes.MARGIN, wet = 0;
		for (int ti = 3; ti < 9; ti++) {
			for (int tj = -3; tj < 3; tj++) {
				CellLakes here = rivers.lakes(ti, tj), east = rivers.lakes(ti, tj + 1), south = rivers.lakes(ti + 1, tj);
				for (int a = -m; a < TILE + m; a++) {
					for (int d = -m; d < m; d++) {
						wet += agree(here, a, TILE + d, east, a, d) + agree(here, TILE + d, a, south, d, a);
					}
				}
			}
		}
		assertTrue(wet > 0, "the lake reaches across the sides of its tiles");
		// In the world: water over the bottom of the bowl, held by its shores.
		for (WorldScale scale : WorldScale.values()) {
			int x = (int) (CENTRE_J * TILE / scale.pixelsPerBlock()), z = (int) (CENTRE_I * TILE / scale.pixelsPerBlock());
			RiverColumn c = rivers.column(scale, x, z);
			assertTrue(c.lake && c.channel && c.bed < c.waterTop, "water in the bowl at " + scale);
		}
	}

	/** Asserts that two tiles see the same water at one pixel; returns 1 if there is some. */
	private static int agree(CellLakes a, int ai, int aj, CellLakes b, int bi, int bj) {
		int ra = a.run(ai, aj), rb = b.run(bi, bj);
		assertEquals(ra < 0, rb < 0, "water at " + ai + ", " + aj);
		if (ra < 0) {
			return 0;
		}
		assertEquals(a.runLevel(ra), b.runLevel(rb), 0f);
		assertEquals(a.runFed(ra), b.runFed(rb));
		return 1;
	}

	@Test
	void aDryBowlHoldsNoLake() {
		TerrainSource source = new BowlSource() {
			@Override
			public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
				float[] out = super.coarse(ci0, cj0, ci1, cj1);
				int plane = (ci1 - ci0) * (cj1 - cj0);
				Arrays.fill(out, COARSE_PRECIPITATION * plane, (COARSE_PRECIPITATION + 1) * plane, 100f);
				return out;
			}
		};
		RiverNetwork network = new RiverNetwork(new TerrainField(1, source));
		assertNull(network.lakeAt(6, 0), "evaporation outweighs the runoff");
		assertNotEquals(RiverNetwork.PIT, network.direction(6, 0), "the bowl is breached instead");
	}

	@Test
	void sameLakeOnEveryThread() throws Exception {
		WorldScale scale = WorldScale.MEDIUM;
		int blocksPerTile = (int) Math.round(TILE / scale.pixelsPerBlock());
		List<int[]> points = new ArrayList<>();
		for (int k = 0; k < 400; k++) {
			points.add(new int[]{-blocksPerTile + (k * 37) % (3 * blocksPerTile),
				5 * blocksPerTile + (k * 53) % (2 * blocksPerTile)});
		}
		Rivers serial = Rivers.of(field());
		Rivers parallel = Rivers.of(field());
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<RiverColumn>> results = new ArrayList<>();
			for (int[] p : points) {
				results.add(pool.submit(() -> copy(parallel.column(scale, p[0], p[1]))));
			}
			int lakes = 0;
			for (int k = 0; k < points.size(); k++) {
				RiverColumn expected = serial.column(scale, points.get(k)[0], points.get(k)[1]);
				RiverColumn actual = results.get(k).get();
				assertEquals(expected.lake, actual.lake);
				assertEquals(expected.channel, actual.channel);
				if (actual.channel) {
					assertEquals(expected.waterTop, actual.waterTop);
					assertEquals(expected.bed, actual.bed);
				}
				lakes += actual.lake ? 1 : 0;
			}
			assertTrue(lakes > 20, "points in the lake: " + lakes);
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void thePrefetcherComputesEveryTileOfALakeInItsPlanAndItsWater() throws Exception {
		TerrainField field = field();
		int[] cells = Rivers.of(field).lakeTiles(6, 0);
		assertTrue(cells.length > 0, "the bowl holds a lake");
		assertEquals(0, Rivers.of(field).lakeTiles(0, 6).length, "no lake on dry land");
		// A still player on the lake's tile with the farthest other tiles, which its plan of 3 by 3 tiles misses.
		int at = 0, reach = 0;
		for (int c = 0; c < cells.length; c += 2) {
			int far = 0;
			for (int d = 0; d < cells.length; d += 2) {
				far = Math.max(far, Math.max(Math.abs(cells[d] - cells[c]), Math.abs(cells[d + 1] - cells[c + 1])));
			}
			if (far > reach) {
				at = c;
				reach = far;
			}
		}
		assertTrue(reach >= 2, "the lake reaches past the plan");
		WorldScale scale = WorldScale.MEDIUM;
		double blocks = TILE / scale.pixelsPerBlock();
		try (TerrainPrefetcher prefetcher = new TerrainPrefetcher()) {
			prefetcher.update(List.of(new TerrainPrefetcher.Traveller(field, scale, (cells[at + 1] + 0.5) * blocks,
				(cells[at] + 0.5) * blocks, 0, 0, 16)));
			long deadline = System.nanoTime() + 60_000_000_000L;
			for (int c = 0; c < cells.length; c += 2) {
				while (!field.hasTile(cells[c], cells[c + 1]) && System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				assertTrue(field.hasTile(cells[c], cells[c + 1]), "lake tile " + cells[c] + ", " + cells[c + 1]);
			}
			Rivers rivers = Rivers.of(field);
			while (!rivers.hasGreatLake(cells[at], cells[at + 1]) && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertTrue(rivers.hasGreatLake(cells[at], cells[at + 1]), "the lake's water");
		}
	}

	private static RiverColumn copy(RiverColumn c) {
		RiverColumn out = new RiverColumn();
		out.lake = c.lake;
		out.channel = c.channel;
		out.waterTop = c.waterTop;
		out.bed = c.bed;
		return out;
	}
}
