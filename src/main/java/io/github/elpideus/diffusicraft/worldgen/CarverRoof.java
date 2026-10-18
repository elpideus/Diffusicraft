package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Keeps carvers from opening the ground where the hole would fill with water: under water, along rivers and on
 * lowland a few blocks above the sea. Vanilla's ravines there become straight canals of sea water running beside the
 * coast and the rivers, and a ravine across a river drains it. Under such ground the carvers still dig, but leave a
 * roof of {@link #THICKNESS} blocks; elsewhere they open to the sky as in vanilla.
 */
public final class CarverRoof {
	/** Blocks of ground a carver leaves over its hole. */
	static final int THICKNESS = 4;
	/** Ground at most this many blocks above sea level is lowland, which a hole would fill with sea water. */
	static final int LOWLAND = 3;

	private CarverRoof() {
	}

	/**
	 * A carving filter for a chunk whose carvers have run but not yet been applied. {@code inner} is the filter vanilla
	 * would have used, or {@code null}; the result allows only what both allow.
	 */
	public static CarvingMask.Filter filter(ChunkAccess chunk, WorldScale scale, Rivers rivers, int seaLevel,
											  CarvingMask.Filter inner) {
		int[] roof = new int[256];
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
				boolean roofed = top > ground || ground <= seaLevel + LOWLAND
					|| rivers.column(scale, minX + x, minZ + z).river;
				roof[z << 4 | x] = roofed ? ground - THICKNESS : Integer.MAX_VALUE;
			}
		}
		return (x, y, z) -> y <= roof[z << 4 | x] && (inner == null || inner.test(x, y, z));
	}
}
