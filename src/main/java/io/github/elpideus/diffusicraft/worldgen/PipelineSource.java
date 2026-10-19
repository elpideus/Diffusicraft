package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.DiskWindowStore;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.Tile;
import io.github.elpideus.diffusicraft.terrain.geography.Geography;

import java.nio.file.Path;

/**
 * The Terrain Diffusion pipeline for one seed. It is created on first use, so a world can be loaded (and its density
 * functions compiled) before the models are, and windows are cached under the world's save folder.
 */
final class PipelineSource implements TerrainSource {
	private static final float WEIGHT_EPS = 1e-6f;

	private final long seed;
	private volatile WorldPipeline pipeline;
	private volatile boolean closed;

	PipelineSource(long seed) {
		this.seed = seed;
	}

	/** A source over a pipeline that is already loaded, for tests. */
	PipelineSource(long seed, WorldPipeline pipeline) {
		this.seed = seed;
		this.pipeline = pipeline;
	}

	@Override
	public TerrainSample sample(int i0, int j0, int i1, int j1) {
		return pipeline().sample(i0, j0, i1, j1, true);
	}

	@Override
	public int coarseScale() {
		return WorldPipeline.COARSE_SCALE;
	}

	@Override
	public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
		Tile slice = pipeline().coarseSlice(ci0, cj0, ci1, cj1);
		int plane = slice.plane();
		float[] out = new float[COARSE_CHANNELS * plane];
		for (int ch = 0; ch < COARSE_CHANNELS; ch++) {
			for (int px = 0; px < plane; px++) {
				float weight = slice.data[COARSE_CHANNELS * plane + px];
				float v = weight > WEIGHT_EPS ? slice.data[ch * plane + px] / weight : 0f;
				// The coarse map stores both elevations as signed square roots.
				if (ch == COARSE_ELEVATION || ch == COARSE_LOW_ELEVATION) {
					v = Math.signum(v) * v * v;
				}
				out[ch * plane + px] = v;
			}
		}
		return out;
	}

	private WorldPipeline pipeline() {
		WorldPipeline p = pipeline;
		if (p == null) {
			synchronized (this) {
				if (closed) {
					throw new IllegalStateException("The terrain source for seed " + seed + " is closed");
				}
				p = pipeline;
				if (p == null) {
					ModelBundle models = TerrainFields.models();
					Path root = TerrainFields.storageRoot();
					DiskWindowStore store = root == null ? null
						: new DiskWindowStore(root.resolve(models.revision()).resolve("map" + Geography.VERSION)
							.resolve(Long.toHexString(seed)));
					p = new WorldPipeline(seed, models, store, WorldPipeline.CacheLimits.defaults());
					pipeline = p;
				}
			}
		}
		return p;
	}

	@Override
	public synchronized void close() {
		closed = true;
		WorldPipeline p = pipeline;
		pipeline = null;
		if (p != null) {
			p.clearMemory();
		}
	}
}
