package io.github.elpideus.diffusicraft.runtime;

import io.github.elpideus.diffusicraft.terrain.diffusion.ModelAssets;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Everything Diffusicraft downloads: the Terrain Diffusion models (required) and the GPU runtimes (optional).
 * Owns the configuration, computes status off the calling thread, runs downloads one at a time and notifies
 * listeners when a job ends. Used by the client screens, the dedicated server and the world generator.
 *
 * <p>Files live in a per-user directory shared by every game instance (models and runtimes are large and
 * immutable), unless {@code storagePath} in the configuration says otherwise.
 */
public final class Resources {
	private static final Logger LOGGER = LoggerFactory.getLogger(Resources.class);
	public static final String MODELS_JOB = "models";
	public static final String RUNTIME_JOB_PREFIX = "runtime:";
	public static final String CUDA = "cuda";
	public static final String ROCM = "rocm";
	/** Minimums of the CUDA 13 build pinned in runtime-manifest.json. */
	public static final int CUDA_MIN_COMPUTE_CAPABILITY = 75;
	public static final int CUDA_MIN_DRIVER = 580;
	/** Share of the GPU's memory the inference arena may use by default; the rest is left to the game and shaders. */
	private static final double AUTO_GPU_MEMORY_SHARE = 0.6;
	private static final long MIN_AUTO_GPU_MEMORY = 2L << 30;

	private static volatile Resources instance;

	/**
	 * @param modelsReady         every model file is present and verified
	 * @param modelsDownloadBytes bytes still to download for the models
	 * @param runtimes            status by runtime id, in manifest order
	 * @param selectedRuntime     the runtime the configuration selects now: a manifest id or {@link NativeRuntime#BUNDLED}
	 */
	public record Status(boolean modelsReady, long modelsDownloadBytes, Map<String, RuntimeInstaller.RuntimeStatus> runtimes,
		String selectedRuntime) {
		public Optional<RuntimeInstaller.RuntimeStatus> runtime(String id) {
			return Optional.ofNullable(runtimes.get(id));
		}
	}

	@FunctionalInterface
	private interface Work {
		void run(Job job) throws IOException;
	}

	private final Path configFile;
	private final Path storage;
	private final Platform platform = Platform.current();
	private final RuntimeManifest manifest = RuntimeManifest.builtin();
	private final ModelAssets models;
	private final RuntimeInstaller installer;
	private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(daemon("Diffusicraft Downloads"));
	private final ExecutorService checkExecutor = Executors.newSingleThreadExecutor(daemon("Diffusicraft Resource Check"));
	private final CompletableFuture<Hardware> hardware;
	private final List<Job> jobs = new CopyOnWriteArrayList<>();
	private final List<Consumer<Job>> listeners = new CopyOnWriteArrayList<>();
	private volatile DiffusicraftConfig config;
	private volatile Status status;
	private final Map<String, Job> finished = new ConcurrentHashMap<>();
	/** The NVIDIA driver library is found; checked once, since installing a driver needs a reboot anyway. */
	private volatile Boolean nvidiaDriver;
	private ScheduledExecutorService progressLogger;

	private Resources(Path gameDir, Path configDir) {
		this.configFile = configDir.resolve("diffusicraft.json");
		this.config = DiffusicraftConfig.load(configFile);
		if (!Files.isRegularFile(configFile)) {
			config.save(configFile);
		}
		this.storage = resolveStorage(config, gameDir);
		this.models = ModelAssets.forStorage(storage);
		Path runtimeRoot = storage.resolve("runtimes").resolve(platform.id());
		Path userProvided = config.rocmNativePath.isEmpty() ? runtimeRoot.resolve(ROCM) : gameDir.resolve(config.rocmNativePath);
		this.installer = new RuntimeInstaller(runtimeRoot, platform, config.useSystemLibraries ? SystemLibraries.scan(platform) : null, userProvided);
		this.hardware = CompletableFuture.supplyAsync(() -> Hardware.probe(platform), checkExecutor);
		hardware.thenAccept(h -> LOGGER.info("Hardware: {} CPU threads, {} MB RAM, GPUs: {}", h.cpuThreads(), h.memoryBytes() >> 20, h.gpus()));
		NativeRuntime.configure(this::chooseRuntime);
	}

