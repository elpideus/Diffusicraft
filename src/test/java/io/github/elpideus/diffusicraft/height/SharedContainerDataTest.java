package io.github.elpideus.diffusicraft.height;

import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedContainerDataTest {
	@Test
	void findsUniformStorage() {
		assertTrue(SharedContainerData.isUniform(new ZeroBitStorage(4096)));
		// Block sections hold 4096 entries and biomes 64, at every width a palette uses.
		for (int size : new int[]{4096, 64}) {
			for (int bits = 1; bits <= 15; bits++) {
				int id = (1 << bits) - 1;
				assertTrue(SharedContainerData.isUniform(filled(bits, size, id)), bits + " bits, " + size);
				assertTrue(SharedContainerData.isUniform(filled(bits, size, 0)), bits + " bits, " + size);
			}
		}
	}

	@Test
	void findsAnyOtherEntry() {
		for (int size : new int[]{4096, 64}) {
			for (int bits = 1; bits <= 15; bits++) {
				// Wherever the odd entry is: the first word, any word between, or the last.
				for (int index = 0; index < size; index++) {
					SimpleBitStorage storage = filled(bits, size, 1);
					storage.set(index, 0);
					assertFalse(SharedContainerData.isUniform(storage), bits + " bits, entry " + index + " of " + size);
				}
			}
		}
	}

	private static SimpleBitStorage filled(int bits, int size, int id) {
		SimpleBitStorage storage = new SimpleBitStorage(bits, size);
		for (int i = 0; i < size; i++) {
			storage.set(i, id);
		}
		return storage;
	}
}
