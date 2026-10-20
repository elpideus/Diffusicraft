package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OrtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The three Terrain Diffusion networks plus their configuration, loaded from one {@link ModelAssets} directory.
 * Shared by every world (pipelines differ only by seed); close it when the game shuts down.
 */
public final class ModelBundle implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModelBundle.class);
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
			OnnxModel decoder = loadDecoder(assets.path(DECODER), settings);
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

	/**
	 * The decoder in half precision when it will run on CUDA, else as downloaded. Any failure on the way falls back
	 * to the downloaded model.
	 */
	private static OnnxModel loadDecoder(Path file, OnnxModel.Settings settings) throws OrtException {
		if (settings.halfPrecision() && OnnxModel.preferredProvider(settings).equals("CUDA")) {
			try {
				OnnxModel decoder = OnnxModel.loadOn(HalfPrecision.ensure(file), "decoder", settings, "CUDA");
				LOGGER.info("Running the decoder in half precision");
				return decoder;
			} catch (IOException | OrtException | RuntimeException e) {
				LOGGER.warn("Could not run the decoder in half precision, using full precision: {}", e.toString());
			}
		}
		return OnnxModel.load(file, "decoder", settings);
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
