package io.github.elpideus.diffusicraft.height;

import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.Strategy;

import java.util.List;

/**
 * Block sections with up to eight different blocks, stored with the bits per block they need.
 *
 * <p>Vanilla stores every block section with 2 to 16 different blocks at 4 bits per block: 2 KiB. A tall world holds
 * thousands of blocks of rock per column, and most of its sections that are not empty hold only a few kinds of stone:
 * around the highest ground near the spawn of a 1:1 world, half of them hold two blocks. At 1, 2 or 3 bits
 * per block they take a quarter, a half or three quarters of the memory. Biomes already work this way in vanilla.
 *
 * <p>Saves keep vanilla's 4 bits ({@link #bitsInStorage()}), so worlds stay readable without the mod, and the
 * storage is narrowed again as it loads. Chunk packets carry the narrower storage, which a client reads only with
 * the mod; it cannot join without it anyway, since the mod adds blocks. {@code PalettedContainerMixin} and
 * {@code StrategyMixin} hand these out wherever the game picks a configuration.
 */
public record NarrowLinearConfiguration(int bitsInMemory) implements Configuration {
	private static final Configuration[] BY_BITS = {null, new NarrowLinearConfiguration(1),
		new NarrowLinearConfiguration(2), new NarrowLinearConfiguration(3)};

	/** {@code configuration}, which the game picked for {@code bits} bits per entry, or a narrower one instead. */
	public static Configuration narrowed(Configuration configuration, int bits) {
		// Block states round 1 to 3 bits up to 4 (Lithium's hash palette too); biomes keep the bits they ask for.
		return bits >= 1 && bits <= 3 && configuration.bitsInMemory() == 4 && !configuration.alwaysRepack()
			? BY_BITS[bits] : configuration;
	}

	@Override
	public boolean alwaysRepack() {
		return false;
	}

	@Override
	public int bitsInStorage() {
		return 4;
	}

	@Override
	public <T> Palette<T> createPalette(Strategy<T> strategy, List<T> paletteEntries) {
		return LinearPalette.create(bitsInMemory, paletteEntries);
	}
}
