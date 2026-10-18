package io.github.elpideus.diffusicraft.height;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderWindowTest {
	private static final int TALL_MIN = -1023;
	private static final int TALL_MAX = 1022;

	@Test
	void followsTheRenderDistance() {
		assertEquals(25, RenderWindow.of(TALL_MIN, TALL_MAX, 12, 0).rows());
		assertEquals(9, RenderWindow.of(TALL_MIN, TALL_MAX, 12, 4).rows());
		// Vanilla draws nothing beyond the render distance vertically, so a larger value changes nothing.
		assertEquals(25, RenderWindow.of(TALL_MIN, TALL_MAX, 12, 40).rows());
	}

	@Test
	void neverExceedsTheLevel() {
		RenderWindow overworld = RenderWindow.of(-4, 19, 32, 0);
		assertEquals(24, overworld.rows());
		assertTrue(overworld.coversLevel());
		assertFalse(RenderWindow.of(-4, 19, 8, 0).coversLevel());
		assertThrows(IllegalArgumentException.class, () -> new RenderWindow(-4, 19, 25));
		assertThrows(IllegalArgumentException.class, () -> new RenderWindow(-4, 19, 0));
	}

	@Test
	void staysInsideTheLevel() {
		RenderWindow window = RenderWindow.of(TALL_MIN, TALL_MAX, 4, 0);
		assertEquals(96, window.lowest(100));
		assertEquals(TALL_MIN, window.lowest(TALL_MIN));
		assertEquals(TALL_MAX - 8, window.lowest(TALL_MAX));
		// A camera above or below the level sees its edge, as in vanilla.
		assertEquals(TALL_MAX - 8, window.lowest(TALL_MAX + 500));
		assertEquals(TALL_MIN, window.lowest(Integer.MIN_VALUE));
		assertEquals(TALL_MAX - 8, window.lowest(Integer.MAX_VALUE));
	}

	@Test
	void slotsHoldTheWindowsRows() {
		Random random = new Random(1);
		for (int rows : new int[] {1, 2, 9, 25}) {
			RenderWindow window = new RenderWindow(TALL_MIN, TALL_MAX, rows);
			int[] held = new int[rows];
			int lowest = window.lowest(0);
			for (int slot = 0; slot < rows; slot++) {
				held[slot] = window.sectionY(slot, lowest);
			}
			for (int step = 0; step < 2000; step++) {
				int camera = step % 10 == 0 ? random.nextInt(-1200, 1200) : held[0] + random.nextInt(-3, 4);
				int newLowest = window.lowest(camera);
				int moved = 0;
				for (int slot = 0; slot < rows; slot++) {
					int sectionY = window.sectionY(slot, newLowest);
					assertTrue(sectionY >= newLowest && sectionY < newLowest + rows);
					assertEquals(slot, window.slot(sectionY));
					if (sectionY != held[slot]) {
						moved++;
						held[slot] = sectionY;
					}
				}
				// Rotating: a window that moves by d rows reassigns only d slots.
				assertEquals(Math.min(rows, Math.abs(newLowest - lowest)), moved);
				lowest = newLowest;
			}
		}
	}
}
