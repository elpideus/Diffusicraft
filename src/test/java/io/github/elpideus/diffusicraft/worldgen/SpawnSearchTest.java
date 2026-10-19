package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnSearchTest {
	private static final WorldScale SCALE = WorldScale.STANDARD;

	private static float elevationAt(TerrainField field, SpawnSearch.Target target) {
		return field.elevation(SCALE.toPixel(target.z()), SCALE.toPixel(target.x()));
	}

	@Test
	void aCoastalSpawnIsOnTheBeachNearestTheOrigin() {
		// Land falling 5 m a pixel to the sea, which begins 16 pixels (32 blocks) east of the origin.
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) -> 5f * (16 - (float) j) - 2.5f));
		SpawnSearch.Target target = SpawnSearch.find(field, SCALE, SpawnKind.COAST);
		double i = SCALE.toPixel(target.z()), j = SCALE.toPixel(target.x());
		float coast = field.coast(i, j);
		assertTrue(coast > 0 && coast <= SpawnSearch.SHORE, "coast " + coast + " at " + target);
		float above = SCALE.surfaceY(field.elevation(i, j)) - SCALE.seaLevel();
		assertTrue(above >= 1 && above <= 4, above + " blocks over the sea at " + target);
		assertTrue(target.x() > 16 && target.x() < 32, target.toString());
		assertTrue(Math.abs(target.z()) <= 4, target.toString());
	}

	@Test
	void aSpawnByAPeakIsOnItsSummit() {
		// A mountain 1,500 m over a plain, its top 10 pixels south and 8 west of the origin.
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) ->
			100f + 1500f * (float) Math.exp(-((i - 10) * (i - 10) + (j + 8) * (j + 8)) / 72.0)));
		SpawnSearch.Target target = SpawnSearch.find(field, SCALE, SpawnKind.PEAK);
		assertTrue(elevationAt(field, target) > 1580, elevationAt(field, target) + " m at " + target);
		assertTrue(Math.abs(target.x() + 16) <= 2 && Math.abs(target.z() - 20) <= 2, target.toString());
	}

	@Test
	void withoutAMountainThePeakFallsBackToTheLand() {
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) -> 120f));
		assertEquals(new SpawnSearch.Target(0, 0), SpawnSearch.find(field, SCALE, SpawnKind.PEAK));
		assertEquals(new SpawnSearch.Target(0, 0), SpawnSearch.find(field, SCALE, SpawnKind.INLAND));
	}

	@Test
	void anInlandSpawnAtSeaMovesToTheNearestLand() {
		// Sea at the origin, and land from 8 pixels (16 blocks) east.
		TerrainField field = new TerrainField(1, new FormulaSource((i, j) -> j >= 8 ? 100f : -50f));
		SpawnSearch.Target target = SpawnSearch.find(field, SCALE, SpawnKind.INLAND);
		assertTrue(elevationAt(field, target) > 0, target.toString());
		assertTrue(target.x() >= 16 && target.x() <= 32 && Math.abs(target.z()) <= 8, target.toString());
	}
}
