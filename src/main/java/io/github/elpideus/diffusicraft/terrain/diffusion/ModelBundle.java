package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OrtException;

import java.io.IOException;

/**
 * The three Terrain Diffusion networks plus their configuration, loaded from one {@link ModelAssets} directory.
 * Shared by every world (pipelines differ only by seed); close it when the game shuts down.
 */
public final class ModelBundle implements AutoCloseable {
	public static final String COARSE = "coarse_model.onnx";
	public static final String BASE = "base_model.onnx";
	public static final String DECODER = "decoder_model.onnx";

	private final String revision;
	private final ModelConfig config;
	private final ClimateQuantiles quantiles;
	private final OnnxModel coarse;
	private final OnnxModel base;
	private final OnnxModel decoder;

	private ModelBundle(String revision, ModelConfig config, ClimateQuantiles quantiles, OnnxModel coarse, OnnxModel base, OnnxModel decoder) {
		this.revision = revision;
		this.config = config;
		this.quantiles = quantiles;
		this.coarse = coarse;
		this.base = base;
		this.decoder = decoder;
	}

	/** Loads the models from verified assets. Call {@link ModelAssets#ensure} first. */
	public static ModelBundle load(ModelAssets assets, OnnxModel.Settings settings) throws IOException, OrtException {
		ModelConfig config = ModelConfig.load(assets.path(ModelConfig.FILE_NAME));
		ClimateQuantiles quantiles = ClimateQuantiles.load(assets.path(ClimateQuantiles.FILE_NAME));
		OnnxModel coarse = null;
		OnnxModel base = null;
		try {
			coarse = OnnxModel.load(assets.path(COARSE), "coarse", settings);
			base = OnnxModel.load(assets.path(BASE), "base", settings);
			OnnxModel decoder = OnnxModel.load(assets.path(DECODER), "decoder", settings);
			return new ModelBundle(assets.manifest().revision(), config, quantiles, coarse, base, decoder);
		} catch (OrtException | RuntimeException e) {
			if (coarse != null) {
				coarse.close();
			}
			if (base != null) {
				base.close();
			}
			throw e;
		}
	}

	/** Model revision; part of every persistent cache key. */
	public String revision() {
		return revision;
	}

	public ModelConfig config() {
		return config;
	}

	public ClimateQuantiles quantiles() {
		return quantiles;
	}

	public OnnxModel coarse() {
		return coarse;
	}

	public OnnxModel base() {
		return base;
	}

	public OnnxModel decoder() {
		return decoder;
	}

	@Override
	public void close() {
		coarse.close();
		base.close();
		decoder.close();
	}
}
