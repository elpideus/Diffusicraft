package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverNetworkTest {
	private static final int TILE = TerrainField.TILE;

	/**
	 * A coarse map of hills and hollows of every size, from a few cells to a few dozen across, with a sea to the north
	 * ({@code i < 0}) and a wet climate, so hollows of every size hold lakes. The fine terrain is flat: only the
	 * network looks at this map.
	 */
	static final class HollowsSource implements TerrainSource {
		static float elevation(int ci, int cj) {
			double e = 500.0 + 260.0 * noise(ci, cj, 23, 1) + 120.0 * noise(ci, cj, 9, 2) + 40.0 * noise(ci, cj, 3, 3);
			return (float) (ci < 0 ? -200.0 : Math.max(5.0, e - Math.max(0.0, 12.0 - ci) * 60.0));
		}

		/** Smooth value noise in [-1, 1] on a lattice {@code spacing} cells apart. */
		private static double noise(int ci, int cj, int spacing, int salt) {
			int li = Math.floorDiv(ci, spacing), lj = Math.floorDiv(cj, spacing);
			double fi = smooth((ci - li * spacing) / (double) spacing);
			double fj = smooth((cj - lj * spacing) / (double) spacing);
			double top = lerp(lattice(li, lj, salt), lattice(li, lj + 1, salt), fj);
			double bottom = lerp(lattice(li + 1, lj, salt), lattice(li + 1, lj + 1, salt), fj);
			return lerp(top, bottom, fi);
		}

		private static double lattice(int i, int j, int salt) {
			long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
			h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
			h ^= h >>> 29;
			return (h >>> 11) / (double) (1L << 53) * 2.0 - 1.0;
		}

		private static double smooth(double t) {
			return t * t * (3.0 - 2.0 * t);
		}

		private static double lerp(double a, double b, double t) {
			return a + (b - a) * t;
		}

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			int plane = (i1 - i0) * (j1 - j0);
			return new TerrainSample(i0, j0, i1 - i0, j1 - j0, new float[plane],
				new float[TerrainSample.CLIMATE_CHANNELS * plane]);
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
					float mean = elevation(ci0 + r, cj0 + c);
					out[COARSE_ELEVATION * plane + px] = mean;
					out[COARSE_LOW_ELEVATION * plane + px] = mean <= 0f ? mean : Math.max(1f, mean - 25f);
					out[COARSE_TEMPERATURE * plane + px] = 10f;
					out[COARSE_TEMPERATURE_SEASONALITY * plane + px] = 600f;
					out[COARSE_PRECIPITATION * plane + px] = 1500f;
					out[COARSE_PRECIPITATION_SEASONALITY * plane + px] = 30f;
				}
			}
			return out;
		}
	}

	@Test
	void aWiderWindowFindsTheSameNetwork() {
		TerrainField field = new TerrainField(1, new HollowsSource());
		RiverNetwork network = new RiverNetwork(field);
		RiverNetwork wider = new RiverNetwork(field, RiverNetwork.MARGIN + 3 * RiverNetwork.LAKE_RADIUS);
		Set<Long> lakes = new HashSet<>();
		int large = 0;
		for (int ci = -8; ci < 3 * RiverNetwork.REGION; ci++) {
			for (int cj = -RiverNetwork.REGION; cj < 2 * RiverNetwork.REGION; cj++) {
				String at = "cell (" + ci + ", " + cj + ")";
				assertEquals(wider.direction(ci, cj), network.direction(ci, cj), at);
				assertEquals(wider.level(ci, cj), network.level(ci, cj), at);
				assertEquals(wider.discharge(ci, cj), network.discharge(ci, cj), at);
				RiverNetwork.Lake lake = network.lakeAt(ci, cj), other = wider.lakeAt(ci, cj);
				if (lake == null) {
					assertNull(other, at);
					continue;
				}
				assertEquals(other.id(), lake.id(), at);
				assertEquals(other.level(), lake.level(), at);
				assertArrayEquals(other.cells(), lake.cells(), at);
				assertArrayEquals(new int[] {other.outI(), other.outJ(), other.exitI(), other.exitJ()},
					new int[] {lake.outI(), lake.outJ(), lake.exitI(), lake.exitJ()}, at);
				if (lakes.add(lake.id()) && lake.cells().length / 2 > 16) {
					large++;
				}
			}
		}
		assertTrue(lakes.size() >= 10 && large >= 2, lakes.size() + " lakes, " + large + " of more than 16 cells");
	}

	@Test
	void lakesReachTheirLargestSize() {
		RiverNetwork network = new RiverNetwork(new TerrainField(1, new HollowsSource()));
		int largest = 0, widest = 0;
		for (int ci = 0; ci < 4 * RiverNetwork.REGION; ci++) {
			for (int cj = -2 * RiverNetwork.REGION; cj < 2 * RiverNetwork.REGION; cj++) {
				RiverNetwork.Lake lake = network.lakeAt(ci, cj);
				if (lake == null) {
					continue;
				}
				int[] cells = lake.cells();
				int[] i = new int[cells.length / 2], j = new int[cells.length / 2];
				for (int c = 0; c < i.length; c++) {
					i[c] = cells[2 * c];
					j[c] = cells[2 * c + 1];
				}
				Arrays.sort(i);
				Arrays.sort(j);
				largest = Math.max(largest, i.length);
				widest = Math.max(widest, Math.max(i[i.length - 1] - i[0], j[j.length - 1] - j[0]) + 1);
			}
		}
		assertTrue(largest > 16 && largest <= RiverNetwork.MAX_LAKE_CELLS, "largest lake: " + largest + " cells");
		assertTrue(widest <= RiverNetwork.MAX_LAKE_SPAN, "widest lake: " + widest + " cells");
	}
}
