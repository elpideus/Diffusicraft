package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;

/**
 * Where other mods' biomes go in a Diffusicraft world. The world is tiled into regions about {@value #CELL} blocks
 * across, the cells of jittered points with their borders bent by noise, and each region is either Diffusicraft's or
 * open to other mods. In an open region, {@link ClimateBiomeSource} takes the pick of the parameter list as the mods
 * left it wherever that pick is one of theirs; everywhere else it picks from vanilla's own table as if no mod were
 * installed. So the mods add their biomes beside Diffusicraft's instead of reshuffling the whole map, and how much of
 * the world they get is a setting.
 *
 * <p>The share is process-wide, set as a world's server starts (like {@link HeightTemperature}).
 */
public final class ModdedBiomes {
	/** Width of a region's cell, in blocks. */
	static final double CELL = 1024;
	/** How far a cell's point strays from its middle, as a fraction of the cell. */
	private static final double JITTER = 0.8;
	/** How far the noise moves a column before its region is looked up, in cells. */
	private static final double WARP = 0.25;
	/** Wavelength of the noise that bends the borders, in blocks. */
	private static final double WARP_WAVELENGTH = 384;
	private static final int WARP_OCTAVES = 3;

	private static volatile float share = 0.5f;

	private ModdedBiomes() {
	}

	/** The fraction of the world open to other mods' biomes, in [0, 1]. */
	public static float share() {
		return share;
	}

	public static void setShare(float value) {
		share = Math.clamp(value, 0f, 1f);
	}

	/** Whether the column at block {@code (x, z)} lies in a region open to other mods' biomes. */
	public static boolean open(long seed, int x, int z, float share) {
		return share > 0f && draw(seed, x, z) < share;
	}

	/** The draw in [0, 1) of the region the column at block {@code (x, z)} lies in. */
	static double draw(long seed, int x, int z) {
		long cells = GeoNoise.hash(seed, 0x4D4F4444, 0x45444249, 0);
		double u = x / CELL + WARP * GeoNoise.fractal(cells + 1, x / WARP_WAVELENGTH, z / WARP_WAVELENGTH, WARP_OCTAVES);
		double v = z / CELL + WARP * GeoNoise.fractal(cells + 2, x / WARP_WAVELENGTH, z / WARP_WAVELENGTH, WARP_OCTAVES);
		int cu = (int) Math.floor(u), cv = (int) Math.floor(v);
		double nearest = Double.POSITIVE_INFINITY;
		long region = 0;
		for (int du = -1; du <= 1; du++) {
			for (int dv = -1; dv <= 1; dv++) {
				long h = GeoNoise.hash(cells, cu + du, cv + dv, 0);
				double pu = cu + du + 0.5 + JITTER * (GeoNoise.unit(h) - 0.5);
				double pv = cv + dv + 0.5 + JITTER * (GeoNoise.unit2(h) - 0.5);
				double distance = (u - pu) * (u - pu) + (v - pv) * (v - pv);
				if (distance < nearest) {
					nearest = distance;
					region = h;
				}
			}
		}
		return GeoNoise.unit(GeoNoise.hash(region, 2, 0, 0));
	}
}