	/** Creates the shared instance; called once from the mod initialiser. */
	public static synchronized Resources init(Path gameDir, Path configDir) {
		if (instance == null) {
			instance = new Resources(gameDir, configDir);
			LOGGER.info("Diffusicraft storage: {}", instance.storage);
			instance.refresh();
		}
		return instance;
	}

	public static Resources get() {
		Resources resources = instance;
		if (resources == null) {
			throw new IllegalStateException("Diffusicraft resources are not initialised yet");
		}
		return resources;
	}

	public DiffusicraftConfig config() {
		return config;
	}

	/** Applies {@code change} to a copy of the configuration, then saves it. */
	public synchronized void updateConfig(Consumer<DiffusicraftConfig> change) {
		DiffusicraftConfig copy = config.copy();
		change.accept(copy);
		config = copy.sanitize();
		config.save(configFile);
		refresh();
	}

	public Path configFile() {
		return configFile;
	}

	public Path storage() {
		return storage;
	}

	public Platform platform() {
		return platform;
	}

	public RuntimeManifest manifest() {
		return manifest;
	}

	public ModelAssets models() {
		return models;
	}

	public RuntimeInstaller installer() {
		return installer;
	}

	public CompletableFuture<Hardware> hardware() {
		return hardware;
	}

	/** Latest computed status, or null before the first check finishes. */
	public Status status() {
		return status;
	}

	/** Recomputes the status in the background. Checking the models may hash them once (a few seconds). */
	public CompletableFuture<Status> refresh() {
		return CompletableFuture.supplyAsync(this::computeStatus, checkExecutor).whenComplete((s, e) -> {
			if (s != null) {
				status = s;
			} else {
				LOGGER.warn("Resource check failed", e);
			}
		});
	}

