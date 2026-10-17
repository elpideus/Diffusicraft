package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Chooses which ONNX Runtime native build the JVM loads: the CPU build bundled in the mod jar, or an installed GPU
 * runtime. Must run before any ONNX Runtime class initialises; {@code OnnxModel} calls {@link #activate()} first.
 *
 * <p>For a GPU runtime, the vendor libraries (CUDA runtime, cuBLAS, cuDNN, ...) are loaded by absolute path, in
 * dependency order. ONNX Runtime's execution provider then finds them without any {@code LD_LIBRARY_PATH} or
 * {@code PATH} changes, because the dynamic linker reuses a loaded library with the same soname (Linux) or module
 * name (Windows). Finally {@code onnxruntime.native.path} is pointed at the runtime's ONNX Runtime directory.
 *
 * <p>ONNX Runtime loads its native library once per JVM, so switching to another runtime needs a restart.
 */
public final class NativeRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(NativeRuntime.class);
	/** The CPU build inside the mod jar. */
	public static final String BUNDLED = "bundled";
	/** {@code onnxruntime.native.path} was set by the user (JVM argument). */
	public static final String EXTERNAL = "external";
	private static final String NATIVE_PATH_PROPERTY = "onnxruntime.native.path";

	/** A runtime to activate. {@code nativeDirectory} and {@code libraries} are unused for {@link #BUNDLED}. */
	public record Choice(String runtimeId, Path nativeDirectory, List<Path> libraries) {
		public static final Choice BUNDLED_CPU = new Choice(BUNDLED, null, List.of());
	}

	/**
	 * @param runtimeId which runtime is loaded: {@link #BUNDLED}, {@link #EXTERNAL} or a manifest runtime id
	 * @param error     why the chosen runtime could not be loaded (then the bundled build is used), or null
	 */
	public record Activation(String runtimeId, Path nativeDirectory, List<Path> loadedLibraries, String error) {
		/** GPU builds of ONNX Runtime also contain the CPU provider. */
		public boolean canServe(String runtimeId) {
			return this.runtimeId.equals(EXTERNAL) || this.runtimeId.equals(runtimeId) || runtimeId.equals(BUNDLED);
		}
	}

	private static volatile Supplier<Choice> chooser = () -> Choice.BUNDLED_CPU;
	private static volatile Activation active;

	private NativeRuntime() {
	}

	/** Sets how the runtime is chosen at activation; {@link Resources} installs one based on the configuration. */
	public static void configure(Supplier<Choice> chooser) {
		NativeRuntime.chooser = chooser;
	}

	/** The loaded runtime, or null while ONNX Runtime has not been used yet. */
	public static Activation active() {
		return active;
	}

	/** Loads the chosen runtime's libraries and selects its ONNX Runtime build. Idempotent. */
	public static synchronized Activation activate() {
		if (active != null) {
			return active;
		}
		String external = System.getProperty(NATIVE_PATH_PROPERTY);
		if (external != null && !external.isBlank()) {
			LOGGER.info("Using ONNX Runtime natives from {} (set by -D{})", external, NATIVE_PATH_PROPERTY);
			return active = new Activation(EXTERNAL, Path.of(external), List.of(), null);
		}
		Choice choice;
		try {
			choice = chooser.get();
		} catch (RuntimeException e) {
			LOGGER.error("Could not choose an inference runtime; using the CPU", e);
			return active = new Activation(BUNDLED, null, List.of(), e.toString());
		}
		if (choice.runtimeId().equals(BUNDLED)) {
			LOGGER.info("Using the bundled CPU build of ONNX Runtime");
			return active = new Activation(BUNDLED, null, List.of(), null);
		}
		List<Path> loaded = new ArrayList<>();
		long start = System.nanoTime();
		try {
			for (Path library : choice.libraries()) {
				System.load(library.toAbsolutePath().toString());
				loaded.add(library);
			}
		} catch (UnsatisfiedLinkError | SecurityException e) {
			LOGGER.error("Could not load the {} runtime ({}); using the CPU build instead", choice.runtimeId(), e.getMessage());
			return active = new Activation(BUNDLED, null, loaded, e.getMessage());
		}
		System.setProperty(NATIVE_PATH_PROPERTY, choice.nativeDirectory().toAbsolutePath().toString());
		LOGGER.info("Using the {} runtime from {} ({} libraries loaded in {} ms)", choice.runtimeId(), choice.nativeDirectory(),
			loaded.size(), (System.nanoTime() - start) / 1_000_000);
		return active = new Activation(choice.runtimeId(), choice.nativeDirectory(), loaded, null);
	}
}
