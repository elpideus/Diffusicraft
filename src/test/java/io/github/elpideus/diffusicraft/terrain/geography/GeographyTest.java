package io.github.elpideus.diffusicraft.terrain.geography;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeographyTest {
	/** Probabilities and heights in meters of a table shaped like the real one (ETOPO, as the models ship it). */
	private static final double[][] REAL_SHAPE = {
		{0, -8780}, {0.02, -5800}, {0.1, -5180}, {0.3, -4190}, {0.5, -2230}, {0.55, -230}, {0.58, -20}, {0.6, 10},
		{0.65, 95}, {0.7, 200}, {0.8, 410}, {0.9, 835}, {0.95, 1240}, {0.99, 2745}, {1, 5810},
	};
	private static final float[] QUANTILES = quantiles(64);

	private static float[] quantiles(int n) {
		float[] q = new float[n];
		for (int k = 0; k < n; k++) {
			double p = k / (n - 1.0);
			int s = 1;
			while (s < REAL_SHAPE.length - 1 && REAL_SHAPE[s][0] < p) {
				s++;
			}
			double t = (p - REAL_SHAPE[s - 1][0]) / (REAL_SHAPE[s][0] - REAL_SHAPE[s - 1][0]);
			q[k] = (float) (REAL_SHAPE[s - 1][1] + t * (REAL_SHAPE[s][1] - REAL_SHAPE[s - 1][1]));
		}
		return q;
	}

	/** Points of land on a ring around the origin. */
	private static int landOnRing(Geography geography, double radius, int points) {
		int land = 0;
		for (int k = 0; k < points; k++) {
			double a = 2 * Math.PI * k / points;
			if (geography.elevation(radius * Math.cos(a), radius * Math.sin(a)) > 0) {
				land++;
			}
		}
		return land;
	}

	/** Whether the sea some way out from the origin is deeper than {@code depth} along some bearing, near and far. */
	private static boolean openSea(Geography geography, double near, double far, double depth) {
		for (int k = 0; k < 8; k++) {
			double a = 2 * Math.PI * k / 8;
			if (geography.elevation(near * Math.cos(a), near * Math.sin(a)) < depth
				&& geography.elevation(far * Math.cos(a), far * Math.sin(a)) < depth) {
				return true;
			}
		}
		return false;
	}

	@Test
	void aSeedAlwaysMapsTheSameWorld() {
		Geography a = new Geography(7, QUANTILES), b = new Geography(7, QUANTILES);
		assertEquals(a.spawnKind(), b.spawnKind());
		for (int k = 0; k < 64; k++) {
			double x = 997.0 * k - 30000, z = 31.0 * k * k - 20000;
			assertEquals(a.elevation(x, z), b.elevation(x, z));
		}
		assertEquals(a.latitude(500), b.latitude(500));
	}

	@Test
	void landTakesAboutTheShareOfTheRealTable() {
		Geography geography = new Geography(3, QUANTILES);
		int land = 0, total = 0;
		for (int a = 0; a < 300; a++) {
			for (int b = 0; b < 300; b++) {
				total++;
				// A square of 36,000 km: the continents are large enough that a smaller one around a coast can be
				// mostly land.
				if (geography.elevation(120.0 * b - 18000, 120.0 * a - 18000) > 0) {
					land++;
				}
			}
		}
		double share = land / (double) total;
		assertTrue(share > 0.25 && share < 0.55, "land share " + share);
	}

	@Test
	void theOriginIsTheKindOfPlaceAsked() {
		for (long seed = 1; seed <= 3; seed++) {
			for (SpawnKind kind : SpawnKind.values()) {
				Geography geography = new Geography(seed, QUANTILES, kind);
				assertEquals(kind, geography.spawnKind(), "seed " + seed);
				double height = geography.elevation(0, 0);
				String where = kind + " of seed " + seed + " at " + Math.round(height) + " m";
				switch (kind) {
					case COAST -> {
						assertTrue(height > 0 && height <= 150, where);
						assertTrue(landOnRing(geography, 16, 8) < 8, where + " has no sea near");
						assertTrue(openSea(geography, 30, 60, -100), where + " is on an inlet");
						assertTrue(landOnRing(geography, 150, 12) >= 5, where + " is not on a continent");
					}
					case INLAND -> {
						assertTrue(height >= 60 && height <= 900, where);
						assertEquals(12, landOnRing(geography, 100, 12), where + " is near the sea");
					}
					case ISLAND -> {
						assertTrue(height >= 150, where);
						assertEquals(0, landOnRing(geography, 120, 12), where + " has land near");
					}
					case HOLLOW -> {
						assertTrue(height >= 60 && height <= 1500, where);
						assertEquals(12, landOnRing(geography, 100, 12), where + " is near the sea");
					}
					case PEAK -> assertTrue(height >= 3000, where);
				}
			}
		}
	}

	/** The highest ground within {@code reach} km of the origin, on rings 2 km apart. */
	private static double highestWithin(Geography geography, double reach) {
		double highest = geography.elevation(0, 0);
		for (double radius = 2; radius <= reach; radius += 2) {
			int points = (int) Math.ceil(Math.PI * radius);
			for (int k = 0; k < points; k++) {
				double a = 2 * Math.PI * k / points;
				highest = Math.max(highest, geography.elevation(radius * Math.cos(a), radius * Math.sin(a)));
			}
		}
		return highest;
	}

	@Test
	void mostCoastsHaveMountainsInView() {
		int seeds = 12, inView = 0;
		for (long seed = 1; seed <= seeds; seed++) {
			if (highestWithin(new Geography(seed, QUANTILES, SpawnKind.COAST), 30) >= 1500) {
				inView++;
			}
		}
		// Eight of these twelve have them, and none did before the origin looked for them.
		assertTrue(inView >= seeds / 2, inView + " of " + seeds + " coasts have mountains in view");
	}

	@Test
	void seedsDrawTheKindsByTheirChances() {
		double sum = 0;
		for (SpawnKind kind : SpawnKind.values()) {
			sum += kind.chance();
		}
		assertEquals(1, sum, 1e-9);
		assertTrue(SpawnKind.COAST.chance() > SpawnKind.INLAND.chance());
		assertTrue(SpawnKind.INLAND.chance() > SpawnKind.ISLAND.chance());
		assertTrue(SpawnKind.ISLAND.chance() > SpawnKind.HOLLOW.chance());
		assertEquals(SpawnKind.HOLLOW.chance(), SpawnKind.PEAK.chance());

		Map<SpawnKind, Integer> drawn = new EnumMap<>(SpawnKind.class);
		int n = 100_000;
		for (int k = 0; k < n; k++) {
			drawn.merge(SpawnKind.draw((k + 0.5) / n), 1, Integer::sum);
		}
		for (SpawnKind kind : SpawnKind.values()) {
			assertEquals(kind.chance(), drawn.getOrDefault(kind, 0) / (double) n, 1e-4, kind.toString());
		}
	}
}