	private Status computeStatus() {
		long missing;
		try {
			missing = models.invalidFiles().stream().mapToLong(ModelAssets.FileSpec::size).sum();
		} catch (IOException e) {
			LOGGER.warn("Cannot check the models: {}", e.toString());
			missing = models.manifest().totalBytes();
		}
		Map<String, RuntimeInstaller.RuntimeStatus> runtimes = new LinkedHashMap<>();
		for (RuntimeManifest.Runtime runtime : manifest.runtimes()) {
			runtimes.put(runtime.id(), installer.status(runtime));
		}
		return new Status(missing == 0, missing, Collections.unmodifiableMap(runtimes), chooseRuntime().runtimeId());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Jobs

	public Job downloadModels() {
		return submit(new Job(MODELS_JOB, "Terrain Diffusion models"), models::ensure);
	}

	public Job installRuntime(String runtimeId) {
		RuntimeManifest.Runtime runtime = manifest.runtime(runtimeId).orElseThrow(() -> new IllegalArgumentException(runtimeId));
		return submit(new Job(RUNTIME_JOB_PREFIX + runtimeId, runtime.name()), job -> installer.install(runtime, job));
	}

	/** Deletes a runtime's downloaded files after any running job. */
	public CompletableFuture<Void> removeRuntime(String runtimeId) {
		RuntimeManifest.Runtime runtime = manifest.runtime(runtimeId).orElseThrow(() -> new IllegalArgumentException(runtimeId));
		return CompletableFuture.runAsync(() -> {
			try {
				installer.remove(runtime);
			} catch (IOException e) {
				throw new IllegalStateException("Could not remove " + runtime.name() + ": " + e.getMessage(), e);
			}
		}, jobExecutor).whenComplete((v, e) -> refresh());
	}

	private Job submit(Job job, Work work) {
		synchronized (jobs) {
			for (Job existing : jobs) {
				if (existing.id().equals(job.id())) {
					return existing;
				}
			}
			jobs.add(job);
		}
		jobExecutor.execute(() -> run(job, work));
		return job;
	}

	private void run(Job job, Work work) {
		if (job.cancelRequested()) {
			finish(job, Job.State.CANCELLED, null);
			return;
		}
		job.start();
		LOGGER.info("Started: {}", job.title());
		try {
			Files.createDirectories(storage);
			// Instances share the storage directory; only one may download at a time.
			try (FileChannel channel = FileChannel.open(storage.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
				FileLock lock = channel.tryLock();
				if (lock == null) {
					job.detail("Waiting for another Minecraft instance");
					LOGGER.info("Another instance is downloading into {}; waiting", storage);
					lock = channel.lock();
				}
				try {
					work.run(job);
				} finally {
					lock.release();
				}
			}
			LOGGER.info("Finished: {}", job.title());
			finish(job, Job.State.DONE, null);
		} catch (Job.CancelledException e) {
			LOGGER.info("Cancelled: {}", job.title());
			finish(job, Job.State.CANCELLED, null);
		} catch (IOException | RuntimeException e) {
			if (job.cancelRequested()) {
				finish(job, Job.State.CANCELLED, null);
			} else {
				LOGGER.error("{} failed", job.title(), e);
				finish(job, Job.State.FAILED, e);
			}
		}
	}

	private void finish(Job job, Job.State state, Throwable error) {
		job.finish(state, error);
		jobs.remove(job);
		finished.put(job.id(), job);
		refresh().whenComplete((s, e) -> {
			for (Consumer<Job> listener : listeners) {
				try {
					listener.accept(job);
				} catch (RuntimeException failure) {
					LOGGER.warn("Job listener failed", failure);
				}
			}
		});
	}

	/** Running and queued jobs, oldest first. */
	public List<Job> jobs() {
		return List.copyOf(jobs);
	}

	public Optional<Job> job(String id) {
		return jobs.stream().filter(j -> j.id().equals(id)).findFirst();
	}

	/** The most recently finished job with this id, for showing its outcome. */
	public Optional<Job> lastFinished(String id) {
		return Optional.ofNullable(finished.get(id));
	}

	/** Called on a background thread after a job ends and the status was refreshed. */
	public void addListener(Consumer<Job> listener) {
		listeners.add(listener);
	}

	/** Logs the progress of running jobs every ten seconds (dedicated servers have no progress screen). */
	public synchronized void logProgress() {
		if (progressLogger != null) {
			return;
		}
		progressLogger = Executors.newSingleThreadScheduledExecutor(daemon("Diffusicraft Download Progress"));
		progressLogger.scheduleAtFixedRate(() -> {
			for (Job job : jobs) {
				if (job.state() == Job.State.RUNNING && job.total() > 0) {
					LOGGER.info("{}: {}% ({} of {} MB, {} MB/s) {}", job.title(), Math.round(job.fraction() * 100),
						job.done() >> 20, job.total() >> 20, String.format("%.1f", job.bytesPerSecond() / (1 << 20)), job.detail());
				}
			}
		}, 10, 10, TimeUnit.SECONDS);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Decisions

	/** The GPU runtime this machine should use, if any: CUDA when an NVIDIA GPU is present and supported. */
	public Optional<RuntimeManifest.Runtime> recommendedRuntime() {
		Hardware hw = hardware.getNow(null);
		if (hw == null || hw.gpus().stream().noneMatch(g -> cudaSupport(g) == CudaSupport.SUPPORTED)) {
			return Optional.empty();
		}
		return manifest.runtime(CUDA).filter(r -> r.supports(platform));
	}

	public enum CudaSupport {
		SUPPORTED,
		/** Not an NVIDIA GPU. */
		NOT_NVIDIA,
		/** Older than Turing (GeForce 16 / RTX 20 series); CUDA 13 dropped Maxwell, Pascal and Volta. */
		GPU_TOO_OLD,
		/** The driver predates CUDA 13. */
		DRIVER_TOO_OLD
	}

	/**
	 * Whether {@code gpu} can run the pinned CUDA runtime (CUDA 13: compute capability 7.5 and driver 580 or newer).
	 * Unknown values count as supported; ONNX Runtime falls back to the CPU if they were not.
	 */
	public static CudaSupport cudaSupport(Hardware.Gpu gpu) {
		if (gpu.vendor() != Hardware.Vendor.NVIDIA) {
			return CudaSupport.NOT_NVIDIA;
		}
		if (gpu.computeCapability() > 0 && gpu.computeCapability() < CUDA_MIN_COMPUTE_CAPABILITY) {
			return CudaSupport.GPU_TOO_OLD;
		}
		if (gpu.driverMajor() > 0 && gpu.driverMajor() < CUDA_MIN_DRIVER) {
			return CudaSupport.DRIVER_TOO_OLD;
		}
		return CudaSupport.SUPPORTED;
	}

	/**
	 * Whether the launch screen should offer downloads: the models are missing, or an NVIDIA GPU is present
	 * without its runtime (unless the user chose the CPU). Uses metadata checks only; waits up to two seconds for
	 * GPU detection.
	 */
	public boolean setupSuggestedAtLaunch() {
		DiffusicraftConfig c = config;
		if (!c.showSetupScreen) {
			return false;
		}
		if (!models.quickCheck()) {
			return true;
		}
		if (c.device == OnnxModel.Device.CPU) {
			return false;
		}
		try {
			hardware.get(2, TimeUnit.SECONDS);
		} catch (Exception e) {
			return false;
		}
		return recommendedRuntime().map(r -> installer.status(r).state() != RuntimeInstaller.RuntimeState.READY).orElse(false);
	}

	/** The runtime to load given the configuration and what is installed. Called by {@link NativeRuntime}. */
	public NativeRuntime.Choice chooseRuntime() {
		List<String> candidates = switch (config.device) {
			case CPU -> List.of();
			case CUDA -> List.of(CUDA);
			case ROCM -> List.of(ROCM);
			case AUTO -> List.of(CUDA, ROCM);
		};
		for (String id : candidates) {
			Optional<RuntimeManifest.Runtime> runtime = manifest.runtime(id);
			if (runtime.isEmpty()) {
				continue;
			}
			RuntimeInstaller.RuntimeStatus s = installer.status(runtime.get());
			if (s.state() != RuntimeInstaller.RuntimeState.READY) {
				continue;
			}
			if (id.equals(CUDA) && !nvidiaDriverPresent()) {
				LOGGER.warn("The CUDA runtime is installed but no NVIDIA driver was found");
				continue;
			}
			Hardware hw = hardware.getNow(null);
			if (id.equals(CUDA) && hw != null && hw.has(Hardware.Vendor.NVIDIA)
				&& hw.gpus().stream().noneMatch(g -> cudaSupport(g) == CudaSupport.SUPPORTED)) {
				LOGGER.warn("The CUDA runtime is installed but no GPU can run it: {}", hw.gpus());
				continue;
			}
			return new NativeRuntime.Choice(id, s.nativeDirectory(), s.preloadLibraries());
		}
		return NativeRuntime.Choice.BUNDLED_CPU;
	}

	private boolean nvidiaDriverPresent() {
		Boolean present = nvidiaDriver;
		if (present == null) {
			SystemLibraries system = SystemLibraries.scan(platform);
			nvidiaDriver = present = system.find(platform.isWindows() ? "nvcuda.dll" : "libcuda.so.1").isPresent();
		}
		return present;
	}

	/**
	 * Whether the configured device needs a runtime other than the one ONNX Runtime already loaded. Based on the
	 * last {@link #status()}, so it is cheap enough to call every frame.
	 */
	public boolean restartRequired() {
		NativeRuntime.Activation active = NativeRuntime.active();
		Status s = status;
		return active != null && s != null && !active.canServe(s.selectedRuntime());
	}

	/** Inference settings from the configuration and the detected hardware. */
	public OnnxModel.Settings modelSettings() {
		DiffusicraftConfig c = config;
		long limit = 0;
		if (c.gpuMemoryLimitMb > 0) {
			limit = (long) c.gpuMemoryLimitMb << 20;
		} else if (c.gpuMemoryLimitMb == 0) {
			limit = autoGpuMemoryLimit(c.gpuIndex);
		}
		return new OnnxModel.Settings(c.device, c.gpuIndex, c.cpuThreads, limit, Map.of(), c.halfPrecision,
			new Semaphore(1, true));
	}

	/**
	 * The cap used when {@code gpuMemoryLimitMb} is 0, or 0 (no cap) when the GPU's memory is unknown. Waits briefly for
	 * the hardware probe, so call it off the render thread unless {@link #hardware()} is done.
	 */
	public long autoGpuMemoryLimit(int gpuIndex) {
		Hardware hw;
		try {
			hw = hardware.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return 0;
		} catch (Exception e) {
			return 0;
		}
		if (hw == null || gpuIndex >= hw.gpus().size()) {
			return 0;
		}
		long vram = hw.gpus().get(gpuIndex).vramBytes();
		return vram <= 0 ? 0 : Math.max(MIN_AUTO_GPU_MEMORY, (long) (vram * AUTO_GPU_MEMORY_SHARE));
	}

	/** Dedicated server start: download what the configuration needs and log progress. */
	public void prepareServer() {
		logProgress();
		if (!models.quickCheck()) {
			downloadModels();
		}
		hardware.thenAccept(hw -> {
			RuntimeManifest.Runtime cuda = manifest.runtime(CUDA).filter(r -> r.supports(platform)).orElse(null);
			if (cuda == null || installer.status(cuda).state() == RuntimeInstaller.RuntimeState.READY) {
				return;
			}
			if (config.device == OnnxModel.Device.CUDA) {
				installRuntime(CUDA);
			} else if (config.device == OnnxModel.Device.AUTO && hw.has(Hardware.Vendor.NVIDIA)) {
				LOGGER.info("An NVIDIA GPU was found. Set \"device\": \"CUDA\" in {} to download the CUDA runtime ({} MB) and generate terrain on the GPU.",
					configFile, installer.status(cuda).downloadBytes(platform) >> 20);
			}
		});
	}

	// ---------------------------------------------------------------------------------------------------------
	// Storage

	static Path resolveStorage(DiffusicraftConfig config, Path gameDir) {
		if (!config.storagePath.isEmpty()) {
			Path path = Path.of(config.storagePath);
			return path.isAbsolute() ? path : gameDir.resolve(path);
		}
		Path shared = sharedDirectory(Platform.current());
		try {
			Files.createDirectories(shared);
			if (Files.isWritable(shared)) {
				return shared;
			}
		} catch (IOException | SecurityException e) {
			LOGGER.warn("Cannot use {} ({}); storing downloads in the game directory", shared, e.toString());
		}
		return gameDir.resolve("diffusicraft");
	}

	/** {@code %LOCALAPPDATA%\Diffusicraft}, {@code ~/Library/Application Support/Diffusicraft} or {@code $XDG_DATA_HOME/diffusicraft}. */
	static Path sharedDirectory(Platform platform) {
		String home = System.getProperty("user.home");
		if (platform.isWindows()) {
			String local = System.getenv("LOCALAPPDATA");
			return local != null && !local.isBlank() ? Path.of(local, "Diffusicraft") : Path.of(home, "AppData", "Local", "Diffusicraft");
		}
		if (platform.isMac()) {
			return Path.of(home, "Library", "Application Support", "Diffusicraft");
		}
		String xdg = System.getenv("XDG_DATA_HOME");
		return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share")).resolve("diffusicraft");
	}

	private static ThreadFactory daemon(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		};
	}
}
