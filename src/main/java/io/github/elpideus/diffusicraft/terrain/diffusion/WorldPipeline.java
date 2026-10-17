/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion;

import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.Tile;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.TileGrid;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.WindowStore;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.WindowedTensor;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.WindowedTensor.Dependency;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.WindowedTensor.WindowIndex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Terrain Diffusion world pipeline for one seed: an infinite, lazily generated elevation and climate map at
 * the model's native resolution (30 m per pixel).
 *
 * <ol>
 *     <li><b>Coarse</b> (1 px = 256 native px): a 20-step DPM-Solver++ diffusion conditioned on a synthetic
 *     noise map, producing elevation, its 5th percentile and four climate channels.</li>
 *     <li><b>Latent</b> (1 px = 8 native px): two flow-matching steps of the base model, conditioned on the
 *     coarse map.</li>
 *     <li><b>Decoder</b>: one flow-matching step turning latents into a full-resolution elevation residual.</li>
 * </ol>
 * Elevation is the Laplacian decode of the residual over the latent low-frequency channel. Climate is the coarse
 * climate upsampled, with temperature corrected by a locally regressed lapse rate.
 *
 * <p>Thread-safe; any number of threads may call {@link #sample} concurrently. Results are deterministic for a
 * given seed and model revision regardless of call order, threading or execution provider rounding.
 */
public final class WorldPipeline {
	static final int COARSE_SIZE = 64;
	static final int COARSE_STRIDE = 48;
	static final int LATENT_SIZE = 64;
	static final int LATENT_STRIDE = 32;
	static final int DECODER_SIZE = 256;
	static final int DECODER_STRIDE = 192;
	/** Native pixels per coarse pixel. */
	static final int COARSE_SCALE = 256;

	private static final int COARSE_STEPS = 20;
	private static final int LATENT_BATCH = 4;
	private static final float SIGMA_DATA = EDMScheduler.SIGMA_DATA;
	/** Synthetic-map channel {@code k} is normalised with coarse channel {@code COARSE_COND_CHANNELS[k]}. */
	private static final int[] COARSE_COND_CHANNELS = {0, 2, 3, 4, 5};
	private static final float[] COND_MEANS = {14.99f, 11.65f, 15.87f, 619.26f, 833.12f, 69.40f, 0.66f};
	private static final float[] COND_STDS = {21.72f, 21.78f, 10.40f, 452.29f, 738.09f, 34.59f, 0.47f};
	private static final float LOWFREQ_MEAN = -31.4f;
	private static final float LOWFREQ_STD = 38.6f;
	/** Sizes of the six vectors concatenated into the base model's conditioning. */
	private static final int[] COND_DIMS = {16, 16, 4, 16, 5, 1};
	private static final int COND_LENGTH = 58;
	private static final float[] MP_CONCAT_SCALES = mpConcatScales();
	private static final float ELEVATION_DENOISE_SIGMA = 5f;
	private static final int CLIMATE_WINDOW = 15;
	private static final float WEIGHT_EPS = 1e-6f;

	/** Soft in-memory limits per stage. Persistent windows are reloaded from the {@link WindowStore} when evicted. */
	public record CacheLimits(long coarse, long initLatent, long latent, long residual) {
		public static CacheLimits defaults() {
			return new CacheLimits(32L << 20, 32L << 20, 64L << 20, 256L << 20);
		}
	}

	private final long seed;
	private final ModelBundle models;
	private final ModelConfig config;
	private final int latentCompression;
	private final SyntheticMapFactory synthetic;
	private final float[] condVals;
	private final float[] coarseWeights = linearWeightWindow(COARSE_SIZE);
	private final float[] latentWeights = linearWeightWindow(LATENT_SIZE);
	private final float[] decoderWeights = linearWeightWindow(DECODER_SIZE);

	private final WindowedTensor coarse;
	private final WindowedTensor initLatent;
	private final WindowedTensor latent;
	private final WindowedTensor residual;

	/**
	 * @param seed   world seed
	 * @param models shared models (not closed by the pipeline)
	 * @param store  persistent window cache for this seed and model revision, or {@code null}
	 */
	public WorldPipeline(long seed, ModelBundle models, WindowStore store, CacheLimits limits) {
		this.seed = seed;
		this.models = models;
		this.config = models.config();
		this.latentCompression = config.latentCompression();
		this.synthetic = new SyntheticMapFactory(seed, config.frequencyMult(), models.quantiles());
		this.condVals = new float[config.condSnr().length];
		for (int i = 0; i < condVals.length; i++) {
			condVals[i] = (float) Math.log(config.condSnr()[i] / 8.0);
		}
		if (DECODER_SIZE % latentCompression != 0 || DECODER_STRIDE % latentCompression != 0) {
			throw new IllegalArgumentException("Unsupported latent compression " + latentCompression);
		}

		TileGrid coarseGrid = new TileGrid(COARSE_SIZE, COARSE_STRIDE);
		TileGrid latentGrid = new TileGrid(LATENT_SIZE, LATENT_STRIDE);
		// A latent window at latent pixel 32*i reads the 4x4 coarse pixels starting one before coarse pixel i.
		TileGrid coarseForLatent = new TileGrid(4, 1, -1);
		TileGrid latentForDecoder = new TileGrid(DECODER_SIZE / latentCompression, DECODER_STRIDE / latentCompression);

		float tInit = (float) Math.atan(EDMScheduler.SIGMA_MAX / SIGMA_DATA);
		float tStep = (float) Math.atan(0.35f / SIGMA_DATA);

		this.coarse = new WindowedTensor("coarse", 7, coarseGrid, List.of(),
			(windows, deps) -> coarseWindows(windows),
			models.coarse().batchable() ? 4 : 1, limits.coarse(), store);
		// The first latent step is cheap relative to its consumers and never read directly, so it is not persisted.
		this.initLatent = new WindowedTensor("init_latent", 6, latentGrid,
			List.of(new Dependency(coarse, coarseForLatent)),
			(windows, deps) -> latentWindows(windows, null, deps.get(0), tInit, 5819),
			LATENT_BATCH, limits.initLatent(), null);
		this.latent = new WindowedTensor("latent", 6, latentGrid,
			List.of(new Dependency(initLatent, latentGrid), new Dependency(coarse, coarseForLatent)),
			(windows, deps) -> latentWindows(windows, deps.get(0), deps.get(1), tStep, 5820),
			LATENT_BATCH, limits.latent(), store);
		this.residual = new WindowedTensor("residual", 2, new TileGrid(DECODER_SIZE, DECODER_STRIDE),
			List.of(new Dependency(latent, latentForDecoder)),
			(windows, deps) -> decoderWindows(windows, deps.get(0), tInit),
			models.decoder().batchable() ? 2 : 1, limits.residual(), store);
	}

	public long seed() {
		return seed;
	}

	public ModelConfig config() {
		return config;
	}

	/** Windows computed by inference since creation, across all stages. */
	public long computedWindows() {
		return coarse.computedWindows() + initLatent.computedWindows() + latent.computedWindows() + residual.computedWindows();
	}

	/** Drops all in-memory windows. */
	public void clearMemory() {
		residual.clearMemory();
		latent.clearMemory();
		initLatent.clearMemory();
		coarse.clearMemory();
	}

	/**
	 * Elevation (and optionally climate) over native pixels {@code [i0, i1) x [j0, j1)}.
	 * Larger requests amortise the padding each request needs; 256x256 or more is a good unit.
	 */
	public TerrainSample sample(int i0, int j0, int i1, int j1, boolean withClimate) {
		int h = i1 - i0, w = j1 - j0;
		float[] elevation = elevation(i0, j0, i1, j1);
		float[] climate = withClimate ? climate(i0, j0, i1, j1, elevation) : null;
		return new TerrainSample(i0, j0, h, w, elevation, climate);
	}

	/** Raw coarse map {@code [7][ci1-ci0][cj1-cj0]} in coarse pixels; channel 6 is the blend weight. */
	public Tile coarseSlice(int ci0, int cj0, int ci1, int cj1) {
		return coarse.slice(ci0, cj0, ci1, cj1);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Coarse stage
	// ---------------------------------------------------------------------------------------------------------

	private List<Tile> coarseWindows(List<WindowIndex> windows) {
		final int s = COARSE_SIZE, plane = s * s, batch = windows.size();
		float[] means = config.coarseMeans(), stds = config.coarseStds(), snr = config.condSnr();
		EDMScheduler scheduler = new EDMScheduler(COARSE_STEPS);

		float[] cond = new float[batch * 5 * plane];
		float[] sample = new float[batch * 6 * plane];
		for (int b = 0; b < batch; b++) {
			int i1 = windows.get(b).i() * COARSE_STRIDE, j1 = windows.get(b).j() * COARSE_STRIDE;
			// The synthetic map takes (x, y) order: columns first.
			float[][][] syn = synthetic.sample(j1, i1, j1 + s, i1 + s);
			float[] noise = GaussianNoisePatch.generateFlat(seed, i1, j1, s, s, 5, s, s);
			for (int ch = 0; ch < 5; ch++) {
				float mean = means[COARSE_COND_CHANNELS[ch]], std = stds[COARSE_COND_CHANNELS[ch]];
				double angle = Math.atan(snr[ch]);
				float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
				int base = (b * 5 + ch) * plane;
				for (int r = 0; r < s; r++) {
					float[] row = syn[ch][r];
					for (int c = 0; c < s; c++) {
						float v = row[c];
						if (ch == 1 && v <= 20f) {
							v = (v - 20f) * 1.25f + 20f;
						}
						int px = r * s + c;
						cond[base + px] = cos * ((v - mean) / std) + sin * noise[ch * plane + px];
					}
				}
			}
			float[] init = GaussianNoisePatch.generateFlat(seed + 1, i1, j1, s, s, 6, s, s);
			for (int k = 0; k < init.length; k++) {
				sample[b * 6 * plane + k] = init[k] * scheduler.sigmas[0];
			}
		}

		float[][] condInputs = new float[5][];
		long[][] condShapes = new long[5][];
		for (int c = 0; c < 5; c++) {
			condInputs[c] = new float[batch];
			Arrays.fill(condInputs[c], condVals[c]);
			condShapes[c] = new long[]{batch};
		}
		float[] noiseLabels = new float[batch];
		float[] x = new float[batch * 11 * plane];
		long[] xShape = {batch, 11, s, s};
		for (int step = 0; step < COARSE_STEPS; step++) {
			float sigma = scheduler.sigmas[step];
			Arrays.fill(noiseLabels, EDMScheduler.trigflowPreconditionNoise(sigma));
			float[] scaled = EDMScheduler.preconditionInputs(sample, sigma);
			for (int b = 0; b < batch; b++) {
				System.arraycopy(scaled, b * 6 * plane, x, b * 11 * plane, 6 * plane);
				System.arraycopy(cond, b * 5 * plane, x, b * 11 * plane + 6 * plane, 5 * plane);
			}
			float[] out = models.coarse().run(x, xShape, noiseLabels, condInputs, condShapes);
			sample = scheduler.step(out, sample);
		}

		List<Tile> tiles = new ArrayList<>(batch);
		for (int b = 0; b < batch; b++) {
			Tile tile = new Tile(7, s, s);
			float[] dst = tile.data;
			for (int ch = 0; ch < 6; ch++) {
				int src = (b * 6 + ch) * plane;
				for (int px = 0; px < plane; px++) {
					dst[ch * plane + px] = sample[src + px] / SIGMA_DATA * stds[ch] + means[ch];
				}
			}
			for (int px = 0; px < plane; px++) {
				// Channel 1 is predicted as (elevation - p5); store p5 itself.
				dst[plane + px] = dst[px] - dst[plane + px];
			}
			applyWeights(dst, 6, plane, coarseWeights);
			tiles.add(tile);
		}
		return tiles;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Latent stage
	// ---------------------------------------------------------------------------------------------------------

	/**
	 * One flow-matching step of the base model for a batch of windows.
	 *
	 * @param previous previous step's weighted windows, or {@code null} to start from pure noise
	 */
	private List<Tile> latentWindows(List<WindowIndex> windows, List<Tile> previous, List<Tile> coarseSlices,
									 float t, long seedOffset) {
		final int s = LATENT_SIZE, plane = s * s, batch = windows.size(), size = 5 * plane;
		float cos = (float) Math.cos(t), sin = (float) Math.sin(t);
		float[] xt = new float[batch * size];
		float[] modelIn = new float[batch * size];
		float[] cond = new float[batch * COND_LENGTH];
		for (int b = 0; b < batch; b++) {
			int i1 = windows.get(b).i() * LATENT_STRIDE, j1 = windows.get(b).j() * LATENT_STRIDE;
			latentConditioning(coarseSlices.get(b), cond, b * COND_LENGTH);
			float[] noise = GaussianNoisePatch.generateFlat(seed + seedOffset, i1, j1, s, s, 5, s, s);
			float[] prev = previous != null ? previous.get(b).data : null;
			for (int ch = 0; ch < 5; ch++) {
				for (int px = 0; px < plane; px++) {
					int k = ch * plane + px;
					float x0 = 0f;
					if (prev != null) {
						float w = prev[5 * plane + px];
						x0 = w > WEIGHT_EPS ? prev[k] / w * SIGMA_DATA : 0f;
					}
					float v = cos * x0 + sin * noise[k] * SIGMA_DATA;
					xt[b * size + k] = v;
					modelIn[b * size + k] = v / SIGMA_DATA;
				}
			}
		}
		float[] noiseLabels = new float[batch];
		Arrays.fill(noiseLabels, t);
		float[] pred = models.base().run(modelIn, new long[]{batch, 5, s, s}, noiseLabels,
			new float[][]{cond}, new long[][]{{batch, COND_LENGTH}});

		List<Tile> tiles = new ArrayList<>(batch);
		for (int b = 0; b < batch; b++) {
			Tile tile = new Tile(6, s, s);
			float[] dst = tile.data;
			for (int k = 0; k < size; k++) {
				// The exported model predicts the negated velocity.
				dst[k] = (cos * xt[b * size + k] + sin * SIGMA_DATA * pred[b * size + k]) / SIGMA_DATA;
			}
			applyWeights(dst, 5, plane, latentWeights);
			tiles.add(tile);
		}
		return tiles;
	}

	/** Writes the 58-value base-model conditioning for a weighted {@code [7][4][4]} coarse slice. */
	private void latentConditioning(Tile coarseSlice, float[] out, int offset) {
		final int n = 16;
		float[] src = coarseSlice.data;
		float[] img = new float[6 * n];
		for (int ch = 0; ch < 6; ch++) {
			for (int px = 0; px < n; px++) {
				float w = src[6 * n + px];
				float v = w > WEIGHT_EPS ? src[ch * n + px] / w : 0f;
				float norm = (v - COND_MEANS[ch]) / COND_STDS[ch];
				img[ch * n + px] = Float.isNaN(norm) ? 0f : norm;
			}
		}
		// Every pixel is known, so the mask channel is all ones (normalised).
		float mask = (1f - COND_MEANS[6]) / COND_STDS[6];
		float[] climateMeans = new float[4];
		for (int ch = 0; ch < 4; ch++) {
			float sum = 0;
			for (int r = 1; r < 3; r++) {
				for (int c = 1; c < 3; c++) {
					sum += img[(2 + ch) * n + r * 4 + c];
				}
			}
			climateMeans[ch] = Float.isNaN(sum) ? 0f : sum / 4f;
		}
		float noiseLevel = (0f - 0.5f) * (float) Math.sqrt(12.0);
		float[] histogram = config.histogramRaw();

		int o = offset;
		for (int px = 0; px < n; px++) {
			out[o++] = img[px] * MP_CONCAT_SCALES[0];
		}
		for (int px = 0; px < n; px++) {
			out[o++] = img[n + px] * MP_CONCAT_SCALES[1];
		}
		for (float v : climateMeans) {
			out[o++] = v * MP_CONCAT_SCALES[2];
		}
		for (int px = 0; px < n; px++) {
			out[o++] = mask * MP_CONCAT_SCALES[3];
		}
		for (float v : histogram) {
			out[o++] = v * MP_CONCAT_SCALES[4];
		}
		out[o] = noiseLevel * MP_CONCAT_SCALES[5];
	}

	// ---------------------------------------------------------------------------------------------------------
	// Decoder stage
	// ---------------------------------------------------------------------------------------------------------

	private List<Tile> decoderWindows(List<WindowIndex> windows, List<Tile> latentSlices, float t) {
		final int s = DECODER_SIZE, plane = s * s, batch = windows.size();
		final int ls = s / latentCompression, lplane = ls * ls;
		float cos = (float) Math.cos(t), sin = (float) Math.sin(t);
		float[] xt = new float[batch * plane];
		float[] modelIn = new float[batch * 5 * plane];
		for (int b = 0; b < batch; b++) {
			int i1 = windows.get(b).i() * DECODER_STRIDE, j1 = windows.get(b).j() * DECODER_STRIDE;
			float[] lat = latentSlices.get(b).data;
			float[] noise = GaussianNoisePatch.generateFlat(seed + 5819, i1, j1, s, s, 1, s, s);
			int base = b * 5 * plane;
			for (int px = 0; px < plane; px++) {
				// The sample starts at zero, so x_t is pure noise.
				float v = sin * noise[px] * SIGMA_DATA;
				xt[b * plane + px] = v;
				modelIn[base + px] = v / SIGMA_DATA;
			}
			// Nearest-neighbour upsampling of latent channels 0..3 into model input channels 1..4.
			for (int ch = 0; ch < 4; ch++) {
				int dstPlane = base + (ch + 1) * plane;
				for (int r = 0; r < s; r++) {
					int lr = r / latentCompression;
					for (int c = 0; c < s; c++) {
						int lpx = lr * ls + c / latentCompression;
						float w = lat[5 * lplane + lpx];
						modelIn[dstPlane + r * s + c] = w > WEIGHT_EPS ? lat[ch * lplane + lpx] / w : 0f;
					}
				}
			}
		}
		float[] noiseLabels = new float[batch];
		Arrays.fill(noiseLabels, t);
		float[] pred = models.decoder().run(modelIn, new long[]{batch, 5, s, s}, noiseLabels, null, null);

		List<Tile> tiles = new ArrayList<>(batch);
		for (int b = 0; b < batch; b++) {
			Tile tile = new Tile(2, s, s);
			float[] dst = tile.data;
			for (int px = 0; px < plane; px++) {
				dst[px] = (cos * xt[b * plane + px] + sin * SIGMA_DATA * pred[b * plane + px]) / SIGMA_DATA;
			}
			applyWeights(dst, 1, plane, decoderWeights);
			tiles.add(tile);
		}
		return tiles;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Elevation and climate
	// ---------------------------------------------------------------------------------------------------------

	private float[] elevation(int i1, int j1, int i2, int j2) {
		int lc = latentCompression;
		int kernel = ((int) (ELEVATION_DENOISE_SIGMA * 2) / 2) * 2 + 1;
		int pad = (kernel / 2 + 1) * lc;
		// Pad and align to the latent grid.
		int pi1 = Math.floorDiv(i1 - pad, lc) * lc;
		int pj1 = Math.floorDiv(j1 - pad, lc) * lc;
		int pi2 = -Math.floorDiv(-(i2 + pad), lc) * lc;
		int pj2 = -Math.floorDiv(-(j2 + pad), lc) * lc;
		int ph = pi2 - pi1, pw = pj2 - pj1;

		Tile res = residual.slice(pi1, pj1, pi2, pj2);
		float residualStd = config.residualStd(), residualMean = config.residualMean();
		float[][] residualP = new float[ph][pw];
		for (int r = 0; r < ph; r++) {
			for (int c = 0; c < pw; c++) {
				float w = res.data[ph * pw + r * pw + c];
				float v = w > WEIGHT_EPS ? res.data[r * pw + c] / w : 0f;
				residualP[r][c] = v * residualStd + residualMean;
			}
		}

		int lh = ph / lc, lw = pw / lc;
		Tile lat = latent.slice(pi1 / lc, pj1 / lc, pi2 / lc, pj2 / lc);
		int lplane = lh * lw;
		float[][] lowfreq = new float[lh][lw];
		for (int r = 0; r < lh; r++) {
			for (int c = 0; c < lw; c++) {
				int px = r * lw + c;
				float w = lat.data[5 * lplane + px];
				float v = w > WEIGHT_EPS ? lat.data[4 * lplane + px] / w : 0f;
				lowfreq[r][c] = v * LOWFREQ_STD + LOWFREQ_MEAN;
			}
		}

		float[][] lowres = LaplacianUtils.laplacianDenoise(residualP, lowfreq, ELEVATION_DENOISE_SIGMA);
		float[][] decoded = LaplacianUtils.laplacianDecode(residualP, lowres);

		int oi = i1 - pi1, oj = j1 - pj1, h = i2 - i1, w = j2 - j1;
		float[] out = new float[h * w];
		for (int r = 0; r < h; r++) {
			float[] row = decoded[oi + r];
			for (int c = 0; c < w; c++) {
				// The model works in signed square-root meters.
				float v = row[oj + c];
				out[r * w + c] = Math.signum(v) * v * v;
			}
		}
		return out;
	}

	private float[] climate(int i1, int j1, int i2, int j2, float[] elevation) {
		final int scale = COARSE_SCALE;
		int ci1 = Math.floorDiv(i1, scale), cj1 = Math.floorDiv(j1, scale);
		int ci2 = -Math.floorDiv(-i2, scale), cj2 = -Math.floorDiv(-j2, scale);
		int pad = (CLIMATE_WINDOW - 1) / 2 + 1;

		Tile slice = coarse.slice(ci1 - pad, cj1 - pad, ci2 + pad, cj2 + pad);
		int ch = slice.height, cw = slice.width, cplane = ch * cw;
		float[][] map = new float[6][cplane];
		for (int k = 0; k < 6; k++) {
			for (int px = 0; px < cplane; px++) {
				float w = slice.data[6 * cplane + px];
				map[k][px] = w > WEIGHT_EPS ? slice.data[k * cplane + px] / w : 0f;
			}
		}
		// Coarse elevation is stored as signed sqrt; the oceans clamp to zero.
		float[] coarseElevation = new float[cplane];
		for (int px = 0; px < cplane; px++) {
			float v = Math.max(0f, map[0][px]);
			coarseElevation[px] = v * v;
		}

		float[][][] baseline = LaplacianUtils.localBaselineTemperature(
			to2D(map[2], ch, cw), to2D(coarseElevation, ch, cw), CLIMATE_WINDOW, 0.02f);
		int bh = baseline[0].length, bw = baseline[0][0].length;
		int crop = CLIMATE_WINDOW / 2;
		int ih = ch - 2 * crop, iw = cw - 2 * crop;
		float[][] tempStd = crop(to2D(map[3], ch, cw), crop, ih, iw);
		float[][] precip = crop(to2D(map[4], ch, cw), crop, ih, iw);
		float[][] precipStd = crop(to2D(map[5], ch, cw), crop, ih, iw);

		int h = i2 - i1, w = j2 - j1, plane = h * w;
		float[] out = new float[TerrainSample.CLIMATE_CHANNELS * plane];
		for (int r = 0; r < h; r++) {
			float gy = (i1 + r + 0.5f) / scale - ci1 + 0.5f;
			for (int c = 0; c < w; c++) {
				float gx = (j1 + c + 0.5f) / scale - cj1 + 0.5f;
				int px = r * w + c;
				float seaLevelTemp = bilinear(baseline[0], bh, bw, gy, gx);
				float lapse = bilinear(baseline[1], bh, bw, gy, gx);
				out[TerrainSample.TEMPERATURE * plane + px] = seaLevelTemp + lapse * Math.max(0f, elevation[px]);
				out[TerrainSample.TEMPERATURE_SEASONALITY * plane + px] = bilinear(tempStd, ih, iw, gy, gx);
				out[TerrainSample.PRECIPITATION * plane + px] = bilinear(precip, ih, iw, gy, gx);
				out[TerrainSample.PRECIPITATION_SEASONALITY * plane + px] = bilinear(precipStd, ih, iw, gy, gx);
				out[TerrainSample.LAPSE_RATE * plane + px] = lapse;
			}
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------------------

	/** Multiplies the first {@code channels} planes by {@code weights} and stores the weights in the next plane. */
	private static void applyWeights(float[] data, int channels, int plane, float[] weights) {
		for (int ch = 0; ch < channels; ch++) {
			int base = ch * plane;
			for (int px = 0; px < plane; px++) {
				data[base + px] *= weights[px];
			}
		}
		System.arraycopy(weights, 0, data, channels * plane, plane);
	}

	/** Separable tent window, 1 at the centre and {@code 1e-3} at the edges, used to blend overlapping windows. */
	static float[] linearWeightWindow(int size) {
		float[] w = new float[size * size];
		float mid = (size - 1) / 2f, eps = 1e-3f;
		for (int r = 0; r < size; r++) {
			float wy = 1f - (1f - eps) * Math.min(1f, Math.abs(r - mid) / mid);
			for (int c = 0; c < size; c++) {
				float wx = 1f - (1f - eps) * Math.min(1f, Math.abs(c - mid) / mid);
				w[r * size + c] = wy * wx;
			}
		}
		return w;
	}

	/** Magnitude-preserving concatenation scales: {@code sqrt(sum(n) * k) / sqrt(n_i) / k}. */
	private static float[] mpConcatScales() {
		int sum = 0;
		for (int n : COND_DIMS) {
			sum += n;
		}
		if (sum != COND_LENGTH) {
			throw new AssertionError(sum);
		}
		int k = COND_DIMS.length;
		float c = (float) Math.sqrt((double) sum * k);
		float[] scales = new float[k];
		for (int i = 0; i < k; i++) {
			scales[i] = c / (float) Math.sqrt(COND_DIMS[i]) / k;
		}
		return scales;
	}

	private static float[][] to2D(float[] flat, int h, int w) {
		float[][] out = new float[h][w];
		for (int r = 0; r < h; r++) {
			System.arraycopy(flat, r * w, out[r], 0, w);
		}
		return out;
	}

	private static float[][] crop(float[][] src, int margin, int h, int w) {
		float[][] out = new float[h][w];
		for (int r = 0; r < h; r++) {
			System.arraycopy(src[r + margin], margin, out[r], 0, w);
		}
		return out;
	}

	/** Bilinear sample with edge clamping; {@code gy, gx} are fractional array indices. */
	static float bilinear(float[][] src, int h, int w, float gy, float gx) {
		float y = Math.max(0f, Math.min(h - 1f, gy));
		float x = Math.max(0f, Math.min(w - 1f, gx));
		int y0 = (int) y, y1 = Math.min(h - 1, y0 + 1);
		int x0 = (int) x, x1 = Math.min(w - 1, x0 + 1);
		float wy = y - y0, wx = x - x0;
		return (1 - wy) * (1 - wx) * src[y0][x0] + (1 - wy) * wx * src[y0][x1]
			+ wy * (1 - wx) * src[y1][x0] + wy * wx * src[y1][x1];
	}
}
