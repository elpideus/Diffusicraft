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

	/** Blocks out to sea from the coastline that stacks can reach at a scale, and a little more. */
	private static int stackReach(WorldScale scale) {
		return (int) Math.ceil(12 / scale.pixelsPerBlock()) + 2;
	}

	@Test
	void stacksStandOffSomeCliffsPastOpenWater() {
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = surface(HIGH, scale);
			int sea = scale.seaLevel(), x1 = coast(scale) - 1, x0 = x1 - stackReach(scale);
			float cliffTop = scale.surfaceY(90f);
			int length = (int) (100_000 / scale.metersPerBlock()), stacks = 0, columns = 0;
			// The stacks of the Earth 1:1 world are at least 9 blocks wide.
			int step = scale == WorldScale.EARTH ? 3 : 1;
			boolean previous = false;
			for (int z = 0; z < length; z++) {
				boolean row = false;
				// Walking out to sea from the coastline: water first, then the stacks.
				boolean water = false;
				for (int x = x1; x >= x0; x -= step) {
					float y = surface.y(x, z);
					if (y <= sea) {
						water = true;
						continue;
					}
					assertTrue(water, scale + ": a stack joined to the land at " + x + ", " + z);
					assertTrue(y <= cliffTop + 0.5f, scale + ": a stack above the cliff at " + x + ", " + z + ": " + y);
					// The density takes the stack and the sea around it block by block.
					for (int d = -4; d <= 4 && (x + z) % 5 == 0; d++) {
						assertEquals(surface.y(x + d, z), surface.cliff(x + d, z), 0f, scale + " " + (x + d) + ", " + z);
						assertEquals(surface.y(x, z + d), surface.cliff(x, z + d), 0f, scale + " " + x + ", " + (z + d));
					}
					row = true;
					columns++;
				}
				if (row && !previous) {
					stacks++;
				}
				previous = row;
			}
			double perKm = stacks / (length * scale.metersPerBlock() / 1000);
			System.out.println(scale + ": " + stacks + " stacks, " + columns + " columns, " + perKm + " per km");
			assertTrue(perKm > 0.2 && perKm < 4, scale + ": " + stacks + " stacks along 100 km of cliffs");
		}
	}

	@Test
	void aLowCoastHasNoStacks() {
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = surface(LOW, scale);
			int x1 = coast(scale) - 1, x0 = x1 - stackReach(scale);
			for (int z = 0; z < 20_000 / scale.metersPerBlock(); z++) {
				for (int x = x0; x <= x1; x++) {
					assertTrue(surface.y(x, z) < scale.seaLevel(), scale + " " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void theSeaFlowsThroughTheArches() {
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = surface(HIGH, scale);
			int sea = scale.seaLevel(), x1 = coast(scale) - 1, x0 = x1 - stackReach(scale);
			int length = (int) (100_000 / scale.metersPerBlock()), openings = 0;
			float highest = 0;
			float[] hole = new float[2];
			for (int z = 0; z < length; z++) {
				boolean open = false;
				for (int x = x1; x >= x0; x--) {
					float y = surface.cliff(x, z, hole);
					if (Float.isNaN(hole[1])) {
						continue;
					}
					assertTrue(hole[0] < sea, scale + ": the opening's floor lies under the sea: " + hole[0]);
					assertTrue(y - hole[1] >= 2f - 1e-3f, scale + ": the roof over an opening: " + (y - hole[1]));
					highest = Math.max(highest, hole[1] - sea);
					open = true;
				}
				if (open) {
					// The coast runs along z: across an arch, every column of it is open.
					int closed = 0, opened = 0;
					for (int x = x1; x >= x0 - 1; x--) {
						float y = x >= x0 ? surface.cliff(x, z, hole) : sea;
						if (y > sea) {
							if (Float.isNaN(hole[1])) {
								closed++;
							} else {
								opened++;
							}
						} else {
							assertFalse(closed > 0 && opened > 0, scale + ": a closed column across an arch at z " + z);
							closed = opened = 0;
						}
					}
					openings++;
				}
			}
			System.out.println(scale + ": " + openings + " rows through arches, highest " + highest);
			assertTrue(openings > 0 && highest >= 3, scale + ": " + openings + " rows, highest " + highest);
		}
	}

	@Test
	void theDensityTakesTheCliffAndTheSeaBesideItBlockByBlock() {
		WorldScale scale = WorldScale.EARTH;
		TerrainSurface surface = surface(HIGH, scale);
		int x0 = coast(scale), zone = (int) TerrainSurface.cliffZone(scale);
		int beside = 0, stacks = 0, outside = 0;
		for (int z = 0; z < 40_000; z += 131) {
			float[] y = profile(surface, scale, z);
			boolean cliff = rise(y, face(y)) >= 25f;
			for (int x = x0 - 20; x < x0 + zone + 20; x++) {
				float column = surface.cliff(x, z);
				if (x < x0 - TerrainSurface.CLIFF_MARGIN) {
					outside++;
				}
				if (x > x0 + zone) {
					assertTrue(Float.isNaN(column), "inland of the strip: " + x + ", " + z);
				} else if (x < x0 - TerrainSurface.CLIFF_MARGIN && !Float.isNaN(column)) {
					// Out to sea, only around the stacks.
					assertEquals(surface.y(x, z), column, 0f);
					stacks++;
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
		assertTrue(stacks < 0.1 * outside, "around the stacks: " + stacks + " of " + outside);
	}
}
