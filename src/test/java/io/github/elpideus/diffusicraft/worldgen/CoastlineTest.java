package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoastlineTest {
	private static final int TILE = TerrainField.TILE;
	private static final int REACH = Coastline.REACH;

	/** Land rising to the south-east, 10 m per pixel, from a straight coast where {@code 0.6 u + 0.8 v = 300}. */
	private static final FormulaSource.Elevation SLOPE = (i, j) -> (float) (10 * (0.6 * i + 0.8 * j - 300));

	/** The distance from the straight coast at a field coordinate, pixel centres lying half a pixel in. */
	private static double slopeDistance(double i, double j) {
		return 0.6 * (i - 0.5) + 0.8 * (j - 0.5) - 300;
	}

	@Test
	void aStraightCoastIsMeasuredExactly() {
		TerrainField field = new TerrainField(1, new FormulaSource(SLOPE));
		int checked = 0;
		for (double i = -40.3; i < 600; i += 3.7) {
			for (double j = -40.1; j < 600; j += 4.3) {
				double expected = slopeDistance(i, j);
				if (Math.abs(expected) < REACH - 1) {
					assertEquals(expected, field.coast(i, j), 1e-3, i + ", " + j);
					checked++;
				} else if (Math.abs(expected) > REACH + 1.5) {
					assertEquals(Math.signum(expected) * REACH, field.coast(i, j), 0f, i + ", " + j);
				}
			}
		}
		assertTrue(checked > 300, "the coast crosses several tiles");
	}

	@Test
	void theGradientPointsInlandAtUnitLength() {
		TerrainField field = new TerrainField(1, new FormulaSource(SLOPE));
		float[] gradient = new float[2];
		for (double i = 100.2; i < 400; i += 7.1) {
			double j = (300 + 0.5 * 0.6 + 0.5 * 0.8 - 0.6 * i) / 0.8 + 3.3;
			field.coast(i, j, gradient);
			assertEquals(0.6, gradient[0], 1e-3);
			assertEquals(0.8, gradient[1], 1e-3);
		}
	}

	@Test
	void theDistanceIsContinuousAcrossTiles() {
		// Island coasts curving every way.
		FormulaSource.Elevation hills = (i, j) -> (float) (40 * Math.sin(i / 9.0) + 30 * Math.cos(j / 7.0) + 5);
		TerrainField field = new TerrainField(1, new FormulaSource(hills));
		float[] before = new float[2], after = new float[2];
		for (int border : new int[]{0, TILE, -TILE, 2 * TILE}) {
			for (double j = -300.3; j < 600; j += 1.7) {
				// The tile is chosen by the pixel below the point, which changes at border + 0.5.
				float a = field.coast(border + 0.5 - 1e-7, j, before), b = field.coast(border + 0.5 + 1e-7, j, after);
				assertEquals(a, b, 1e-4, border + ", " + j);
				assertEquals(before[0], after[0], 1e-4);
				assertEquals(before[1], after[1], 1e-4);
				a = field.coast(j, border + 0.5 - 1e-7, before);
				b = field.coast(j, border + 0.5 + 1e-7, after);
				assertEquals(a, b, 1e-4, j + ", " + border);
				assertEquals(before[0], after[0], 1e-4);
				assertEquals(before[1], after[1], 1e-4);
			}
		}
	}

	@Test
	void anIslandIsMeasuredFromItsRoundShore() {
		// A cone 20 pixels across at sea level, centred on pixel (100, 100).
		FormulaSource.Elevation cone = (i, j) -> (float) (20 * (20 - Math.hypot(i - 100, j - 100)));
		TerrainField field = new TerrainField(1, new FormulaSource(cone));
		for (double angle = 0; angle < 2 * Math.PI; angle += 0.37) {
			for (double r = 9; r <= 31; r += 2.5) {
				double i = 100.5 + r * Math.cos(angle), j = 100.5 + r * Math.sin(angle);
				// The polygon traced through the cells cuts inside the circle by at most 1 - cos(half a pixel's arc).
				assertEquals(20 - r, field.coast(i, j), 0.05, angle + ", " + r);
			}
		}
	}

	@Test
	void farFromTheSeaThereIsNoCoast() {
		float[] land = new float[TerrainField.SPAN * TerrainField.SPAN];
		assertNull(Coastline.compute(land), "ground at sea level is land");
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) -> i < -2000 ? -50f : 50f));
		assertEquals(REACH, field.coast(100.5, 100.5), 0f);
		assertEquals(-REACH, field.coast(-2500.5, 100.5), 0f);
		TerrainField.Column column = new TerrainField.Column();
		field.coarseColumn(-2500.5, 100.5, column);
		assertEquals(-TerrainField.COAST_REACH, column.coast, 0f, "the coarse map has no coastline");
	}

	@Test
	void aCoastJustOutsideTheTileStillCounts() {
		// The sea begins three pixels north of tile (0, 0); none of its own pixels lie in it.
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) -> i < -3 ? -10f : 10f));
		assertEquals(3.5, field.coast(0.5, 50.5), 1e-3);
		assertEquals(REACH, field.coast(20.5, 50.5), 0f);
	}

	@Test
	void aSaddleFollowsTheMeanOfItsCorners() {
		float[] ends = new float[8];
		// Land at 00 and 11: with a high middle the land joins across the cell and cuts off the corners at sea.
		assertEquals(2, Coastline.segments(9, 3f, -1f, -1f, 3f, ends));
		assertEquals(0f, ends[0], 0f);
		assertEquals(0.75f, ends[1], 0f);
		assertEquals(0.25f, ends[2], 0f);
		assertEquals(1f, ends[3], 0f);
		// With a low middle the sea joins and cuts off the corners on land.
		assertEquals(2, Coastline.segments(9, 1f, -3f, -3f, 1f, ends));
		assertEquals(0f, ends[0], 0f);
		assertEquals(0.25f, ends[1], 0f);
		assertEquals(0.25f, ends[2], 0f);
		assertEquals(0f, ends[3], 0f);
	}
}
