package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sea cliffs of {@link TerrainSurface}, along straight coasts running north to south with the sea to the west. */
class SeaCliffTest {
	/** Land rising 30 m per pixel from the coastline at pixel column 100 to a plateau at 90 m. */
	private static final FormulaSource.Elevation HIGH = (i, j) -> (float) Math.min(90, 30 * (j - 100));
	/** Land rising 1 m per pixel to 3 m: a coast too low for cliffs. */
	private static final FormulaSource.Elevation LOW = (i, j) -> (float) Math.min(3, j - 100);

	private static TerrainSurface surface(FormulaSource.Elevation elevation, WorldScale scale) {
		return new TerrainField(7, new FormulaSource(elevation)).surface(scale);
	}

	/** The first block column east of the coastline. */
	private static int coast(WorldScale scale) {
		return (int) Math.ceil(100.5 / scale.pixelsPerBlock() - 0.5);
	}

	/** The ground across the coast at {@code z}, from 10 blocks out to sea to 10 blocks past the strip of cliffs. */
	private static float[] profile(TerrainSurface surface, WorldScale scale, int z) {
		int x0 = coast(scale) - 10, n = (int) TerrainSurface.cliffZone(scale) + 20;
		float[] y = new float[n];
		for (int k = 0; k < n; k++) {
			y[k] = surface.y(x0 + k, z);
		}
		return y;
	}

	/** The highest rise between neighbouring columns of a profile, and where it is. */
	private static int face(float[] profile) {
		int best = 0;
		for (int k = 1; k < profile.length - 1; k++) {
			if (profile[k + 1] - profile[k] > profile[best + 1] - profile[best]) {
				best = k;
			}
		}
		return best;
	}

	private static float rise(float[] profile, int at) {
		return profile[at + 1] - profile[at];
	}

	@Test
	void someStretchesOfAHighCoastHaveCliffs() {
		for (WorldScale scale : new WorldScale[]{WorldScale.EARTH, WorldScale.STANDARD}) {
			TerrainSurface surface = surface(HIGH, scale);
			// The land rises a block or so per block; a face rises a good part of the plateau's height at once.
			float cliff = (scale.surfaceY(90f) - scale.seaLevel()) * 0.4f;
			int cliffs = 0, profiles = 0;
			for (int z = 0; z < 40_000 / scale.metersPerBlock(); z += (int) (250 / scale.metersPerBlock())) {
				float[] y = profile(surface, scale, z);
				if (rise(y, face(y)) >= cliff) {
					cliffs++;
				}
				profiles++;
			}
			double share = cliffs / (double) profiles;
			assertTrue(share > 0.25 && share < 0.95, scale + ": " + cliffs + " cliffs in " + profiles);
		}
	}

	@Test
	void theFootOfAFullCliffIsAShelfABlockAboveTheWater() {
		WorldScale scale = WorldScale.EARTH;
		TerrainSurface surface = surface(HIGH, scale);
		// The top of a cliff is the ground at the end of the strip, two pixels inland.
		float shelf = scale.seaLevel() + 0.5f, top = scale.surfaceY(HIGH.at(0, 102));
		int full = 0;
		for (int z = 0; z < 40_000; z += 97) {
			float[] y = profile(surface, scale, z);
			int face = face(y);
			// Along the stretches where cliffs fade out, the slope comes back under the face as it shrinks.
			if (rise(y, face) < 0.97f * (top - shelf)) {
				continue;
			}
			full++;
			for (int k = 10; k <= face; k++) {
				assertTrue(y[k] <= shelf + 2.5f, "z " + z + ": " + y[k] + " at " + (k - 10));
			}
			assertEquals(shelf, y[face], 2f, "z " + z);
		}
		assertTrue(full > 10, "full cliffs: " + full);
	}

