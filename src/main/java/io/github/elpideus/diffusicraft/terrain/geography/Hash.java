package io.github.elpideus.diffusicraft.terrain.geography;

/** SplitMix64 hashing: the same numbers on every machine, for every thread, in any order. */
final class Hash {
	private static final long GOLDEN = 0x9E3779B97F4A7C15L;

	private Hash() {
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** A hash of a seed and two integers, such as the coordinates of a cell. */
	static long of(long seed, long a, long b) {
		return mix(seed ^ mix(a * GOLDEN + mix(b + GOLDEN)));
	}

	/** A number in [0, 1) drawn from a hash; different salts give independent numbers. */
	static double unit(long hash, int salt) {
		return (mix(hash + salt * GOLDEN) >>> 11) * 0x1.0p-53;
	}
}
