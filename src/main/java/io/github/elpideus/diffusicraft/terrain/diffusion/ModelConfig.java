/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Model-specific constants of the Terrain Diffusion pipeline, read from {@code world_pipeline_config.json}
 * (shipped next to the ONNX models). Swapping in a differently trained model only needs a new config file.
 *
 * @param nativeResolution   meters per pixel of the decoded output
 * @param latentCompression  native pixels per latent pixel
 * @param coarseMeans        denormalisation means of the 6 coarse channels
 * @param coarseStds         denormalisation stds of the 6 coarse channels
 * @param condSnr            signal-to-noise ratio of the 5 conditioning channels
 * @param frequencyMult      frequency multipliers of the 5 synthetic-map channels
 * @param residualMean       residual denormalisation mean
 * @param residualStd        residual denormalisation std
 * @param coarsePooling      coarse pooling factor (only 1 is supported)
 * @param histogramRaw       optional 5-value histogram conditioning (zeros when absent)
 */
public record ModelConfig(
	float nativeResolution,
	int latentCompression,
	float[] coarseMeans,
	float[] coarseStds,
	float[] condSnr,
	float[] frequencyMult,
	float residualMean,
	float residualStd,
	int coarsePooling,
	float[] histogramRaw
) {
	public static final String FILE_NAME = "world_pipeline_config.json";

	public ModelConfig {
		requireLength(coarseMeans, 6, "coarse_means");
		requireLength(coarseStds, 6, "coarse_stds");
		requireLength(condSnr, 5, "cond_snr");
		requireLength(frequencyMult, 5, "frequency_mult");
		if (histogramRaw == null) {
			histogramRaw = new float[5];
		}
		requireLength(histogramRaw, 5, "histogram_raw");
		if (coarsePooling != 1) {
			throw new IllegalArgumentException("coarse_pooling=" + coarsePooling + " is not supported yet");
		}
		if (latentCompression <= 0 || nativeResolution <= 0f) {
			throw new IllegalArgumentException("latent_compression and native_resolution must be positive");
		}
	}

	public static ModelConfig load(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Json json = new Gson().fromJson(reader, Json.class);
			if (json == null) {
				throw new IOException("Empty model config: " + file);
			}
			return new ModelConfig(json.nativeResolution, json.latentCompression, json.coarseMeans, json.coarseStds,
				json.condSnr, json.frequencyMult, json.residualMean, json.residualStd, json.coarsePooling, json.histogramRaw);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid model config " + file + ": " + e.getMessage(), e);
		}
	}

	private static void requireLength(float[] values, int length, String field) {
		if (values == null || values.length != length) {
			throw new IllegalArgumentException(field + " must contain exactly " + length + " values");
		}
	}

	private static final class Json {
		@SerializedName("native_resolution") float nativeResolution;
		@SerializedName("latent_compression") int latentCompression;
		@SerializedName("coarse_means") float[] coarseMeans;
		@SerializedName("coarse_stds") float[] coarseStds;
		@SerializedName("cond_snr") float[] condSnr;
		@SerializedName("frequency_mult") float[] frequencyMult;
		@SerializedName("residual_mean") float residualMean;
		@SerializedName("residual_std") float residualStd;
		@SerializedName("coarse_pooling") int coarsePooling;
		@SerializedName("histogram_raw") float[] histogramRaw;
	}
}
