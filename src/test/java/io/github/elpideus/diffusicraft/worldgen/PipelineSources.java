package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;

/** Opens {@link PipelineSource} to tests in other packages. */
public final class PipelineSources {
	private PipelineSources() {
	}

	public static TerrainSource of(long seed, WorldPipeline pipeline) {
		return new PipelineSource(seed, pipeline);
	}
}