	@Test
	void theCliffMeetsTheGroundAtTheEndOfItsStrip() {
		for (WorldScale scale : new WorldScale[]{WorldScale.EARTH, WorldScale.GRAND, WorldScale.STANDARD}) {
			TerrainSurface surface = surface(HIGH, scale);
			int end = coast(scale) + (int) Math.ceil(TerrainSurface.cliffZone(scale));
			for (int z = 0; z < 20_000 / scale.metersPerBlock(); z += 7) {
				// The plateau is flat; its edge, where the land rises to it, lies inside the strip.
				for (int x = end - 1; x <= end + 3; x++) {
					assertEquals(surface.y(x, z), surface.y(x + 1, z), 2.5f, scale + " " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void aLowCoastHasNoCliffs() {
		WorldScale scale = WorldScale.EARTH;
		TerrainSurface surface = surface(LOW, scale);
		for (int z = 0; z < 40_000; z += 211) {
			float[] y = profile(surface, scale, z);
			// The sea floor lies a block under the shore.
			assertTrue(rise(y, face(y)) < 1.5f, "z " + z + ": " + rise(y, face(y)));
			for (int x = coast(scale) - 10; x < coast(scale) + 70; x += 3) {
				assertTrue(Float.isNaN(surface.cliff(x, z)));
			}
		}
	}

	@Test
	void theSeaFloorDeepensAwayFromAFlatShore() {
		// A shore that barely leaves the sea level: 0.02 m per pixel either way.
		FormulaSource.Elevation flat = (i, j) -> (float) (0.02 * (j - 100));
		WorldScale earth = WorldScale.EARTH;
		TerrainSurface surface = surface(flat, earth);
		// The coastline runs through block 3014.5, a block a meter.
		for (int z = 0; z < 2000; z += 173) {
			float previous = earth.seaLevel();
			for (int d = 5; d < 600; d += 5) {
				int x = 3014 - d;
				float y = surface.y(x, z);
				double depth = 0.1 * Math.pow(Math.min(d + 0.5, TerrainField.COAST_REACH), 2.0 / 3.0);
				assertEquals(earth.seaLevel() - Math.max(1, depth), y, 0.3, "z " + z + ", " + d + " m out");
				assertTrue(y <= previous + 1e-3f, "the floor never rises away from the shore: " + d + " m out");
				previous = y;
			}
		}
		// The other worlds keep the shallowest sea a block deep: the profile is less than a block there.
		for (WorldScale scale : new WorldScale[]{WorldScale.GRAND, WorldScale.STANDARD}) {
			TerrainSurface other = surface(flat, scale);
			for (int x = coast(scale) - 100; x < coast(scale) - 1; x += 3) {
				assertEquals(scale.seaLevel() - 1, other.y(x, 0), 1e-3f, scale + " " + x);
			}
		}
	}

	@Test
	void theFarLevelsOfDetailShowTheCliffs() {
		for (WorldScale scale : new WorldScale[]{WorldScale.EARTH, WorldScale.STANDARD}) {
			TerrainSurface surface = surface(HIGH, scale);
			float cliff = (scale.surfaceY(90f) - scale.seaLevel()) * 0.4f;
			int x0 = coast(scale) - 10, n = (int) TerrainSurface.cliffZone(scale) + 20;
			int cliffs = 0, profiles = 0;
			for (int z = 0; z < 40_000 / scale.metersPerBlock(); z += (int) (250 / scale.metersPerBlock())) {
				float[] far = new float[n];
				for (int k = 0; k < n; k++) {
					far[k] = surface.shore(x0 + k, z);
				}
				int face = face(far);
				if (rise(far, face) >= cliff) {
					cliffs++;
					// The face stands where the chunks have it.
					float[] near = profile(surface, scale, z);
					assertEquals(face, face(near), 1, scale + " z " + z);
				}
				profiles++;
			}
			double share = cliffs / (double) profiles;
			assertTrue(share > 0.25 && share < 0.95, scale + ": " + cliffs + " cliffs in " + profiles);
		}
	}

	@Test
	void theDensityTakesTheCliffAndTheSeaBesideItBlockByBlock() {
		WorldScale scale = WorldScale.EARTH;
		TerrainSurface surface = surface(HIGH, scale);
		int x0 = coast(scale), zone = (int) TerrainSurface.cliffZone(scale);
		int beside = 0;
		for (int z = 0; z < 40_000; z += 131) {
			float[] y = profile(surface, scale, z);
			boolean cliff = rise(y, face(y)) >= 25f;
			for (int x = x0 - 20; x < x0 + zone + 20; x++) {
				float column = surface.cliff(x, z);
				if (x < x0 - TerrainSurface.CLIFF_MARGIN || x > x0 + zone) {
					assertTrue(Float.isNaN(column), "outside the strip and its margin: " + x + ", " + z);
				} else if (!Float.isNaN(column)) {
					assertEquals(surface.y(x, z), column, 0f);
				} else if (x >= x0 && x < x0 + zone / 2) {
					assertFalse(cliff, "a cliff column the density would interpolate: " + x + ", " + z);
				}
			}
			if (cliff && !Float.isNaN(surface.cliff(x0 - 3, z))) {
				beside++;
			}
		}
		assertTrue(beside > 10, "the sea beside a cliff: " + beside);
	}
}
