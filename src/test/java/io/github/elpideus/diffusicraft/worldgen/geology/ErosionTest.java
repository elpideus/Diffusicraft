package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErosionTest {
	private static final Erosion EROSION = new Erosion(0x1234_5678_9ABCL);

	/**
	 * A plateau at y = 300 breaking off in a cliff of slope 3 at downhill distance 0, a talus of slope 0.7 from 30 to
	 * 50 and flat ground beyond.
	 */
	private static double profile(double d) {
		if (d < 0) {
			return 300;
		}
		if (d < 30) {
			return 300 - 3 * d;
		}
		return d < 50 ? 210 - 0.7 * (d - 30) : 196;
	}

	@Test
	void gentleGroundKeepsItsSoilAndSteepGroundShowsTheRock() {
		for (int x = -300; x < 300; x += 7) {
			for (int z = -300; z < 300; z += 11) {
				assertEquals(Erosion.Kind.SOIL, EROSION.kind((px, pz) -> 0.25 * px - 0.15 * pz, x, z));
				assertEquals(Erosion.Kind.CLIFF, EROSION.kind((px, pz) -> 2 * pz, x, z));
				assertEquals(Erosion.Kind.CLIFF, EROSION.kind((px, pz) -> 1.5 * px + 1.5 * pz, x, z));
			}
		}
	}

	@Test
	void screeLiesUnderCliffsWhicheverWayTheyFace() {
		// The downhill distance of a point, and the column whose middle lies at downhill distance d + 0.5.
		record Facing(Erosion.Surface surface, int dx, int dz, int x0, int z0) {
		}
		List<Facing> facings = List.of(
			new Facing((x, z) -> profile(x), 1, 0, 0, 0),
			new Facing((x, z) -> profile(-x), -1, 0, -1, 0),
			new Facing((x, z) -> profile(z), 0, 1, 0, 0));
		for (Facing f : facings) {
			for (int across = -400; across < 400; across += 23) {
				for (int d = -40; d < 80; d++) {
					int x = f.x0 + f.dx * d + (f.dz != 0 ? across : 0);
					int z = f.z0 + f.dz * d + (f.dx != 0 ? across : 0);
					Erosion.Kind kind = EROSION.kind(f.surface, x, z);
					String where = "downhill " + d + " at " + x + ", " + z;
					if (d < -2 || d >= 52) {
						assertEquals(Erosion.Kind.SOIL, kind, "flat ground " + where);
					} else if (d >= 1 && d < 29) {
						assertEquals(Erosion.Kind.CLIFF, kind, "the cliff " + where);
					} else if (d >= 31 && d < 36) {
						assertEquals(Erosion.Kind.SCREE, kind, "the foot of the cliff " + where);
					}
				}
			}
		}
	}

	@Test
	void screeIsAboutHalfGravel() {
		int gravel = 0, total = 0;
		for (int x = 0; x < 64; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 64; z++) {
					gravel += EROSION.gravel(x, y, z) ? 1 : 0;
					total++;
				}
			}
		}
		assertEquals(0.55, gravel / (double) total, 0.02);
	}
}
