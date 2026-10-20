package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FjordsTest {
	/** A cold mountain coast whose valleys climb gently, 51 m per coarse pixel: the ice could reach far inland. */
	private static final GlaciationTest.CoastSource COAST = new GlaciationTest.CoastSource(5f, 0.2f);
	/** The same coast, with main valleys a coarse pixel wide and side valleys off them. */
	private static final GlaciationTest.CoastSource BRANCHING = GlaciationTest.CoastSource.branchingValleys(5f, 0.2f);
	private static final int SCALE = TerrainField.TILE;

	/** The trunks of the fjords on 128 coarse pixels of {@code source}'s coast, without their arms. */
	private static List<Fjords.Trough> trunks(TerrainSource source) {
		List<Fjords.Trough> trunks = new ArrayList<>();
		for (Fjords.Trough trough : new Fjords(source, 1L).near(-4, -64, 4, 64)) {
			if (trough.arm() == 0) {
				trunks.add(trough);
			}
		}
		return trunks;
	}

	private static int mouthRow(Fjords.Trough trunk) {
		return (int) (trunk.key() >> 32);
	}

	private static int mouthColumn(Fjords.Trough trunk) {
		return (int) trunk.key();
	}

	@Test
	void coldMountainCoastsGrowFjordsSpacedAlongThem() {
		List<Fjords.Trough> trunks = trunks(COAST);
		assertTrue(trunks.size() >= 8, trunks.size() + " fjords on 128 coarse pixels of coast");
		trunks.sort(Comparator.comparingInt(FjordsTest::mouthColumn));
		for (int k = 0; k < trunks.size(); k++) {
			Fjords.Trough trunk = trunks.get(k);
			assertEquals(0, mouthRow(trunk), "the mouth is on the coast");
			assertTrue(trunk.rows()[trunk.head()] > Fjords.MIN_STEPS, "the head lies inland");
			if (k > 0) {
				assertTrue(mouthColumn(trunk) - mouthColumn(trunks.get(k - 1)) > Fjords.SPACING, "mouths keep apart");
			}
		}
	}

	@Test
	void theTroughSinksBehindItsSillAndRisesToItsHead() {
		for (Fjords.Trough trunk : trunks(COAST)) {
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
		Fjords.Trough longest = trunks(COAST).stream()
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
		for (Fjords.Trough trunk : trunks(new GlaciationTest.CoastSource(5f, 0.2f, 40f))) {
			assertTrue(mouthColumn(trunk) >= 0, "a fjord opens on the plain at " + mouthColumn(trunk));
		}
	}

	@Test
	void neighbouringBlocksAgreeToTheBit() {
		Fjords fjords = new Fjords(COAST, 1L);
		Map<Long, Fjords.Trough> first = new HashMap<>();
		for (Fjords.Trough trough : fjords.block(0, 0)) {
			first.put(trough.id(), trough);
		}
		int shared = 0, arms = 0;
		for (Fjords.Trough trunk : fjords.block(0, 1)) {
			Fjords.Trough other = first.get(trunk.id());
			if (other != null) {
				shared++;
				arms += trunk.arm() > 0 ? 1 : 0;
				assertArrayEquals(other.rows(), trunk.rows());
				assertArrayEquals(other.columns(), trunk.columns());
				assertArrayEquals(other.floor(), trunk.floor());
				assertArrayEquals(other.halfWidth(), trunk.halfWidth());
			}
		}
		assertTrue(shared > 0 && arms > 0, "the blocks share fjords and their arms: " + shared + ", " + arms);
	}

	@Test
	void overlappingSamplesAcrossAFjordAgreeToTheBit() {
		Fjords.Trough trunk = trunks(COAST).getFirst();
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
	void armsBranchUpTheSideValleys() {
		List<Fjords.Trough> troughs = new Fjords(BRANCHING, 1L).near(-4, -64, 4, 64);
		Map<Long, Fjords.Trough> trunks = new HashMap<>();
		for (Fjords.Trough trough : troughs) {
			if (trough.arm() == 0) {
				trunks.put(trough.key(), trough);
			}
		}
		TerrainField field = new TerrainField(1, BRANCHING);
		TerrainField.Column column = new TerrainField.Column();
		int arms = 0;
		for (Fjords.Trough arm : troughs) {
			if (arm.arm() == 0) {
				continue;
			}
			arms++;
			// It branches off its trunk, on the trunk's floor, between the mouth and the head.
			Fjords.Trough trunk = trunks.get(arm.key());
			assertNotNull(trunk);
			int k = trunk.mouth();
			while (k < trunk.head() && (trunk.rows()[k] != arm.rows()[0] || trunk.columns()[k] != arm.columns()[0])) {
				k++;
			}
			assertTrue(k > trunk.mouth() && k < trunk.head(), "the arm branches off the trunk");
			assertEquals(trunk.floor()[k], arm.floor()[0]);
			// It runs up a side valley, away from the main one.
			int headRow = (int) Math.floor(arm.rows()[arm.head()]);
			int headColumn = (int) Math.floor(arm.columns()[arm.head()]);
			assertEquals(0, Math.floorMod(headRow, GlaciationTest.CoastSource.SIDE_SPACING), "in a side valley");
			assertTrue(Math.abs(headColumn - mouthColumn(trunk)) >= Fjords.ARM_MIN_STEPS, "away from the trunk");
			// The water runs from the trunk all the way to the arm's head.
			float[] floor = arm.floor();
			for (int a = 0; a <= arm.head(); a++) {
				assertTrue(floor[a] <= -Fjords.HEAD_DEPTH + 1e-3f, "the water runs all the way to the head");
			}
			assertEquals(-Fjords.HEAD_DEPTH, floor[arm.head()], 1e-3f);
			assertTrue(floor[floor.length - 1] > 0f, "past the head, the valley climbs out of the sea");
			for (int a = 0; a <= arm.head(); a += Fjords.SAMPLES / 4) {
				int i = (int) Math.floor(arm.rows()[a] * SCALE), j = (int) Math.floor(arm.columns()[a] * SCALE);
				field.column(i, j, column);
				assertTrue(column.elevation < 0f && Glaciation.drowned(column.elevation, column.iceCut),
					"at " + i + ", " + j + ": " + column.elevation + " m, cut " + column.iceCut);
			}
		}
		assertTrue(arms >= 3, arms + " arms on " + trunks.size() + " fjords");
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

	@Test
	void roughWallsStandOutAndBackButStillRiseFromAClearFloor() {
		for (float r = -Fjords.ROUGHNESS; r <= Fjords.ROUGHNESS; r += Fjords.ROUGHNESS / 4f) {
			assertEquals(-30f, Fjords.rough(-30f, r), "the floor is left as it is");
			assertEquals(0f, Fjords.rough(0f, r));
			for (float x = 10f; x <= Fjords.WALL_REACH; x += 10f) {
				assertTrue(Fjords.rough(x, r) > Fjords.rough(x - 10f, r), "the wall turns back at " + x + " by " + r);
			}
		}
		Fjords fjords = new Fjords(COAST, 1L);
		Fjords.Trough trunk = trunks(COAST).getFirst();
		int k = trunk.mouth() + 2 * Fjords.SAMPLES, size = 256, plane = size * size;
		int ci = (int) Math.floor(trunk.rows()[k] * SCALE), cj = (int) Math.floor(trunk.columns()[k] * SCALE);
		float[] carving = fjords.carve(List.of(trunk), SCALE, ci - size / 2, cj - size / 2, size, size);
		float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
		for (int px = 0; px < plane; px++) {
			if (carving[Fjords.BEYOND * plane + px] != Float.POSITIVE_INFINITY) {
				float r = carving[Fjords.ROUGH * plane + px];
				assertTrue(Math.abs(r) <= Fjords.ROUGHNESS, r + " m");
				low = Math.min(low, r);
				high = Math.max(high, r);
			}
		}
		assertTrue(high - low > Fjords.ROUGHNESS, "along the shore, the walls stand out and back: " + low + " to " + high);
	}
}
