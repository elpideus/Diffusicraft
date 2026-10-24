package io.github.elpideus.diffusicraft.worldgen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coasts of {@link TerrainSurface}, along straight coasts running north to south with the sea to the west: the
 * land easing down to the water, the sea cliffs and the islets.
 */
class SeaCliffTest {
	/** Land rising 30 m per pixel from the coastline at pixel column 100 to a plateau at 90 m: a high coast. */
	private static final FormulaSource.Elevation HIGH = (i, j) -> (float) Math.min(90, 30 * (j - 100));
	/** Land rising 6 m per pixel, 0.2 m per meter, to the same plateau: a gentle coast the easing leaves alone. */
	private static final FormulaSource.Elevation GENTLE = (i, j) -> (float) Math.min(90, 6 * (j - 100));
	/** Land rising 1 m per pixel to 3 m: a coast too low for cliffs. */
	private static final FormulaSource.Elevation LOW = (i, j) -> (float) Math.min(3, j - 100);
	/** The high coast over a shelf 4 m deep at most, as off a real shore, where the sea floor is too flat for ledges. */
	private static final FormulaSource.Elevation SHELF = (i, j) -> (float) (j >= 100 ? Math.min(90, 30 * (j - 100))
		: Math.max(-4, j - 100));

	private static TerrainSurface surface(FormulaSource.Elevation elevation, WorldScale scale) {
		return new TerrainField(7, new FormulaSource(elevation)).surface(scale);
	}

	/**
	 * The sizes with islets: every one but 1:32, where the distance to the coastline reads only 11 blocks out to sea,
	 * too short for an islet and the open water around it.
	 */
	private static final WorldScale[] WITH_ISLETS = Arrays.stream(WorldScale.values())
		.filter(scale -> scale != WorldScale.VANILLA).toArray(WorldScale[]::new);

	/** The first block column east of the coastline. */
	private static int coast(WorldScale scale) {
		return (int) Math.ceil(100.5 / scale.pixelsPerBlock() - 0.5);
	}

	/**
	 * The ground across the coast at {@code z}, from 10 blocks out to sea to 10 blocks past the strip of cliffs: the
	 * chunks' ground, or the far levels of detail's, which has the shapes of the shore without the ledges and gullies.
	 */
	private static float[] profile(TerrainSurface surface, WorldScale scale, int z, boolean far) {
		int x0 = coast(scale) - 10, n = (int) TerrainSurface.cliffZone(scale) + 20;
		float[] y = new float[n];
		for (int k = 0; k < n; k++) {
			y[k] = far ? surface.shore(x0 + k, z) : surface.y(x0 + k, z);
		}
		return y;
	}

	/** The highest climb between neighbouring columns of a profile, from column {@code from} on. */
	private static float steepest(float[] profile, int from) {
		float steepest = 0;
		for (int k = from; k < profile.length - 1; k++) {
			steepest = Math.max(steepest, profile[k + 1] - profile[k]);
		}
		return steepest;
	}

	/** The steepest a cliff's face climbs at a scale, in blocks per block, halfway up a face of the steepest pace. */
	private static double steepestFace(WorldScale scale) {
		double pace = Math.max(TerrainSurface.FACE_STEEP_MAX,
			TerrainSurface.cliffHeightMax(scale) / (TerrainSurface.FACE_RUN * TerrainSurface.cliffZone(scale)));
		return (1 + TerrainSurface.FACE_ROUNDING / 2) * pace;
	}

	/** Whether a cliff stands whole at the coastline at {@code z}. */
	private static boolean whole(TerrainSurface surface, WorldScale scale, int z) {
		return surface.cliffStrength(coast(scale) + 1, z) > 0.99;
	}

	/**
	 * Where the shelf of a whole cliff ends on a profile: the last column a block above the water, or lower, where a
	 * gully reaches the sea.
	 */
	private static int foot(float[] profile, WorldScale scale) {
		float shelf = scale.seaLevel() + 0.5f;
		int foot = 10;
		while (foot + 1 < profile.length && profile[foot + 1] < shelf + 0.25f) {
			foot++;
		}
		return foot;
	}

