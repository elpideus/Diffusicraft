package io.github.elpideus.diffusicraft.worldgen.biome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModdedBiomesTest {
	private static final long SEED = 42;
	/** Columns sampled on a grid this many blocks apart, this many to a side. */
	private static final int STEP = 64;
	private static final int SIDE = 1000;

	private static double openFraction(long seed, float share) {
		int open = 0;
		for (int i = 0; i < SIDE; i++) {
			for (int j = 0; j < SIDE; j++) {
				if (ModdedBiomes.open(seed, i * STEP, j * STEP, share)) {
					open++;
				}
			}
		}
		return open / (double) (SIDE * SIDE);
	}

	@Test
	void noShareOpensNothingAndAFullShareEverything() {
		for (int i = -50; i < 50; i++) {
			assertFalse(ModdedBiomes.open(SEED, i * 997, i * -613, 0f));
			assertTrue(ModdedBiomes.open(SEED, i * 997, i * -613, 1f));
		}
	}

	@Test
	void theOpenRegionsCoverTheirShare() {
		for (float share : new float[] {0.25f, 0.5f, 0.75f}) {
			assertEquals(share, openFraction(SEED, share), 0.05, "share " + share);
		}
	}

	@Test
	void regionsSpanHundredsOfBlocks() {
		int borders = 0, pairs = 0;
		for (int i = 0; i < SIDE; i++) {
			for (int j = 0; j < SIDE; j++) {
				int x = i * STEP, z = j * STEP;
				boolean open = ModdedBiomes.open(SEED, x, z, 0.5f);
				if (open != ModdedBiomes.open(SEED, x + 16, z, 0.5f)) {
					borders++;
				}
				pairs++;
			}
		}
		// A chunk-wide step crosses a border about once in every 1024 / 16 / 2 steps.
		assertTrue(borders < pairs * 0.04, borders + " borders in " + pairs + " steps");
	}

	@Test
	void theSeedMovesTheRegions() {
		int differ = 0;
		for (int i = 0; i < 1000; i++) {
			if (ModdedBiomes.open(SEED, i * 1500, i * 700, 0.5f) != ModdedBiomes.open(SEED + 1, i * 1500, i * 700, 0.5f)) {
				differ++;
			}
		}
		assertTrue(differ > 300, differ + " of 1000 columns differ");
	}
}
