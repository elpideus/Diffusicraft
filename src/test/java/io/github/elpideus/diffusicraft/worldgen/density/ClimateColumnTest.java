package io.github.elpideus.diffusicraft.worldgen.density;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.FormulaSource;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The climate of a column on the slopes of a ridge. */
class ClimateColumnTest {
	/** A ridge 3 km high whose sides fall half a meter per meter, along {@code i} (Z) or along {@code j} (X). */
	private static FormulaSource.Elevation ridge(boolean alongZ) {
		return (i, j) -> (float) (3000 - 15 * Math.sqrt((alongZ ? i * i : j * j) + 100));
	}

	private static ClimateColumn column(TerrainField field, int x, int z) {
		ClimateColumn column = new ClimateColumn();
		column.sample(field, WorldScale.MEDIUM, x, z);
		return column;
	}

	@Test
	void theSunnySideOfARidgeIsWarmerAndDrier() {
		TerrainField field = new TerrainField(1, new FormulaSource(ridge(true)));
		// 100 pixels south and north of the crest, at the same height.
		ClimateColumn south = column(field, 0, 200);
		ClimateColumn north = column(field, 0, -199);
		assertEquals(south.column.elevation, north.column.elevation, 1f);

		float exposure = ClimateMapping.exposure(0f, -0.5f);
		float warming = south.column.temperature - north.column.temperature;
		System.out.printf("The south face is %.2f °C warmer than the north face%n", warming);
		assertEquals(2 * ClimateMapping.EXPOSURE_WARMING * exposure, warming, 0.05);
		float wet = 1f + ClimateMapping.EXPOSURE_DRYING * exposure, dry = 1f - ClimateMapping.EXPOSURE_DRYING * exposure;
		assertEquals(wet / dry, north.column.precipitation / south.column.precipitation, 0.02);
		assertTrue(south.parameters[ClimateMapping.TEMPERATURE] > north.parameters[ClimateMapping.TEMPERATURE]);
		assertTrue(south.parameters[ClimateMapping.HUMIDITY] < north.parameters[ClimateMapping.HUMIDITY]);
	}

	@Test
	void slopesFacingEastAndWestAreAlike() {
		TerrainField field = new TerrainField(1, new FormulaSource(ridge(false)));
		ClimateColumn east = column(field, 200, 0);
		ClimateColumn west = column(field, -199, 0);
		assertEquals(east.column.temperature, west.column.temperature, 0.01);
		assertEquals(east.column.precipitation, west.column.precipitation, 0.1);
	}
}
