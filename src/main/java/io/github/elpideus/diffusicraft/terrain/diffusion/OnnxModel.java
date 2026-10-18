/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.providers.CoreMLFlags;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import io.github.elpideus.diffusicraft.runtime.NativeRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One ONNX Runtime session. Sessions are created straight from the model file, so the 2 GB base model is never
 * copied onto the Java heap. The requested execution provider is tried first, then any other GPU provider the
 * loaded native library contains, then the CPU. Which native library is loaded (CPU-only, CUDA, ROCm/MIGraphX) is
 * decided before ONNX Runtime initialises; see {@link NativeRuntime}.
 *
 * <p>{@link #run} is thread-safe. Concurrent runs are limited by a shared semaphore because each run already
 * uses many threads (CPU) or the whole device (GPU); oversubscribing only adds contention.
 */
public final class OnnxModel implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(OnnxModel.class);

	public enum Device {
		/** Best available: CUDA, then MIGraphX/ROCm, DirectML, CoreML, WebGPU, then the CPU. */
		AUTO,
		CPU,
		/** NVIDIA CUDA (needs the CUDA runtime package). */
		CUDA,
		/**
		 * AMD GPUs through a ROCm build of ONNX Runtime. Recent builds (1.23 and newer) only have the MIGraphX provider,
		 * older ones the ROCm provider; whichever the library contains is used.
		 */
		ROCM
	}

	/**
	 * @param device           preferred execution provider; others are tried if it fails
	 * @param deviceId         GPU index
	 * @param intraOpThreads   CPU threads per run, or 0 for ONNX Runtime's default (physical cores)
	 * @param gpuMemoryLimit   cap on the GPU memory arena in bytes, or 0 for no cap; leaves room for the game and shaders
	 * @param providerOptions  extra provider options that override the defaults (e.g. {@code use_tf32})
	 * @param permits          shared limit on concurrent runs across all models
	 */
	public record Settings(Device device, int deviceId, int intraOpThreads, long gpuMemoryLimit,
						   Map<String, String> providerOptions, Semaphore permits) {
		public Settings {
			providerOptions = Map.copyOf(providerOptions);
		}

		public static Settings defaults() {
			return new Settings(Device.AUTO, 0, 0, 0, Map.of(), new Semaphore(1, true));
		}
	}

	private final String name;
	private final OrtSession session;
	private final String provider;
	private final Semaphore permits;
	/** Guarded by {@link #permits}: a closed session must never run, or the native code crashes. */
	private boolean closed;
	private final boolean batchable;
	private final AtomicLong runs = new AtomicLong();
	private final AtomicLong runNanos = new AtomicLong();

	private OnnxModel(String name, OrtSession session, String provider, Semaphore permits) throws OrtException {
		this.name = name;
		this.session = session;
		this.provider = provider;
		this.permits = permits;
		this.batchable = session.getInputInfo().get("x").getInfo() instanceof TensorInfo info
			&& info.getShape().length > 0 && info.getShape()[0] < 0;
	}

	static OrtEnvironment environment() {
		// Decides which native build ONNX Runtime loads; must happen before its classes initialise.
		NativeRuntime.activate();
		return OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "diffusicraft");
	}

	public static OnnxModel load(Path file, String name, Settings settings) throws OrtException {
		long start = System.nanoTime();
		OrtEnvironment env = environment();
		EnumSet<OrtProvider> available = OrtEnvironment.getAvailableProviders();
		List<String> candidates = candidates(settings.device(), available);
		OrtException lastFailure = null;
		for (String candidate : candidates) {
			try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
				options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
				if (settings.intraOpThreads() > 0) {
					options.setIntraOpNumThreads(settings.intraOpThreads());
				}
				addProvider(options, candidate, settings);
				OrtSession session = env.createSession(file.toAbsolutePath().toString(), options);
				LOGGER.info("Loaded model '{}' on {} in {} ms", name, candidate, (System.nanoTime() - start) / 1_000_000);
				return new OnnxModel(name, session, candidate, settings.permits());
			} catch (OrtException | RuntimeException | LinkageError e) {
				LOGGER.warn("Could not load model '{}' with {}: {}", name, candidate, e.toString());
				lastFailure = e instanceof OrtException ort ? ort : new OrtException(e.toString());
			}
		}
		throw lastFailure != null ? lastFailure : new OrtException("No execution provider could load '" + name + "' (available: " + available + ")");
	}

	/** Provider names to try, in order; always ends with the CPU. */
	static List<String> candidates(Device device, EnumSet<OrtProvider> available) {
		List<OrtProvider> order = new ArrayList<>();
		switch (device) {
			case CUDA -> order.add(OrtProvider.CUDA);
			case ROCM -> order.addAll(List.of(OrtProvider.MI_GRAPH_X, OrtProvider.ROCM));
			default -> {
			}
		}
		if (device != Device.CPU) {
			order.addAll(List.of(OrtProvider.CUDA, OrtProvider.MI_GRAPH_X, OrtProvider.ROCM, OrtProvider.DIRECT_ML,
				OrtProvider.CORE_ML, OrtProvider.WEBGPU));
		}
		List<String> names = new ArrayList<>();
		for (OrtProvider provider : order) {
			if (available.contains(provider) && !names.contains(provider.name())) {
				names.add(provider.name());
			}
		}
		names.add(OrtProvider.CPU.name());
		return names;
	}

	private static void addProvider(OrtSession.SessionOptions options, String provider, Settings settings) throws OrtException {
		switch (provider) {
			case "CUDA" -> {
				try (OrtCUDAProviderOptions cuda = new OrtCUDAProviderOptions(settings.deviceId())) {
					Map<String, String> values = new LinkedHashMap<>();
					// Grow the arena only by what is needed; it is shared with the game's renderer.
					values.put("arena_extend_strategy", "kSameAsRequested");
					// Window shapes are fixed, so the exhaustive search runs once per shape and pays off.
					values.put("cudnn_conv_algo_search", "EXHAUSTIVE");
					// TF32 tensor cores (Ampere and newer); terrain does not need full fp32 mantissas.
					values.put("use_tf32", "1");
					values.put("do_copy_in_default_stream", "1");
					if (settings.gpuMemoryLimit() > 0) {
						values.put("gpu_mem_limit", Long.toString(settings.gpuMemoryLimit()));
					}
					values.putAll(settings.providerOptions());
					for (Map.Entry<String, String> e : values.entrySet()) {
						cuda.add(e.getKey(), e.getValue());
					}
					options.addCUDA(cuda);
				}
			}
			case "ROCM" -> options.addROCM(settings.deviceId());
			case "MI_GRAPH_X" -> {
				// The gpu_mem_limit cap is CUDA-only: MIGraphX option names vary between ROCm releases, and an unknown
				// one fails the whole provider.
				Map<String, String> values = new LinkedHashMap<>();
				values.put("device_id", Integer.toString(settings.deviceId()));
				values.putAll(settings.providerOptions());
				addByName(options, "MIGraphX", values);
			}
			case "DIRECT_ML" -> {
				// DirectML does not support memory patterns or parallel execution.
				options.setMemoryPatternOptimization(false);
				options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
				options.addDirectML(settings.deviceId());
			}
			case "CORE_ML" -> options.addCoreML(EnumSet.of(CoreMLFlags.ENABLE_ON_SUBGRAPH));
			case "WEBGPU" -> options.addWebGPU(Map.of());
			case "CPU" -> {
			}
			default -> throw new IllegalArgumentException(provider);
		}
	}

	/**
	 * Appends a provider through ONNX Runtime's generic {@code SessionOptionsAppendExecutionProvider}, which the Java
	 * API only exposes for a few providers (XNNPACK, QNN, CoreML, WebGPU) and keeps private otherwise.
	 */
	static void addByName(OrtSession.SessionOptions options, String provider, Map<String, String> values) throws OrtException {
		try {
			Method add = OrtSession.SessionOptions.class.getDeclaredMethod("addExecutionProvider", String.class, Map.class);
			add.setAccessible(true);
			add.invoke(options, provider, values);
		} catch (InvocationTargetException e) {
			throw e.getCause() instanceof OrtException ort ? ort : new OrtException(String.valueOf(e.getCause()));
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new OrtException("This ONNX Runtime version cannot add " + provider + " by name: " + e);
		}
	}

	public String name() {
		return name;
	}

	/** Execution provider actually in use, e.g. {@code CUDA} or {@code CPU}. */
	public String provider() {
		return provider;
	}

	/** Completed runs since load. */
	public long runs() {
		return runs.get();
	}

	/** Total wall time spent inside runs, in nanoseconds (excludes waiting for a permit). */
	public long runNanos() {
		return runNanos.get();
	}

	/** Whether input {@code x} has a dynamic batch dimension, so several windows can share one run. */
	public boolean batchable() {
		return batchable;
	}

	/**
	 * Runs the model with the pipeline's input convention: {@code x}, {@code noise_labels} and optional
	 * {@code cond_0..cond_n}. Returns the first output, flattened.
	 */
	public float[] run(float[] x, long[] xShape, float[] noiseLabels, float[][] cond, long[][] condShapes) {
		OrtEnvironment env = environment();
		Map<String, OnnxTensor> feed = new LinkedHashMap<>();
		boolean acquired = false;
		try {
			feed.put("x", OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape));
			feed.put("noise_labels", OnnxTensor.createTensor(env, FloatBuffer.wrap(noiseLabels), new long[]{noiseLabels.length}));
			if (cond != null) {
				for (int i = 0; i < cond.length; i++) {
					feed.put("cond_" + i, OnnxTensor.createTensor(env, FloatBuffer.wrap(cond[i]), condShapes[i]));
				}
			}
			permits.acquireUninterruptibly();
			acquired = true;
			if (closed) {
				throw new IllegalStateException("Model '" + name + "' is closed");
			}
			long start = System.nanoTime();
			try (OrtSession.Result result = session.run(feed)) {
				OnnxValue value = result.get(0);
				FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
				float[] out = new float[buffer.remaining()];
				buffer.get(out);
				return out;
			} finally {
				runNanos.addAndGet(System.nanoTime() - start);
				runs.incrementAndGet();
			}
		} catch (OrtException e) {
			throw new IllegalStateException("Inference failed for model '" + name + "' on " + provider, e);
		} finally {
			if (acquired) {
				permits.release();
			}
			for (OnnxTensor tensor : feed.values()) {
				tensor.close();
			}
		}
	}

	/** Waits for a running inference to finish, then frees the session. Later runs throw. */
	@Override
	public void close() {
		permits.acquireUninterruptibly();
		try {
			if (!closed) {
				closed = true;
				session.close();
			}
		} catch (OrtException e) {
			LOGGER.warn("Failed to close model '{}': {}", name, e.toString());
		} finally {
			permits.release();
		}
	}
}
