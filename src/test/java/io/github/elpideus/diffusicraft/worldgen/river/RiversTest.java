package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSource;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiversTest {
	private static final int TILE = TerrainField.TILE;
	/** Coarse column of the valley floor. */
	private static final int VALLEY = 4;

	/**
	 * V-shaped valleys every {@value #RIDGES} cells, one of them along coarse column {@value #VALLEY}, that rise to the
	 * south (+i) from the sea at {@code i < 0} up to a ridge {@value #RIDGES} cells away, with rolling hills on top,
	 * and a wet climate. The coarse map follows the same formula. Every basin is finite.
	 */
	static class ValleySource implements TerrainSource {
		static final int RIDGES = 9;

		static float elevation(double i, double j) {
			double along = i / TILE, across = Math.abs(j / TILE - (VALLEY + 0.5)) % RIDGES;
			along = Math.min(along, 2.0 * RIDGES - along);
			across = Math.min(across, RIDGES - across);
			return (float) (100.0 * along + 60.0 * across + 15.0 * Math.sin(i / 17.0) * Math.cos(j / 13.0));
		}

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			int h = i1 - i0, w = j1 - j0, plane = h * w;
			float[] elevation = new float[plane];
			float[] climate = new float[TerrainSample.CLIMATE_CHANNELS * plane];
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					int px = r * w + c;
					elevation[px] = elevation(i0 + r + 0.5, j0 + c + 0.5);
					climate[TerrainSample.TEMPERATURE * plane + px] = 10f;
					climate[TerrainSample.TEMPERATURE_SEASONALITY * plane + px] = 600f;
					climate[TerrainSample.PRECIPITATION * plane + px] = 1500f;
					climate[TerrainSample.PRECIPITATION_SEASONALITY * plane + px] = 30f;
					climate[TerrainSample.LAPSE_RATE * plane + px] = -0.0065f;
				}
			}
			return new TerrainSample(i0, j0, h, w, elevation, climate);
		}

		@Override
		public int coarseScale() {
			return TILE;
		}

		@Override
		public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
			int w = cj1 - cj0, plane = (ci1 - ci0) * w;
			float[] out = new float[COARSE_CHANNELS * plane];
			for (int r = 0; r < ci1 - ci0; r++) {
				for (int c = 0; c < w; c++) {
					int px = r * w + c;
					float mean = elevation((ci0 + r + 0.5) * TILE, (cj0 + c + 0.5) * TILE);
					out[COARSE_ELEVATION * plane + px] = mean;
					out[COARSE_LOW_ELEVATION * plane + px] = mean - 30f;
					out[COARSE_TEMPERATURE * plane + px] = 10f;
					out[COARSE_TEMPERATURE_SEASONALITY * plane + px] = 600f;
					out[COARSE_PRECIPITATION * plane + px] = 1500f;
					out[COARSE_PRECIPITATION_SEASONALITY * plane + px] = 30f;
				}
			}
			return out;
		}
	}

	private static TerrainField field() {
		return new TerrainField(1, new ValleySource());
	}

	@Test
	void waterRunsDownTheValleyToTheSea() {
		RiverNetwork network = new RiverNetwork(field());
		for (int ci = 1; ci < ValleySource.RIDGES - 1; ci++) {
			assertEquals(0, network.direction(ci, VALLEY), "the valley floor drains north at " + ci);
			assertTrue(network.direction(ci, VALLEY - 2) >= 0, "the slopes drain");
		}
		assertTrue(network.ocean(-1, VALLEY));
		// Discharge grows downstream.
		assertTrue(network.discharge(2, VALLEY) > network.discharge(8, VALLEY));
		assertTrue(network.discharge(2, VALLEY) >= RiverNetwork.MIN_DISCHARGE);
	}

	@Test
	void dischargeIsTheRunoffOfEverythingUpstream() {
		RiverNetwork network = new RiverNetwork(field());
		float runoff = RiverNetwork.runoff(10f, 1500f);
		for (int ci = 0; ci < 10; ci++) {
			for (int cj = 0; cj < 9; cj++) {
				double upstream = 0;
				for (int n = 0; n < 8; n++) {
					int ui = ci + RiverNetwork.DI[n], uj = cj + RiverNetwork.DJ[n];
					int d = network.direction(ui, uj);
					if (d >= 0 && ui + RiverNetwork.DI[d] == ci && uj + RiverNetwork.DJ[d] == cj) {
						upstream += network.discharge(ui, uj);
					}
				}
				assertEquals(runoff + upstream, network.discharge(ci, cj), 1e-3 * (runoff + upstream));
			}
		}
	}

	@Test
	void neighboursAgreeOnEveryCrossing() {
		RiverNetwork network = new RiverNetwork(field());
		int crossings = 0;
		for (int ci = -2; ci < 12; ci++) {
			for (int cj = 0; cj < 10; cj++) {
				CellFlows a = network.flows(ci, cj);
				for (int side = 0; side < 4; side++) {
					CellFlows b = network.flows(ci + RiverNetwork.DI[side], cj + RiverNetwork.DJ[side]);
					int opposite = side ^ 1;
					assertEquals(a.outLevel[side], b.inLevel[opposite], 0f, "level at " + ci + "," + cj + " side " + side);
					assertEquals(a.outDischarge[side], b.inDischarge[opposite], 0f);
					if (!Float.isNaN(a.outLevel[side])) {
						crossings++;
					}
				}
				for (CellFlows.Inflow inflow : a.inflows) {
					assertTrue(inflow.target() != inflow.side(), "a river leaves where it came in");
				}
			}
		}
		assertTrue(crossings >= 10, "the valley has a river, crossings: " + crossings);
	}

	@Test
	void breachesPitsWithinReach() {
		// A one-cell hollow in a slope drains over its rim instead of ending in a pond.
		TerrainSource source = new ValleySource() {
			@Override
			public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
				float[] out = super.coarse(ci0, cj0, ci1, cj1);
				int w = cj1 - cj0, plane = (ci1 - ci0) * w;
				if (ci0 <= 6 && 6 < ci1 && cj0 <= 1 && 1 < cj1) {
					out[COARSE_LOW_ELEVATION * plane + (6 - ci0) * w + 1 - cj0] -= 400f;
				}
				return out;
			}
		};
		RiverNetwork network = new RiverNetwork(new TerrainField(1, source));
		assertNotEquals(RiverNetwork.PIT, network.direction(6, 1));
		int pits = 0;
		for (int ci = 0; ci < 12; ci++) {
			for (int cj = 0; cj < 9; cj++) {
				pits += network.direction(ci, cj) == RiverNetwork.PIT ? 1 : 0;
			}
		}
		assertEquals(0, pits);
	}

	@Test
	void riverHasWaterAndMeetsItselfAcrossTileBorders() {
		Rivers rivers = Rivers.of(field());
		WorldScale scale = WorldScale.STANDARD;
		int blocksPerTile = (int) Math.round(TILE / scale.pixelsPerBlock());
		int channels = 0;
		for (int tile = 2; tile < 8; tile++) {
			// Walk across the valley right before and right after a tile border, at z = border - 1 and border.
			int border = tile * blocksPerTile;
			for (int x = VALLEY * blocksPerTile; x < (VALLEY + 1) * blocksPerTile; x++) {
				RiverColumn before = copy(rivers.column(scale, x, border - 1));
				RiverColumn after = rivers.column(scale, x, border);
				assertEquals(before.channel, after.channel, "channel at x " + x + " across z " + border);
				if (before.channel) {
					channels++;
					assertTrue(Math.abs(before.waterTop - after.waterTop) <= 1, "water steps at most a block");
					assertTrue(after.bed < after.waterTop);
				}
			}
		}
		assertTrue(channels >= 6, "the river crosses every border, channel columns: " + channels);
	}

	@Test
	void banksHoldTheWaterWhereItStepsDown() {
		Rivers rivers = Rivers.of(field());
		WorldScale scale = WorldScale.STANDARD;
		int blocksPerTile = (int) Math.round(TILE / scale.pixelsPerBlock());
		// The whole valley, a row at a time, away from the sea.
		int x0 = VALLEY * blocksPerTile, size = blocksPerTile;
		RiverColumn[][] rows = new RiverColumn[3][size];
		int steps = 0, channels = 0;
		for (int z = blocksPerTile; z < (ValleySource.RIDGES - 1) * blocksPerTile; z++) {
			RiverColumn[] next = rows[0];
			rows[0] = rows[1];
			rows[1] = rows[2];
			rows[2] = next;
			for (int dx = 0; dx < size; dx++) {
				next[dx] = copy(rivers.column(scale, x0 + dx, z + 1));
			}
			if (rows[0][0] == null) {
				continue;
			}
			for (int dx = 1; dx < size - 1; dx++) {
				RiverColumn c = rows[1][dx];
				if (!c.channel) {
					continue;
				}
				channels++;
				RiverColumn[] neighbours = {rows[1][dx - 1], rows[1][dx + 1], rows[0][dx], rows[2][dx]};
				for (RiverColumn n : neighbours) {
					if (n.channel) {
						// Water running into the air over lower water falls into it and stays in the channel.
						steps += n.waterTop < c.waterTop ? 1 : 0;
					} else {
						assertTrue(n.river && n.surface > c.waterTop, "a bank holds the water at " + (x0 + dx) + ", " + z);
					}
				}
			}
		}
		assertTrue(channels > 1000, "the valley has a river, channel columns: " + channels);
		assertTrue(steps > 0, "the water steps down along the valley");
	}

	@Test
	void lakesFillTheHollowsAndTheirShoresHoldThem() {
		Rivers rivers = Rivers.of(field());
		// The rolling hills on the slopes between the valleys leave hollows 3 m deep: three blocks at the Earth scale,
		// less than one at the others.
		WorldScale scale = WorldScale.EARTH;
		int lakes = 0;
		for (int z = 23040; z < 30720; z += 32) {
			for (int x = 0; x < 15360; x += 32) {
				RiverColumn c = copy(rivers.column(scale, x, z));
				if (!c.lake) {
					continue;
				}
				lakes++;
				assertTrue(c.channel && c.bed < c.waterTop, "a lake has water at " + x + ", " + z);
				int[][] sides = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
				for (int[] side : sides) {
					RiverColumn n = rivers.column(scale, x + side[0], z + side[1]);
					assertTrue(n.channel || n.river && n.surface > c.waterTop,
						"the shore holds the lake at " + x + ", " + z);
				}
			}
		}
		assertTrue(lakes > 20, "the hollows hold lakes, lake columns: " + lakes);
	}

	@Test
	void theRiverFillsTheHollowsOfItsValley() {
		Rivers rivers = Rivers.of(field());
		int fed = 0;
		for (int ti = 1; ti < ValleySource.RIDGES - 1; ti++) {
			CellLakes lakes = rivers.lakes(ti, VALLEY);
			for (int i = 0; i < TILE; i++) {
				for (int j = 0; j < TILE; j++) {
					int run = lakes.run(i, j);
					fed += run >= 0 && lakes.runFed(run) ? 1 : 0;
				}
			}
		}
		// The banks of these lakes are checked with the river's in banksHoldTheWaterWhereItStepsDown.
		assertTrue(fed > 0, "the river fills the hollows it crosses");
	}

	@Test
	void aDryClimateLeavesTheHollowsDry() {
		TerrainSource source = new ValleySource() {
			@Override
			public TerrainSample sample(int i0, int j0, int i1, int j1) {
				TerrainSample sample = super.sample(i0, j0, i1, j1);
				int plane = (i1 - i0) * (j1 - j0);
				Arrays.fill(sample.climate(), TerrainSample.PRECIPITATION * plane,
					(TerrainSample.PRECIPITATION + 1) * plane, 100f);
				return sample;
			}
		};
		Rivers rivers = Rivers.of(new TerrainField(1, source));
		for (int z = 23040; z < 30720; z += 32) {
			for (int x = 0; x < 15360; x += 32) {
				assertFalse(rivers.column(WorldScale.EARTH, x, z).lake, "a lake at " + x + ", " + z);
			}
		}
	}

	@Test
	void sameRiversOnEveryThread() throws Exception {
		WorldScale scale = WorldScale.STANDARD;
		Rivers serial = Rivers.of(field());
		List<int[]> points = new ArrayList<>();
		for (int k = 0; k < 400; k++) {
			points.add(new int[]{2300 + (k * 37) % 520, 1000 + (k * 53) % 4000});
		}
		Rivers parallel = Rivers.of(field());
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<RiverColumn>> results = new ArrayList<>();
			for (int[] p : points) {
				results.add(pool.submit(() -> copy(parallel.column(scale, p[0], p[1]))));
			}
			boolean any = false;
			for (int k = 0; k < points.size(); k++) {
				RiverColumn expected = serial.column(scale, points.get(k)[0], points.get(k)[1]);
				RiverColumn actual = results.get(k).get();
				assertEquals(expected.river, actual.river);
				assertEquals(expected.channel, actual.channel);
				if (actual.river) {
					assertEquals(expected.surface, actual.surface);
					assertEquals(expected.bandBottom, actual.bandBottom);
					assertEquals(expected.waterTop, actual.waterTop);
					any = true;
				}
			}
			assertTrue(any);
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void noRiversWithoutATileScaleCoarseMap() {
		TerrainSource source = new ValleySource() {
			@Override
			public int coarseScale() {
				return 4;
			}
		};
		Rivers rivers = Rivers.of(new TerrainField(1, source));
		for (int x = 2000; x < 2600; x += 7) {
			assertFalse(rivers.column(WorldScale.STANDARD, x, 3000).river);
		}
	}

	@Test
	void widthGrowsWithDischarge() {
		for (WorldScale scale : WorldScale.values()) {
			float small = Rivers.halfWidthPixels(scale, RiverNetwork.MIN_DISCHARGE);
			float large = Rivers.halfWidthPixels(scale, 2000f);
			assertTrue(small * WorldScale.METERS_PER_PIXEL >= scale.metersPerBlock() - 1e-3, "at least two blocks wide");
			assertTrue(large > small);
			assertTrue(large <= 8f);
		}
	}

	private static RiverColumn copy(RiverColumn c) {
		RiverColumn out = new RiverColumn();
		out.river = c.river;
		out.surface = c.surface;
		out.bandBottom = c.bandBottom;
		out.channel = c.channel;
		out.lake = c.lake;
		out.waterTop = c.waterTop;
		out.bed = c.bed;
		out.level = c.level;
		out.discharge = c.discharge;
		out.valley = c.valley;
		return out;
	}
}
