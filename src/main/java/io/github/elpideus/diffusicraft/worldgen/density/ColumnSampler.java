package io.github.elpideus.diffusicraft.worldgen.density;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** A sampler whose value depends only on the column: computed once per (x, z) and repeated along Y. */
abstract class ColumnSampler implements DensitySampler {
	final WorldScale scale;

	ColumnSampler(WorldScale scale) {
		this.scale = scale;
	}

	abstract float sampleColumn(int blockX, int blockZ);

	@Override
	public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
		for (int z = 0; z < volume.sizeZ(); z++) {
			int blockZ = volume.blockZ(z);
			for (int x = 0; x < volume.sizeX(); x++) {
				float value = sampleColumn(volume.blockX(x), blockZ);
				outputBuffer.setRange(volume.indexUnchecked(x, 0, z), volume.sizeY(), value);
			}
		}
	}

	@Override
	public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
		return sampleColumn(blockX, blockZ);
	}
}
