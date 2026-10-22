package io.github.elpideus.diffusicraft.worldgen.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringsTest {
	/**
	 * A cliff whose face runs along x = 0, ground west of it up to {@code top} and east of it up to {@code foot}, with
	 * water from the foot up to {@code sea}, and a roof over the east from {@code roof} up if it is below the top.
	 */
	private static Springs.World cliff(int top, int foot, int sea, int roof) {
		return new Springs.World() {
			@Override
			public boolean empty(int x, int y, int z) {
				return x < 0 ? y > top : y > foot && y <= sea ? false : y > foot && y < roof;
			}

			@Override
			public boolean water(int x, int y, int z) {
				return x >= 0 && y > foot && y <= sea;
			}

			@Override
			public int open(int x, int z) {
				return x < 0 ? top + 1 : roof < top ? top + 1 : Math.max(foot, sea) + 1;
			}

			@Override
			public int minY() {
				return -64;
			}
		};
	}

	@Test
	void aSpringHighOnACliffOverDryGroundIsLeftOut() {
		assertTrue(Springs.fallsFar(cliff(120, 64, Integer.MIN_VALUE, Integer.MAX_VALUE), -1, 100, 0));
	}

	@Test
	void aSpringOverWaterStays() {
		assertFalse(Springs.fallsFar(cliff(120, 40, 64, Integer.MAX_VALUE), -1, 100, 0));
	}

	@Test
	void aSpringAFewBlocksUpStays() {
		assertFalse(Springs.fallsFar(cliff(120, 64, Integer.MIN_VALUE, Integer.MAX_VALUE), -1, 65 + Springs.MAX_DROP, 0));
		assertTrue(Springs.fallsFar(cliff(120, 64, Integer.MIN_VALUE, Integer.MAX_VALUE), -1, 66 + Springs.MAX_DROP, 0));
	}

	@Test
	void aSpringInACaveStays() {
		// The same face, under a roof: a cave's wall.
		assertFalse(Springs.fallsFar(cliff(120, 64, Integer.MIN_VALUE, 110), -1, 100, 0));
	}

	@Test
	void aSpringWithoutAHoleStays() {
		assertFalse(Springs.fallsFar(cliff(120, 64, Integer.MIN_VALUE, Integer.MAX_VALUE), -3, 100, 0));
	}
}
