package io.github.elpideus.diffusicraft.worldgen.geology;

import io.github.elpideus.diffusicraft.worldgen.geology.Soils.Horizon;
import io.github.elpideus.diffusicraft.worldgen.geology.Soils.Soil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoilsTest {
	private static final Soils SOILS = new Soils(0x5011_5EEDL);

	/** A temperate, humid climate: 10 °C and 900 mm. */
	private static final float MILD = 10;
	private static final float MILD_RAIN = 900;

	private static Soils.Profile profile(Rock rock, float temperature, float precipitation, double slope, int x,
										 int z) {
		Soils.Profile p = new Soils.Profile();
		SOILS.profile(rock, temperature, precipitation, slope, false, x, z, p);
		return p;
	}

	@Test
	void theSoilFollowsTheRockBeneath() {
		assertEquals(Soil.SAND, profile(Rock.SANDSTONE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.RED_SAND, profile(Rock.RED_SANDSTONE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.CLAY, profile(Rock.SHALE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.CLAY, profile(Rock.SLATE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.GRUS, profile(Rock.GRANITE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.GRUS, profile(Rock.GNEISS, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.LOAM, profile(Rock.STONE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		// Limestone dissolves: a thin dark soil in the cool, red terra rossa in the warm with dry summers.
		assertEquals(Soil.LOAM, profile(Rock.LIMESTONE, MILD, MILD_RAIN, 0.3, 0, 0).soil());
		assertEquals(Soil.TERRA_ROSSA, profile(Rock.LIMESTONE, 16, 600, 0.3, 0, 0).soil());
		assertEquals(Soil.TERRA_ROSSA, profile(Rock.CHALK, 16, 600, 0.3, 0, 0).soil());
		// Shale makes clay only where it is wet enough.
		assertEquals(Soil.LOAM, profile(Rock.SHALE, 16, 300, 0.3, 0, 0).soil());
	}

	@Test
	void theClimateOverridesTheRockWhereItIsStrongest() {
		for (Rock rock : Rock.values()) {
			assertEquals(Soil.LATERITE, profile(rock, 25, 2200, 0.1, 0, 0).soil(), "the wet tropics on " + rock);
			assertEquals(Soil.LATERITE, profile(rock, 24, 1000, 0.1, 0, 0).soil(), "a savanna on " + rock);
			assertEquals(Soil.LOESS, profile(rock, 6, 300, 0.05, 0, 0).soil(), "the cool, dry plains on " + rock);
			Soils.Profile bog = new Soils.Profile();
			SOILS.profile(rock, 6, 1200, 0.05, true, 0, 0, bog);
			assertEquals(Soil.CLAY, bog.soil(), "gley under peat on " + rock);
		}
		// Loess blankets the plains, not the hills.
		assertEquals(Soil.GRUS, profile(Rock.GRANITE, 6, 300, 0.4, 0, 0).soil());
	}

	@Test
	void semiAridLandHasACalcretePan() {
		int pans = 0;
		for (int x = 0; x < 2000; x += 37) {
			Soils.Profile p = profile(Rock.STONE, 18, 500, 0.3, x, 0);
			if (p.subsoil() < p.weathered() && p.horizon(p.subsoil() + 1) == Horizon.PAN) {
				pans++;
			}
			Soils.Profile wet = profile(Rock.STONE, 18, 1100, 0.3, x, 0);
			for (int depth = 1; depth < 40; depth++) {
				assertTrue(wet.horizon(depth) != Horizon.PAN, "no pan where the rain sinks through");
			}
		}
		assertEquals(55, pans);
	}

	@Test
	void profilesRunInOrderDownToTheRock() {
		for (int x = -3000; x < 3000; x += 97) {
			for (int z = -3000; z < 3000; z += 89) {
				for (Rock rock : new Rock[]{Rock.GRANITE, Rock.LIMESTONE, Rock.SHALE}) {
					for (float t : new float[]{-12, 4, 12, 20, 27}) {
						for (float rain : new float[]{150, 450, 900, 2500}) {
							for (double slope : new double[]{0, 0.2, 0.45}) {
								Soils.Profile p = profile(rock, t, rain, slope, x, z);
								assertTrue(p.topsoil() >= 2, "a block of topsoil under the top");
								Horizon last = Horizon.TOPSOIL;
								for (int depth = 1; depth < 60; depth++) {
									Horizon h = p.horizon(depth);
									assertTrue(h.ordinal() >= last.ordinal(), "horizons in order");
									last = h;
								}
								assertEquals(Horizon.WEATHERED, p.horizon(p.weathered()), "weathered rock");
								assertEquals(Horizon.ROCK, p.horizon(p.weathered() + 1));
							}
						}
					}
				}
			}
		}
	}

	@Test
	void warmWetFlatLandWeathersDeepest() {
		double tropics = 0;
		double temperate = 0;
		double cold = 0;
		double slopes = 0;
		int n = 0;
		for (int x = -4000; x < 4000; x += 61) {
			for (int z = -4000; z < 4000; z += 67) {
				tropics += profile(Rock.GRANITE, 26, 2500, 0.05, x, z).weathered();
				temperate += profile(Rock.GRANITE, MILD, MILD_RAIN, 0.05, x, z).weathered();
				cold += profile(Rock.GRANITE, -6, 350, 0.05, x, z).weathered();
				slopes += profile(Rock.GRANITE, MILD, MILD_RAIN, 0.45, x, z).weathered();
				n++;
			}
		}
		tropics /= n;
		temperate /= n;
		cold /= n;
		slopes /= n;
		// Deeper than a few blocks in the tropics, about vanilla's dirt in temperate land, thin in the cold.
		assertTrue(tropics > 14, "the tropics: " + tropics);
		assertTrue(temperate > 5 && temperate < 9, "temperate land: " + temperate);
		assertTrue(cold < 5.5, "the cold: " + cold);
		assertTrue(slopes < temperate - 1.5, "slopes: " + slopes + ", flat: " + temperate);
	}

	@Test
	void weatheredRockTurnsToSolidRockDownwards() {
		Soils.Profile p = profile(Rock.GRANITE, 26, 2500, 0.05, 0, 0);
		int top = 0;
		int bottom = 0;
		int n = 0;
		for (int x = 0; x < 64; x++) {
			for (int z = 0; z < 64; z++) {
				n++;
				top += SOILS.rubble(p, x, 100, z, p.subsoil() + 1) ? 1 : 0;
				bottom += SOILS.rubble(p, x, 100, z, p.weathered()) ? 1 : 0;
			}
		}
		assertTrue(top < 0.55 * n && bottom > 0.6 * n && bottom > top + 0.2 * n, top + " near the soil, " + bottom
			+ " near the rock, of " + n);
	}

	@Test
	void bauxiteLiesUnderTheFlatLandOfTheWetTropics() {
		int patches = 0;
		int n = 0;
		for (int x = -4000; x < 4000; x += 61) {
			for (int z = -4000; z < 4000; z += 67) {
				n++;
				Soils.Profile p = profile(Rock.GRANITE, 26, 2500, 0.05, x, z);
				if (p.hasBauxite()) {
					patches++;
					for (int depth = 1; depth < 40; depth++) {
						if (SOILS.bauxite(p, x, 100 - depth, z, depth)) {
							assertTrue(depth > p.topsoil() + 1 && depth <= p.subsoil(), "in the lower subsoil");
						}
					}
				}
				assertFalse(profile(Rock.GRANITE, 26, 2500, 0.4, x, z).hasBauxite(), "not on slopes");
				assertFalse(profile(Rock.GRANITE, MILD, MILD_RAIN, 0.05, x, z).hasBauxite(), "not out of the tropics");
				assertFalse(profile(Rock.GRANITE, 24, 1000, 0.05, x, z).hasBauxite(), "not in the drier savannas");
			}
		}
		assertTrue(patches > 0.1 * n && patches < 0.6 * n, patches + " of " + n);
	}
}
