package io.github.elpideus.diffusicraft.height;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedPaletteTest {
	@Test
	void holdsOneValue() {
		SharedPalette<String> palette = new SharedPalette<>("air");
		assertEquals("air", palette.valueFor(0));
		assertEquals(1, palette.getSize());
		assertSame(palette, palette.copy());
		assertEquals(0, palette.idFor("air", (bits, value) -> {
			throw new AssertionError("the same value resized the container");
		}));
	}

	@Test
	void leavesOtherValuesToTheContainer() {
		SharedPalette<String> palette = new SharedPalette<>("air");
		String[] resized = new String[1];
		assertEquals(1, palette.idFor("stone", (bits, value) -> {
			assertEquals(1, bits);
			resized[0] = value;
			return 1;
		}));
		assertEquals("stone", resized[0]);
		assertEquals("air", palette.valueFor(0));
	}

	@Test
	void neverReads() {
		SharedPalette<String> palette = new SharedPalette<>("air");
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeVarInt(1);
		assertThrows(UnsupportedOperationException.class, () -> palette.read(buffer, null));
		assertEquals("air", palette.valueFor(0));
	}
}
