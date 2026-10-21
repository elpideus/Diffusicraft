package me.cortex.voxy.common.world.other;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Diffusicraft's declaration of the members of Voxy's {@code Mapper} it uses, to compile against: the ids of a
 * world's voxels, each a block state, a biome and a light level. Not Voxy's code: Voxy is All Rights Reserved, and at
 * runtime its own class stands in for this one. Never shipped.
 */
public final class Mapper {
	private Mapper() {
	}

	/** The id of a block in a biome with a light level: sky light in the low four bits, block light in the high. */
	public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
		throw new AssertionError();
	}

	/** The same voxel with another light level. */
	public static long withLight(long id, int light) {
		throw new AssertionError();
	}

	public static long airWithLight(int light) {
		throw new AssertionError();
	}

	/** Whether a voxel is air, whatever its light. */
	public static boolean isAir(long id) {
		throw new AssertionError();
	}
}
