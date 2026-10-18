package io.github.elpideus.diffusicraft.height;

import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class NarrowLinearConfigurationTest {
	/** Vanilla's block configuration for 1 to 4 bits. */
	private static final Configuration FOUR_BITS_LINEAR = new Configuration.Simple(LinearPalette::create, 4);
	/** Lithium's block configuration for 3 and 4 bits. */
	private static final Configuration FOUR_BITS_HASH = new Configuration.Simple(HashMapPalette::create, 4);

	@Test
	void narrowsBlockSectionsWithAFewBlocks() {
		for (Configuration configuration : List.of(FOUR_BITS_LINEAR, FOUR_BITS_HASH)) {
			for (int bits = 1; bits <= 3; bits++) {
				Configuration narrowed = NarrowLinearConfiguration.narrowed(configuration, bits);
				assertEquals(bits, narrowed.bitsInMemory());
				// Saves stay as vanilla writes them.
				assertEquals(4, narrowed.bitsInStorage());
				assertFalse(narrowed.alwaysRepack());
				assertSame(narrowed, NarrowLinearConfiguration.narrowed(configuration, bits), "cached");
			}
			assertSame(configuration, NarrowLinearConfiguration.narrowed(configuration, 4));
		}
	}

	@Test
	void keepsEverythingElse() {
		// Biomes already store 1 to 3 bits; single values and wider palettes have nothing to narrow.
		for (int bits = 1; bits <= 3; bits++) {
			Configuration biomes = new Configuration.Simple(LinearPalette::create, bits);
			assertSame(biomes, NarrowLinearConfiguration.narrowed(biomes, bits));
		}
		Configuration single = new Configuration.Simple(LinearPalette::create, 0);
		assertSame(single, NarrowLinearConfiguration.narrowed(single, 0));
		Configuration wide = new Configuration.Simple(HashMapPalette::create, 5);
		assertSame(wide, NarrowLinearConfiguration.narrowed(wide, 5));
		Configuration global = new Configuration.Global(15, 15);
		assertSame(global, NarrowLinearConfiguration.narrowed(global, 15));
	}

	@Test
	void growsOneBitAtATime() {
		for (int bits = 1; bits <= 3; bits++) {
			Palette<String> palette = NarrowLinearConfiguration.narrowed(FOUR_BITS_LINEAR, bits)
				.createPalette(null, List.of());
			int[] asked = {0};
			for (int i = 0; i < 1 << bits; i++) {
				assertEquals(i, palette.idFor("block " + i, (newBits, value) -> asked[0] = newBits));
			}
			assertEquals(0, asked[0], "room for " + (1 << bits) + " blocks");
			palette.idFor("one more", (newBits, value) -> asked[0] = newBits);
			assertEquals(bits + 1, asked[0]);
		}
	}
}
