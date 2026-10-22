package io.github.elpideus.diffusicraft.worldgen.feature;

/**
 * Where the undergrowth of a rainforest grows thick ({@link UndergrowthFeature}): a smooth noise of the world's seed
 * over its columns. Apart from the feature, which needs the game's registries, so that it can be tested on its own.
 */
final class Thickets {
	/** Blocks across the cells of the noise: a broad one, for the thickets, and a fine one, which breaks them up. */
	private static final int BROAD = 24, FINE = 7;

	private Thickets() {
	}

	/**
	 * How thick the undergrowth grows at a column, from 0 in a clearing to 1 in a thicket: a smooth noise of the world's
	 * {@code seed}, broad thickets broken up by finer ones, sharpened so that they part from the clearings.
	 */
	static float at(long seed, int x, int z) {
		float n = 0.65f * noise(seed, x, z, BROAD) + 0.35f * noise(seed + 1, x, z, FINE);
		float t = Math.clamp((n - 0.3f) / 0.4f, 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	/** Value noise over cells {@code cell} blocks across, smoothly blended between their corners: from 0 to 1. */
	private static float noise(long seed, int x, int z, int cell) {
		int ix = Math.floorDiv(x, cell), iz = Math.floorDiv(z, cell);
		float fx = (x - ix * cell + 0.5f) / cell, fz = (z - iz * cell + 0.5f) / cell;
		fx = fx * fx * (3f - 2f * fx);
		fz = fz * fz * (3f - 2f * fz);
		float a = corner(seed, ix, iz), b = corner(seed, ix + 1, iz);
		float c = corner(seed, ix, iz + 1), d = corner(seed, ix + 1, iz + 1);
		return (a + (b - a) * fx) * (1f - fz) + (c + (d - c) * fx) * fz;
	}

	private static float corner(long seed, int ix, int iz) {
		long h = seed ^ ix * 0x9E3779B97F4A7C15L ^ iz * 0xC2B2AE3D27D4EB4FL;
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
		return (h >>> 40) / (float) (1 << 24);
	}
}