	/**
	 * Where the face of a whole cliff ends on a profile: from its foot up to where its climb, past its steepest, slows
	 * to half of that. The crest and the ground above it climb no faster.
	 */
	private static int crest(float[] profile, int foot) {
		float peak = 0;
		for (int k = foot; k < profile.length - 1; k++) {
			float step = profile[k + 1] - profile[k];
			peak = Math.max(peak, step);
			if (step < 0.5f * peak) {
				return k;
			}
		}
		return profile.length - 1;
	}

	/** Whether a cliff shapes any column of the strip at {@code z}. */
	private static boolean hasCliff(TerrainSurface surface, WorldScale scale, int z) {
		int x0 = coast(scale), end = x0 + (int) TerrainSurface.cliffZone(scale);
		for (int x = x0; x < end; x++) {
			if (!Float.isNaN(surface.cliff(x, z))) {
				return true;
			}
		}
		return false;
	}

	@Test
	void someStretchesOfAHighCoastHaveCliffs() {
		for (WorldScale scale : new WorldScale[]{WorldScale.ULTRA, WorldScale.MEDIUM}) {
			TerrainSurface surface = surface(HIGH, scale);
			int cliffs = 0, whole = 0, profiles = 0;
			for (int z = 0; z < 60_000 / scale.metersPerBlock(); z += (int) (250 / scale.metersPerBlock())) {
				if (hasCliff(surface, scale, z)) {
					cliffs++;
				}
				if (whole(surface, scale, z)) {
					whole++;
				}
				profiles++;
			}
			// Cliffs are rare: on a stretch of coast here and there, and whole on fewer.
			double share = cliffs / (double) profiles, wholeShare = whole / (double) profiles;
			assertTrue(share > 0.04 && share < 0.2, scale + ": " + cliffs + " cliffs in " + profiles);
			assertTrue(wholeShare > 0.01 && wholeShare < share, scale + ": " + whole + " whole cliffs in " + profiles);
		}
	}

