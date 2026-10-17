package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs, checks and removes runtime components under {@code <root>/<component id>/}. A component is trusted
 * once its {@code .installed} marker matches: the marker lists every file's SHA-256, size and modification time,
 * written after the files were verified, so a status check only compares file metadata.
 */
public final class RuntimeInstaller {
	private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeInstaller.class);
	private static final String MARKER = ".installed";
	/** ONNX Runtime's Java natives that a user-provided (ROCm) directory must hold, plus one of the providers. */
	private static final List<String> USER_PROVIDED_REQUIRED = List.of("libonnxruntime.so", "libonnxruntime4j_jni.so", "libonnxruntime_providers_shared.so");
	private static final List<String> USER_PROVIDED_PROVIDERS = List.of("libonnxruntime_providers_rocm.so", "libonnxruntime_providers_migraphx.so");

	public enum ComponentState {
		/** Downloaded and verified. */
		INSTALLED,
		/** Not downloaded, but the system provides the libraries. */
		SYSTEM,
		MISSING,
		/** No files for this platform. */
		UNSUPPORTED
	}

	/** @param libraries the component's files to load, in order, wherever they are; empty when missing */
	public record ComponentStatus(RuntimeManifest.Component component, ComponentState state, List<Path> libraries) {
	}

	public enum RuntimeState {
		READY, MISSING, UNSUPPORTED
	}

	/** @param nativeDirectory the directory to use as {@code onnxruntime.native.path} once ready */
	public record RuntimeStatus(RuntimeManifest.Runtime runtime, RuntimeState state, List<ComponentStatus> components, Path nativeDirectory) {
		public long downloadBytes(Platform platform) {
			return components.stream().filter(c -> c.state() == ComponentState.MISSING).mapToLong(c -> c.component().downloadBytes(platform)).sum();
		}

		public boolean usesSystemLibraries() {
			return components.stream().anyMatch(c -> c.state() == ComponentState.SYSTEM);
		}

		public boolean anyInstalled() {
			return components.stream().anyMatch(c -> c.state() == ComponentState.INSTALLED);
		}

		/** Vendor libraries to load before ONNX Runtime, in order. */
		public List<Path> preloadLibraries() {
			return components.stream()
				.filter(c -> c.component().role() == RuntimeManifest.Role.LIBRARIES)
				.flatMap(c -> c.libraries().stream())
				.toList();
		}
	}

	private final Path root;
	private final Platform platform;
	private final SystemLibraries system;
	private final Path userProvidedDirectory;

	/**
	 * @param root                  {@code <storage>/runtimes/<platform>}
	 * @param system                system library lookup, or null to never use system libraries
	 * @param userProvidedDirectory where a runtime without components (ROCm) expects its natives
	 */
	RuntimeInstaller(Path root, Platform platform, SystemLibraries system, Path userProvidedDirectory) {
		this.root = root;
		this.platform = platform;
		this.system = system;
		this.userProvidedDirectory = userProvidedDirectory;
	}

	public Path root() {
		return root;
	}

	public Path userProvidedDirectory() {
		return userProvidedDirectory;
	}

	public Path directory(RuntimeManifest.Component component) {
		return root.resolve(component.id());
	}

	public RuntimeStatus status(RuntimeManifest.Runtime runtime) {
		if (runtime.components().isEmpty()) {
			return userProvidedStatus(runtime);
		}
		if (!runtime.supports(platform)) {
			return new RuntimeStatus(runtime, RuntimeState.UNSUPPORTED, List.of(), null);
		}
		List<ComponentStatus> components = runtime.components().stream().map(this::status).toList();
		boolean ready = components.stream().allMatch(c -> c.state() == ComponentState.INSTALLED || c.state() == ComponentState.SYSTEM);
		Path nativeDirectory = runtime.onnxRuntime().map(this::directory).orElse(null);
		return new RuntimeStatus(runtime, ready ? RuntimeState.READY : RuntimeState.MISSING, components, nativeDirectory);
	}

	private ComponentStatus status(RuntimeManifest.Component component) {
		if (!component.supports(platform)) {
			return new ComponentStatus(component, ComponentState.UNSUPPORTED, List.of());
		}
		Path dir = directory(component);
		if (isInstalled(component)) {
			return new ComponentStatus(component, ComponentState.INSTALLED,
				component.files(platform).stream().map(f -> dir.resolve(f.name())).toList());
		}
		List<String> required = component.systemLibraries(platform);
		if (system != null && component.role() == RuntimeManifest.Role.LIBRARIES && !required.isEmpty()) {
			List<Path> found = system.resolveAll(required, component.files(platform).stream().map(RuntimeManifest.FileSpec::name).toList());
			if (!found.isEmpty()) {
				return new ComponentStatus(component, ComponentState.SYSTEM, found);
			}
		}
		return new ComponentStatus(component, ComponentState.MISSING, List.of());
	}

	private RuntimeStatus userProvidedStatus(RuntimeManifest.Runtime runtime) {
		if (!platform.isLinux()) {
			return new RuntimeStatus(runtime, RuntimeState.UNSUPPORTED, List.of(), null);
		}
		boolean ready = USER_PROVIDED_REQUIRED.stream().allMatch(f -> Files.isRegularFile(userProvidedDirectory.resolve(f)))
			&& USER_PROVIDED_PROVIDERS.stream().anyMatch(f -> Files.isRegularFile(userProvidedDirectory.resolve(f)));
		return new RuntimeStatus(runtime, ready ? RuntimeState.READY : RuntimeState.MISSING, List.of(), userProvidedDirectory);
	}

	private boolean isInstalled(RuntimeManifest.Component component) {
		Path marker = directory(component).resolve(MARKER);
		try {
			return Files.isRegularFile(marker) && Files.readString(marker, StandardCharsets.UTF_8).equals(stamp(component));
		} catch (NoSuchFileException e) {
			return false;
		} catch (IOException e) {
			LOGGER.debug("Cannot check {}: {}", marker, e.toString());
			return false;
		}
	}

	private String stamp(RuntimeManifest.Component component) throws IOException {
		Path dir = directory(component);
		StringBuilder text = new StringBuilder();
		for (RuntimeManifest.FileSpec file : component.files(platform)) {
			Path path = dir.resolve(file.name());
			if (Files.size(path) != file.size()) {
				return "";
			}
			text.append(file.sha256()).append(' ').append(file.size()).append(' ')
				.append(Files.getLastModifiedTime(path).toMillis()).append(' ').append(file.name()).append('\n');
		}
		return text.toString();
	}

	/** Downloads every missing component of {@code runtime}. Components the system provides are skipped. */
	public void install(RuntimeManifest.Runtime runtime, Job job) throws IOException {
		if (runtime.components().isEmpty()) {
			throw new IOException(runtime.name() + " cannot be downloaded; see docs/RUNTIMES.md");
		}
		if (!runtime.supports(platform)) {
			throw new IOException(runtime.name() + " is not available for " + platform.id());
		}
		List<RuntimeManifest.Component> missing = status(runtime).components().stream()
			.filter(c -> c.state() == ComponentState.MISSING)
			.map(ComponentStatus::component)
			.toList();
		job.addTotal(missing.stream().mapToLong(c -> c.downloadBytes(platform)).sum());
		for (RuntimeManifest.Component component : missing) {
			installComponent(component, job);
		}
	}

	private void installComponent(RuntimeManifest.Component component, Job job) throws IOException {
		Path dir = directory(component);
		Files.createDirectories(dir);
		Files.deleteIfExists(dir.resolve(MARKER));
		LOGGER.info("Installing {} into {}", component.name(), dir);
		for (RuntimeManifest.Archive archive : component.archives(platform)) {
			List<RuntimeManifest.FileSpec> todo = new ArrayList<>();
			for (RuntimeManifest.FileSpec file : archive.files()) {
				job.checkCancelled();
				Path target = dir.resolve(file.name());
				if (Files.isRegularFile(target) && Files.size(target) == file.size()) {
					job.detail(file.name());
					if (Downloader.sha256(target).equalsIgnoreCase(file.sha256())) {
						job.progress(file.packed());
						continue;
					}
				}
				todo.add(file);
			}
			if (todo.isEmpty()) {
				continue;
			}
			RemoteZip zip = new RemoteZip(archive.url(), archive.size());
			try {
				for (RuntimeManifest.FileSpec file : todo) {
					job.checkCancelled();
					job.detail(file.name());
					RemoteZip.Entry entry = zip.entries(job).get(file.entry());
					if (entry == null) {
						throw new IOException(file.entry() + " not found in " + archive.url());
					}
					zip.extract(entry, dir.resolve(file.name()), file.size(), file.sha256(), job);
				}
			} catch (Downloader.RangeNotSupportedException e) {
				LOGGER.info("{}; downloading the whole archive instead", e.getMessage());
				extractFromFullArchive(archive, todo, dir, job);
			}
		}
		deleteLeftovers(dir);
		Files.writeString(dir.resolve(MARKER), stamp(component), StandardCharsets.UTF_8);
		LOGGER.info("Installed {}", component.name());
	}

	/** Fallback for servers without range support: download the archive, extract, delete it. */
	private void extractFromFullArchive(RuntimeManifest.Archive archive, List<RuntimeManifest.FileSpec> files, Path dir, Job job) throws IOException {
		List<RuntimeManifest.FileSpec> remaining = files.stream().filter(f -> !Files.isRegularFile(dir.resolve(f.name()))).toList();
		job.addTotal(archive.size() - remaining.stream().mapToLong(RuntimeManifest.FileSpec::packed).sum());
		Path zipFile = dir.resolve(".archive-" + archive.sha256().substring(0, 16) + ".zip");
		String url = archive.url().toString();
		job.detail(url.substring(url.lastIndexOf('/') + 1));
		Downloader.download(archive.url(), zipFile, archive.size(), archive.sha256(), job);
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			for (RuntimeManifest.FileSpec file : remaining) {
				job.checkCancelled();
				job.detail(file.name());
				ZipEntry entry = zip.getEntry(file.entry());
				if (entry == null) {
					throw new IOException(file.entry() + " not found in " + archive.url());
				}
				Path part = dir.resolve(file.name() + ".part");
				try (InputStream in = zip.getInputStream(entry)) {
					Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
				}
				if (Files.size(part) != file.size() || !Downloader.sha256(part).equalsIgnoreCase(file.sha256())) {
					Files.delete(part);
					throw new IOException("Verification failed for " + file.name() + " from " + archive.url());
				}
				Downloader.moveAtomic(part, dir.resolve(file.name()));
			}
		} finally {
			Files.deleteIfExists(zipFile);
		}
	}

	private static void deleteLeftovers(Path dir) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.part")) {
			for (Path leftover : stream) {
				Files.deleteIfExists(leftover);
			}
		}
	}

	/**
	 * Deletes the downloaded components of {@code runtime}. Fails on Windows for libraries that are loaded;
	 * those can be removed after a restart.
	 */
	public void remove(RuntimeManifest.Runtime runtime) throws IOException {
		for (RuntimeManifest.Component component : runtime.components()) {
			Path dir = directory(component);
			if (Files.exists(dir)) {
				LOGGER.info("Removing {}", dir);
				deleteRecursively(dir);
			}
		}
	}

	static void deleteRecursively(Path dir) throws IOException {
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(dir)) {
			paths = walk.sorted(Comparator.reverseOrder()).toList();
		}
		IOException failure = null;
		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				failure = failure == null ? e : failure;
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
