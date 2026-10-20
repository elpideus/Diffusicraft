package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FjordsTest {
	/** A cold mountain coast whose valleys climb gently, 51 m per coarse pixel: the ice could reach far inland. */
	private static final GlaciationTest.CoastSource COAST = new GlaciationTest.CoastSource(5f, 0.2f);
	private static final int SCALE = TerrainField.TILE;

	private static int mouthRow(Fjords.Trunk trunk) {
		return (int) (trunk.key() >> 32);
	}

	private static int mouthColumn(Fjords.Trunk trunk) {
		return (int) trunk.key();
	}

	@Test
	void coldMountainCoastsGrowFjordsSpacedAlongThem() {
		List<Fjords.Trunk> trunks = new Fjords(COAST, 1L).near(-4, -64, 4, 64);
		assertTrue(trunks.size() >= 8, trunks.size() + " fjords on 128 coarse pixels of coast");
		trunks.sort(Comparator.comparingInt(FjordsTest::mouthColumn));
		for (int k = 0; k < trunks.size(); k++) {
			Fjords.Trunk trunk = trunks.get(k);
			assertEquals(0, mouthRow(trunk), "the mouth is on the coast");
			assertTrue(trunk.rows()[trunk.head()] > Fjords.MIN_STEPS, "the head lies inland");
			if (k > 0) {
				assertTrue(mouthColumn(trunk) - mouthColumn(trunks.get(k - 1)) > Fjords.SPACING, "mouths keep apart");
			}
		}
	}

	@Test
	void theTroughSinksBehindItsSillAndRisesToItsHead() {
		for (Fjords.Trunk trunk : new Fjords(COAST, 1L).near(-4, -64, 4, 64)) {
			float[] floor = trunk.floor();
			assertEquals(-Fjords.SILL, floor[trunk.mouth()]);
			assertEquals(-Fjords.SILL, floor[0], "out at sea, the floor is the sill's");
			assertEquals(-Fjords.HEAD_DEPTH, floor[trunk.head()], 1e-3f);
			float deepest = 0f;
			for (int k = trunk.mouth(); k <= trunk.head(); k++) {
				assertTrue(floor[k] <= -Fjords.HEAD_DEPTH + 1e-3f, "the water runs all the way to the head");
				deepest = Math.min(deepest, floor[k]);
			}
			assertTrue(deepest < -Fjords.SILL - 50f, "the basin is deeper than the sill: " + deepest);
			assertTrue(deepest >= -Fjords.MAX_DEPTH * (1f + Fjords.DEPTH_VARIATION));
			assertTrue(floor[floor.length - 1] > 0f, "past the head, the valley climbs out of the sea");
		}
	}

	@Test
	void theLongestFjordsCarryTheSeaFarBeyondTheValleys() {
		List<Fjords.Trunk> trunks = new Fjords(COAST, 1L).near(-4, -64, 4, 64);
		Fjords.Trunk longest = trunks.stream()
			.max(Comparator.comparingDouble(t -> t.rows()[t.head()])).orElseThrow();
		assertTrue(longest.rows()[longest.head()] > Glaciation.SEA_REACH + 1,
			"the longest fjord reaches " + longest.rows()[longest.head()] + " coarse pixels inland");
		// Along the whole path, the ground is under the sea and a fjord's, even where the ice's other valleys are dry.
		TerrainField field = new TerrainField(1, COAST);
		TerrainField.Column column = new TerrainField.Column();
		for (int k = longest.mouth(); k <= longest.head(); k += Fjords.SAMPLES / 4) {
			int i = (int) Math.floor(longest.rows()[k] * SCALE), j = (int) Math.floor(longest.columns()[k] * SCALE);
			field.column(i, j, column);
			assertTrue(column.elevation < 0f && Glaciation.drowned(column.elevation, column.iceCut),
				"at " + i + ", " + j + ": " + column.elevation + " m, cut " + column.iceCut);
		}
	}

	@Test
	void mildCoastsGrowNoFjords() {
		assertTrue(new Fjords(new GlaciationTest.CoastSource(15f, 0.2f), 1L).near(-4, -64, 20, 64).isEmpty());
	}

	@Test
	void plainsOpenNoFjords() {
		for (Fjords.Trunk trunk : new Fjords(new GlaciationTest.CoastSource(5f, 0.2f, 40f), 1L).near(-4, -64, 4, 64)) {
			assertTrue(mouthColumn(trunk) >= 0, "a fjord opens on the plain at " + mouthColumn(trunk));
		}
	}

	@Test
	void neighbouringBlocksAgreeToTheBit() {
		Fjords fjords = new Fjords(COAST, 1L);
		Map<Long, Fjords.Trunk> first = new HashMap<>();
		for (Fjords.Trunk trunk : fjords.block(0, 0)) {
			first.put(trunk.key(), trunk);
		}
		int shared = 0;
		for (Fjords.Trunk trunk : fjords.block(0, 1)) {
			Fjords.Trunk other = first.get(trunk.key());
			if (other != null) {
				shared++;
				assertArrayEquals(other.rows(), trunk.rows());
				assertArrayEquals(other.columns(), trunk.columns());
				assertArrayEquals(other.floor(), trunk.floor());
				assertArrayEquals(other.halfWidth(), trunk.halfWidth());
			}
		}
		assertTrue(shared > 0, "the blocks share fjords");
	}

	@Test
	void overlappingSamplesAcrossAFjordAgreeToTheBit() {
		Fjords.Trunk trunk = new Fjords(COAST, 1L).near(-4, -64, 4, 64).getFirst();
		int k = trunk.mouth() + 2 * Fjords.SAMPLES;
		int ci = (int) Math.floor(trunk.rows()[k] * SCALE), cj = (int) Math.floor(trunk.columns()[k] * SCALE);
		Glaciation glaciation = new Glaciation(COAST, 1L);
		TerrainSample a = glaciation.sample(ci - 300, cj - 300, ci + 20, cj + 20);
		TerrainSample b = glaciation.sample(ci - 100, cj - 100, ci + 220, cj + 220);
		assertTrue(a.elevation(ci, cj) < 0f, "the fjord crosses the overlap");
		for (int i = ci - 100; i < ci + 20; i++) {
			for (int j = cj - 100; j < cj + 20; j++) {
				assertEquals(a.elevation(i, j), b.elevation(i, j), 0f);
				assertEquals(a.climate(TerrainSample.TEMPERATURE, i, j), b.climate(TerrainSample.TEMPERATURE, i, j), 0f);
			}
		}
	}

	@Test
	void wallsRiseInAUAndSteepen() {
		assertEquals(0f, Fjords.wall(-10f));
		assertEquals(0f, Fjords.wall(0f));
		assertTrue(Fjords.wall(500f) < 200f, "the floor's edge is gentle");
		assertTrue(Fjords.wall(2000f) > 1000f, "two kilometres out, the walls stand a kilometre high");
		float previous = 0f;
		for (float x = 100f; x <= Fjords.WALL_REACH; x += 100f) {
			float slope = Fjords.wall(x) - Fjords.wall(x - 100f);
			assertTrue(slope > previous, "steeper at " + x);
			previous = slope;
		}
		assertEquals(Math.min(0f, 100f) - Fjords.SMOOTH / 4f, Fjords.smoothMin(0f, 0f), 1e-3f);
		assertEquals(-50f, Fjords.smoothMin(-50f, 100f));
	}
}