	@Test
	void aCliffIsNeverAWall() {
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = surface(HIGH, scale);
			double steepest = steepestFace(scale);
			float found = 0;
			for (int z = 0; z < 60_000 / scale.metersPerBlock(); z += 7) {
				if (!hasCliff(surface, scale, z)) {
					continue;
				}
				// From the sea beside the coastline, whose floor the smaller sizes take down in steps.
				float climb = steepest(profile(surface, scale, z, true), 9);
				assertTrue(climb <= steepest + 0.25, scale + " z " + z + ": a column climbs " + climb + " blocks");
				found = Math.max(found, climb);
			}
			assertTrue(found > 1, scale + ": the steepest climb " + found);
		}
		// At 1:1, a face climbs at most four blocks a block, the old walls a whole face in one.
		assertTrue(steepestFace(WorldScale.ULTRA) < 4);
	}

	@Test
	void aCliffIsNeverTallerThanItsCap() {
		WorldScale scale = WorldScale.ULTRA;
		TerrainSurface surface = surface(HIGH, scale);
		float shelf = scale.seaLevel() + 0.5f, tallest = 0, lowest = Float.MAX_VALUE;
		int whole = 0;
		for (int z = 0; z < 60_000; z += 7) {
			if (!whole(surface, scale, z)) {
				continue;
			}
			whole++;
			float[] y = profile(surface, scale, z, true);
			float face = y[crest(y, foot(y, scale))] - shelf;
			assertTrue(face <= TerrainSurface.cliffHeightMax(scale) + 1, "z " + z + ": a face of " + face);
			tallest = Math.max(tallest, face);
			lowest = Math.min(lowest, face);
		}
		assertTrue(whole > 100, "whole cliffs: " + whole);
		// The crest rises and falls along the coast; the plateau stands 90 m high, and would give a face of all of it.
		assertTrue(tallest > TerrainSurface.cliffHeightMin(scale), "tallest face " + tallest);
		assertTrue(lowest < 0.6 * tallest, "lowest face " + lowest + ", tallest " + tallest);
	}

	@Test
	void theFootOfAWholeCliffIsAShelfABlockAboveTheWater() {
		for (WorldScale scale : new WorldScale[]{WorldScale.ULTRA, WorldScale.GRAND}) {
			TerrainSurface surface = surface(HIGH, scale);
			float shelf = scale.seaLevel() + 0.5f;
			double zone = TerrainSurface.cliffZone(scale);
			int whole = 0, inland = 0;
			for (int z = 0; z < 60_000 / scale.metersPerBlock(); z += 7) {
				if (!whole(surface, scale, z)) {
					continue;
				}
				whole++;
				float[] y = profile(surface, scale, z, true);
				assertEquals(shelf, y[10], 0.1f, scale + " z " + z + ": the first block of land");
				// The foot wanders from the waterline to halfway across the strip, and the ground climbs from there.
				int foot = foot(y, scale);
				assertTrue(foot - 10 <= 0.6 * zone, scale + " z " + z + ": the foot " + (foot - 10) + " blocks inland");
				if (foot - 10 > 0.25 * zone) {
					inland++;
				}
				for (int k = foot; k < 10 + 0.7 * zone; k++) {
					assertTrue(y[k + 1] >= y[k] - 0.01f, scale + " z " + z + ": " + y[k + 1] + " after " + y[k]);
				}
			}
			assertTrue(whole > 50, scale + ": whole cliffs: " + whole);
			assertTrue(inland > 0 && inland < whole, scale + ": feet inland: " + inland + " of " + whole);
		}
	}

	@Test
	void theCliffMeetsTheGroundAtTheEndOfItsStrip() {
		for (WorldScale scale : new WorldScale[]{WorldScale.ULTRA, WorldScale.GRAND, WorldScale.MEDIUM}) {
			TerrainSurface surface = surface(HIGH, scale);
			int end = coast(scale) + (int) Math.ceil(TerrainSurface.cliffZone(scale));
			// Two blocks of rise for each time the heights are exaggerated: the eased slope climbs less than that.
			float rise = (float) (2 * scale.metersPerBlock() / scale.verticalMetersPerBlock());
			for (int z = 0; z < 20_000 / scale.metersPerBlock(); z += 7) {
				for (int x = end - 1; x <= end + 3; x++) {
					assertEquals(surface.shore(x, z), surface.shore(x + 1, z), rise, scale + " " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void theLandEasesDownToTheSea() {
		WorldScale scale = WorldScale.ULTRA;
		int sea = scale.seaLevel(), x0 = coast(scale);
		TerrainSurface surface = surface(HIGH, scale);
		// The plateau would stand 92 blocks high 60 blocks inland; the envelope holds it under 46 m there.
		float held = scale.surfaceY((float) CoastSlope.envelope(CoastSlope.SLOPE_MAX, 60)) - sea;
		float plateau = scale.surfaceY(90f);
		for (int z = 0; z < 60_000; z += 53) {
			float at60 = surface.shore(x0 + 60, z) - sea;
			assertTrue(at60 <= held + 1f, "z " + z + ": " + at60 + " blocks 60 blocks inland");
			assertTrue(at60 > 0.5f * held, "z " + z + ": " + at60 + " blocks 60 blocks inland");
			assertEquals(plateau, surface.shore(x0 + 350, z), 1f, "z " + z + ": the plateau 350 blocks inland");
			// The first block of land stands a block above the water, a cliff's shelf or a bank, not a flat flush with
			// it, and the ground only ever climbs inland, but for the fade of a cliff's bench.
			float first = surface.shore(x0, z);
			assertTrue(first >= sea + 0.5f - 1e-3f && first < sea + 5f, "z " + z + ": the first block of land at " + first);
			float previous = first;
			for (int x = x0 + 1; x <= x0 + 360; x++) {
				float y = surface.shore(x, z);
				assertTrue(y >= previous - 1f, "z " + z + ": " + y + " at " + (x - x0) + " after " + previous);
				previous = y;
			}
		}
		// A gentle coast keeps its shape, but for the stretches with a bluff.
		TerrainSurface gentle = surface(GENTLE, scale);
		int kept = 0;
		for (int z = 0; z < 60_000; z += 101) {
			if (hasCliff(gentle, scale, z)) {
				continue;
			}
			kept++;
			for (int d = 20; d <= 300; d += 20) {
				// The formula reads pixel indices, whose centres lie half a pixel on.
				float own = scale.surfaceY(GENTLE.at(0, (x0 + d + 0.5) * scale.pixelsPerBlock() - 0.5));
				assertEquals(own, gentle.shore(x0 + d, z), 0.03f * (own - sea) + 1f, "z " + z + ", " + d + " blocks inland");
			}
		}
		assertTrue(kept > 300, "profiles without a bluff: " + kept);
	}

	@Test
	void aLowCoastHasNoCliffs() {
		WorldScale scale = WorldScale.ULTRA;
		TerrainSurface surface = surface(LOW, scale);
		for (int z = 0; z < 40_000; z += 211) {
			float[] y = profile(surface, scale, z, false);
			// The sea floor lies a block under the shore.
			assertTrue(steepest(y, 0) < 1.5f, "z " + z + ": " + steepest(y, 0));
			for (int x = coast(scale) - 10; x < coast(scale) + 70; x += 3) {
				assertTrue(Float.isNaN(surface.cliff(x, z)));
			}
		}
	}

	@Test
	void theSeaFloorDeepensAwayFromAFlatShore() {
		// A shore that barely leaves the sea level: 0.02 m per pixel either way.
		FormulaSource.Elevation flat = (i, j) -> (float) (0.02 * (j - 100));
		WorldScale ultra = WorldScale.ULTRA;
		TerrainSurface surface = surface(flat, ultra);
		// The coastline runs through block 3014.5, a block a meter.
		for (int z = 0; z < 2000; z += 173) {
			float previous = ultra.seaLevel();
			for (int d = 5; d < 600; d += 5) {
				int x = 3014 - d;
				float y = surface.y(x, z);
				double depth = 0.1 * Math.pow(Math.min(d + 0.5, TerrainField.COAST_REACH), 2.0 / 3.0);
				assertEquals(ultra.seaLevel() - Math.max(1, depth), y, 0.3, "z " + z + ", " + d + " m out");
				assertTrue(y <= previous + 1e-3f, "the floor never rises away from the shore: " + d + " m out");
				previous = y;
			}
		}
		// The other worlds keep the shallowest sea a block deep: the profile is less than a block there.
		for (WorldScale scale : new WorldScale[]{WorldScale.GRAND, WorldScale.MEDIUM}) {
			TerrainSurface other = surface(flat, scale);
			for (int x = coast(scale) - 100; x < coast(scale) - 1; x += 3) {
				assertEquals(scale.seaLevel() - 1, other.y(x, 0), 1e-3f, scale + " " + x);
			}
		}
	}

	@Test
	void theFarLevelsOfDetailShowTheCliffs() {
		WorldScale scale = WorldScale.ULTRA;
		TerrainSurface surface = surface(HIGH, scale);
		int whole = 0;
		for (int z = 0; z < 60_000; z += 47) {
			if (!whole(surface, scale, z)) {
				continue;
			}
			whole++;
			// The shelf and the face stand where the chunks have them: the ledges only shape the ground above, and the
			// gullies cut no deeper than the shelf where they reach the sea.
			float[] far = profile(surface, scale, z, true), near = profile(surface, scale, z, false);
			int foot = foot(far, scale);
			assertEquals(foot, foot(near, scale), 1, "z " + z);
			for (int k = 10; k <= crest(far, foot); k++) {
				if (k < foot) {
					assertTrue(near[k] <= far[k] + 0.01f, "z " + z + ", " + (k - 10) + " blocks inland");
				} else {
					assertEquals(far[k], near[k], 1f, "z " + z + ", " + (k - 10) + " blocks inland");
				}
			}
		}
		assertTrue(whole > 20, "whole cliffs: " + whole);
	}

	/** Blocks out to sea from the coastline that islets can reach at a scale, and a little more. */
	private static int isletReach(WorldScale scale) {
		return (int) Math.ceil(12 / scale.pixelsPerBlock()) + 2;
	}

	@Test
	void isletsLieOffARockyCoastPastOpenWater() {
		for (WorldScale scale : WITH_ISLETS) {
			TerrainSurface surface = surface(HIGH, scale);
			int sea = scale.seaLevel(), x1 = coast(scale) - 1, x0 = x1 - isletReach(scale);
			// An islet stays below four fifths of the land at the end of the strip, which the envelope holds down.
			float land = scale.surfaceY((float) CoastSlope.envelope(CoastSlope.SLOPE_MAX, 60)) - sea;
			int length = (int) (100_000 / scale.metersPerBlock()), islets = 0, columns = 0;
			// The islets of the 1:1 world are at least 6 blocks wide.
			int step = scale == WorldScale.ULTRA ? 3 : 1;
			boolean previous = false;
			float highest = 0;
			for (int z = 0; z < length; z++) {
				boolean row = false;
				// Walking out to sea from the coastline: water first, then the islets.
				boolean water = false;
				for (int x = x1; x >= x0; x -= step) {
					float y = surface.y(x, z);
					if (y <= sea) {
						water = true;
						continue;
					}
					assertTrue(water, scale + ": an islet joined to the land at " + x + ", " + z);
					assertTrue(y <= sea + 0.8 * land + 0.5, scale + ": an islet " + (y - sea) + " blocks high at " + x + ", " + z);
					highest = Math.max(highest, y - sea);
					// The density takes the islet and the sea around it block by block.
					for (int d = -4; d <= 4 && (x + z) % 5 == 0; d++) {
						assertEquals(surface.y(x + d, z), surface.cliff(x + d, z), 0f, scale + " " + (x + d) + ", " + z);
						assertEquals(surface.y(x, z + d), surface.cliff(x, z + d), 0f, scale + " " + x + ", " + (z + d));
					}
					row = true;
					columns++;
				}
				if (row && !previous) {
					islets++;
				}
				previous = row;
			}
			double perKm = islets / (length * scale.metersPerBlock() / 1000);
			System.out.println(scale + ": " + islets + " islets, " + columns + " columns, " + perKm + " per km, highest " + highest);
			assertTrue(perKm > 0.15 && perKm < 10, scale + ": " + islets + " islets along 100 km of rocky coast");
			assertTrue(highest >= 1.5f, scale + ": the highest islet " + highest);
		}
	}

	@Test
	void anIsletIsAMoundNotAColumn() {
		WorldScale scale = WorldScale.ULTRA;
		TerrainSurface surface = surface(SHELF, scale);
		int sea = scale.seaLevel(), x1 = coast(scale) - 1, x0 = x1 - isletReach(scale);
		int rows = 0;
		for (int z = 0; z < 60_000; z++) {
			float previous = surface.y(x0 - 1, z);
			boolean islet = false;
			for (int x = x0; x <= x1; x++) {
				float y = surface.y(x, z);
				if (y > sea) {
					islet = true;
					// Its sides climb a few blocks a block at most, so the ground slopes into the water.
					assertTrue(Math.abs(y - previous) <= 6f, "a step of " + (y - previous) + " at " + x + ", " + z);
				}
				previous = y;
			}
			if (islet) {
				rows++;
			}
		}
		assertTrue(rows > 100, "rows across islets: " + rows);
	}

	@Test
	void aLowCoastHasNoIslets() {
		for (WorldScale scale : WorldScale.values()) {
			TerrainSurface surface = surface(LOW, scale);
			int x1 = coast(scale) - 1, x0 = x1 - isletReach(scale);
			for (int z = 0; z < 20_000 / scale.metersPerBlock(); z++) {
				for (int x = x0; x <= x1; x++) {
					assertTrue(surface.y(x, z) < scale.seaLevel(), scale + " " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void theDensityTakesTheCliffAndTheSeaBesideItBlockByBlock() {
		WorldScale scale = WorldScale.ULTRA;
		TerrainSurface surface = surface(HIGH, scale);
		int x0 = coast(scale), zone = (int) TerrainSurface.cliffZone(scale);
		int beside = 0, islets = 0, outside = 0;
		for (int z = 0; z < 60_000; z += 131) {
			boolean cliff = surface.cliffStrength(x0 + 1, z) > 0;
			for (int x = x0 - 20; x < x0 + zone + 20; x++) {
				float column = surface.cliff(x, z);
				if (x < x0 - TerrainSurface.CLIFF_MARGIN) {
					outside++;
				}
				if (x > x0 + zone) {
					assertTrue(Float.isNaN(column), "inland of the strip: " + x + ", " + z);
				} else if (x < x0 - TerrainSurface.CLIFF_MARGIN && !Float.isNaN(column)) {
					// Out to sea, only around the islets.
					assertEquals(surface.y(x, z), column, 0f);
					islets++;
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
		assertTrue(islets < 0.1 * outside, "around the islets: " + islets + " of " + outside);
	}
}
