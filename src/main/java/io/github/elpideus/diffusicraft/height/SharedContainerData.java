package io.github.elpideus.diffusicraft.height;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Paletted containers that hold a single value share one read-only copy of their data.
 *
 * <p>Minecraft allocates every section of a loaded column, and most sections of a tall dimension are empty: all air,
 * with one biome. Each of them has two {@link PalettedContainer}s, and each container its own data: a configuration,
 * a palette with the one value and a storage of zero bits. Shared, an empty section costs its own objects only.
 *
 * <p>A container gets shared data when it is created, copied, loaded from a save or read from a packet with a single
 * value, and when {@link Container#diffusicraft$shareIfUniform()} finds that all its entries are the same. The shared
 * data's palette is a {@link SharedPalette}, which never changes. Setting a different value resizes the container as
 * usual, which gives it new data of its own: copy on write. The mixins in {@code mixin/height} do this:
 * {@code PalettedContainerMixin}, {@code StrategyMixin} and {@code LevelChunkSectionMixin}.
 *
 * <p>The data lives on the {@link Strategy}, one per registry, so it goes away with the registry it belongs to.
 */
public final class SharedContainerData {
	private SharedContainerData() {
	}

	/** Whether every entry of {@code storage} holds the same id. Compares whole words where it can. */
	public static boolean isUniform(BitStorage storage) {
		int id = storage.get(0), size = storage.getSize(), from = 1;
		if (storage instanceof SimpleBitStorage) {
			// Entries never straddle words, so the words between the first and the last hold the first word's
			// entries if they equal it. Only the first word's entries and the last word's are left to check.
			long[] words = storage.getRaw();
			for (int i = 1; i < words.length - 1; i++) {
				if (words[i] != words[0]) {
					return false;
				}
			}
			int perWord = 64 / storage.getBits();
			for (int i = 1; i < Math.min(perWord, size); i++) {
				if (storage.get(i) != id) {
					return false;
				}
			}
			from = Math.max(1, (words.length - 1) * perWord);
		}
		for (int i = from; i < size; i++) {
			if (storage.get(i) != id) {
				return false;
			}
		}
		return true;
	}

	/** What {@code StrategyMixin} adds to {@link Strategy}. */
	public interface Source<T> {
		/** The shared data of a container that holds only {@code value}. */
		PalettedContainer.Data<T> diffusicraft$sharedData(T value);
	}

	/** What {@code PalettedContainerMixin} adds to {@link PalettedContainer}. */
	public interface Container {
		/**
		 * Switches to shared data if every entry holds the same value. Other threads may read the container meanwhile:
		 * the old data and the new hold the same values.
		 */
		void diffusicraft$shareIfUniform();

		/** Whether this container's data is shared. */
		boolean diffusicraft$isShared();
	}
}
