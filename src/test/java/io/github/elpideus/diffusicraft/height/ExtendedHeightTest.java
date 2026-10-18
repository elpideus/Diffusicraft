package io.github.elpideus.diffusicraft.height;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedHeightTest {
	private static final int[] HORIZONTAL = {0, 1, -1, 15, -16, 12_345, -54_321, 1_000_000, -1_000_000,
		ExtendedHeight.WORLD_LIMIT, -ExtendedHeight.WORLD_LIMIT, (1 << 23) - 1, -(1 << 23)};

	@Test
	void tallPositionsRoundTrip() {
		for (int x : HORIZONTAL) {
			for (int z : HORIZONTAL) {
				for (int y = -(1 << 14); y < 1 << 14; y += 7) {
					assertRoundTrip(x, y, z);
				}
				for (int y : new int[] {-16368, 16367, -2049, 2048, -(1 << 14), (1 << 14) - 1}) {
					assertRoundTrip(x, y, z);
				}
			}
		}
	}

	@Test
	void vanillaHeightsUseVanillaLayout() {
		// Outside the game, BlockPos keeps vanilla's packing, so it is the reference here.
		for (int x : HORIZONTAL) {
			for (int z : HORIZONTAL) {
				for (int y = -2048; y <= 2047; y++) {
					long wire = ExtendedHeight.toWire(x, y, z);
					assertEquals(BlockPos.asLong(x, y, z), wire);
					assertFalse(ExtendedHeight.isTall(wire));
				}
			}
		}
	}

	@Test
	void decodesVanillaLongs() {
		// Positions a vanilla server sends, as far out as the tall layout allows (|X|, |Z| < 2^24).
		int[] far = {(1 << 24) - 1, -(1 << 24), 16_000_000, -16_000_000};
		for (int x : far) {
			for (int z : far) {
				for (int y : new int[] {-2048, -64, 0, 319, 2047}) {
					long vanilla = BlockPos.asLong(x, y, z);
					assertEquals(x, ExtendedHeight.wireX(vanilla));
					assertEquals(y, ExtendedHeight.wireY(vanilla));
					assertEquals(z, ExtendedHeight.wireZ(vanilla));
				}
			}
		}
	}

	@Test
	void limitsAreConsistent() {
		// BlockPos derives its bits from the world size: 1 + log2(power of two >= size).
		assertEquals(ExtendedHeight.HORIZONTAL_BITS, 1 + Integer.numberOfTrailingZeros(ExtendedHeight.PACKING_SIZE));
		assertTrue(ExtendedHeight.WORLD_LIMIT < ExtendedHeight.PACKING_SIZE);
		// DimensionType: Y_SIZE = 2^bits - 32, MAX_Y = Y_SIZE / 2 - 1. Both ends must fit the wire layout.
		int ySize = (1 << ExtendedHeight.DIMENSION_Y_BITS) - 32;
		assertEquals(32_736, ySize);
		assertEquals(16_367, (ySize >> 1) - 1);
		assertTrue(64 - 2 * ExtendedHeight.HORIZONTAL_BITS >= ExtendedHeight.DIMENSION_Y_BITS);
	}

	private static void assertRoundTrip(int x, int y, int z) {
		long wire = ExtendedHeight.toWire(x, y, z);
		String at = x + " " + y + " " + z;
		assertEquals(x, ExtendedHeight.wireX(wire), at);
		assertEquals(y, ExtendedHeight.wireY(wire), at);
		assertEquals(z, ExtendedHeight.wireZ(wire), at);
	}
}
