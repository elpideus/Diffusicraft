package io.github.elpideus.diffusicraft.worldgen.biome;

/**
 * How much vanilla cools a biome with height. Vanilla takes 0.00125 off a biome's temperature for every block above
 * sea level + 17, so snow falls on anything about 500 blocks above the sea. Diffusicraft's biomes already come from
 * the climate at the ground's real elevation, and its mountains rise thousands of blocks, so that drop would count
 * the cold twice and whiten every slope. In a Diffusicraft world the drop is switched off and snow follows the biome:
 * snowy peaks only where it is both cold and high.
 *
 * <p>Biomes are shared by every level of the game, so this is a process-wide switch, set while a Diffusicraft
 * world's server runs. On a client connected to a remote server it stays at vanilla's, which only changes where rain
 * turns to snow on screen; the snow on the ground is the server's.
 */
public final class HeightTemperature {
	private static volatile float factor = 1f;

	private HeightTemperature() {
	}

	/** Multiplies vanilla's temperature drop per block: 1 is vanilla's, 0 none. */
	public static float factor() {
		return factor;
	}

	public static void set(float value) {
		factor = value;
	}
}
