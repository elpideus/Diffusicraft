package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpliftTest {
	/** Hills and a range of mountains down to the sea, at most 3 km high. */
	private static final FormulaSource.Elevation RANGE = (i, j) ->
		(float) (3000 * Math.exp(-Math.pow((i - 40) / 25, 2)) * (0.6 + 0.4 * Math.cos(j / 9)) - 400 + 3 * j);

	/** {@link #RANGE} with the valley floor everywhere at {@code floor} meters. */
	private static TerrainSource flatFloor(float floor) {
		FormulaSource source = new FormulaSource(RANGE);
		return new TerrainSource() {
			@Override
			public TerrainSample sample(int i0, int j0, int i1, int j1) {
				return source.sample(i0, j0, i1, j1);
			}

			@Override
			public int coarseScale() {
				return source.coarseScale();
			}

			@Override
			public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
				float[] map = source.coarse(ci0, cj0, ci1, cj1);
				int plane = (ci1 - ci0) * (cj1 - cj0);
				Arrays.fill(map, COARSE_LOW_ELEVATION * plane, (COARSE_LOW_ELEVATION + 1) * plane, floor);
				return map;
			}
		};
	}

	@Test
	void theLiftGrowsInFromTheFloorAndNeverFasterThanTheGain() {
		assertEquals(0f, Uplift.lift(0f));
		assertEquals(0f, Uplift.lift(-100f));
		assertTrue(Uplift.lift(20f) < 1f, "low hills barely rise");
		assertEquals(426.4f, Uplift.lift(1000f), 0.5f);
		assertEquals(1020f, Uplift.lift(2000f), 1f);
		float previous = 0f;
		for (float above = 10f; above <= 9000f; above += 10f) {
			float lift = Uplift.lift(above);
			assertTrue(lift > previous, "the lift grows with the height, at " + above);
			assertTrue(lift - previous <= (Uplift.GAIN - 1f) * 10f * 1.001f, "no faster than the gain, at " + above);
			previous = lift;
		}
	}

	@Test
	void groundBelowTheFloorOrTheSeaStaysWhereItIs() {
		assertEquals(-50f, Uplift.raise(-50f, -200f));
		assertEquals(150f, Uplift.raise(150f, 200f));
		assertEquals(200f, Uplift.raise(200f, 200f));
		// Below the sea the floor is sea level.
		assertEquals(1000f + Uplift.lift(1000f), Uplift.raise(1000f, -300f));

		float floor = 500f;
		try (TerrainSource plain = flatFloor(floor); Uplift raised = new Uplift(flatFloor(floor))) {
			TerrainSample before = plain.sample(-8, -8, 96, 96), after = raised.sample(-8, -8, 96, 96);
			int plane = before.height() * before.width();
			int lifted = 0;
			for (int px = 0; px < plane; px++) {
				float e = before.elevation()[px], expected = Uplift.raise(e, floor);
				assertEquals(expected, after.elevation()[px], 1e-3f * Math.max(1f, Math.abs(expected)));
				if (e <= floor) {
					assertEquals(e, after.elevation()[px], "the floor and the sea keep their levels");
				} else {
					lifted++;
					// Raised ground is colder, by the lapse rate.
					float lapse = before.climate()[TerrainSample.LAPSE_RATE * plane + px];
					assertEquals(before.climate()[TerrainSample.TEMPERATURE * plane + px] + lapse * (expected - e),
						after.climate()[TerrainSample.TEMPERATURE * plane + px], 1e-3f);
				}
			}
			assertTrue(lifted > plane / 10, "the mountains rise");
		}
	}

	@Test
	void aWorldUnderTheSeaIsLeftAlone() {
		FormulaSource flat = new FormulaSource((i, j) -> -20f);
		try (Uplift raised = new Uplift(flat)) {
			for (float e : raised.sample(0, 0, 16, 16).elevation()) {
				assertEquals(-20f, e);
			}
		}
	}

	@Test
	void windowsAgreeToTheBit() {
		try (Uplift raised = new Uplift(new FormulaSource(RANGE))) {
			TerrainSample whole = raised.sample(-13, -7, 83, 89);
			TerrainSample part = raised.sample(21, 30, 47, 61);
			for (int r = 0; r < part.height(); r++) {
				for (int c = 0; c < part.width(); c++) {
					int i = part.i0() + r, j = part.j0() + c;
					int inWhole = (i - whole.i0()) * whole.width() + j - whole.j0();
					assertEquals(Float.floatToIntBits(whole.elevation()[inWhole]),
						Float.floatToIntBits(part.elevation()[r * part.width() + c]), "at " + i + ", " + j);
				}
			}
		}
	}

	@Test
	void theCoarseMeanRisesAndItsFloorStays() {
		try (TerrainSource plain = flatFloor(200f); Uplift raised = new Uplift(flatFloor(200f))) {
			float[] before = plain.coarse(0, -2, 20, 24), after = raised.coarse(0, -2, 20, 24);
			int plane = 20 * 26;
			for (int px = 0; px < plane; px++) {
				float mean = before[TerrainSource.COARSE_ELEVATION * plane + px];
				float lifted = Uplift.raise(mean, 200f);
				assertEquals(lifted, after[TerrainSource.COARSE_ELEVATION * plane + px]);
				assertEquals(200f, after[TerrainSource.COARSE_LOW_ELEVATION * plane + px]);
				assertEquals(before[TerrainSource.COARSE_TEMPERATURE * plane + px]
						+ Uplift.COARSE_LAPSE * (lifted - mean),
					after[TerrainSource.COARSE_TEMPERATURE * plane + px], 1e-4f);
				assertEquals(before[TerrainSource.COARSE_PRECIPITATION * plane + px],
					after[TerrainSource.COARSE_PRECIPITATION * plane + px]);
			}
		}
	}
}
