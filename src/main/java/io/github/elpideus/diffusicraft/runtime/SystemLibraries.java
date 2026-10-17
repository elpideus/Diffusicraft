package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Finds native libraries installed on the system (CUDA toolkit, cuDNN, ROCm), so that their download can be
 * skipped. Linux consults the {@code ldconfig} cache and the usual toolkit directories; Windows the {@code PATH}
 * and the toolkit installers' directories.
 */
final class SystemLibraries {
	private static final Logger LOGGER = LoggerFactory.getLogger(SystemLibraries.class);

	private final Platform platform;
	private final Map<String, Path> ldCache;
	private final List<Path> directories;

	private SystemLibraries(Platform platform, Map<String, Path> ldCache, List<Path> directories) {
		this.platform = platform;
		this.ldCache = ldCache;
		this.directories = directories;
	}

	static SystemLibraries scan(Platform platform) {
		Set<Path> dirs = new LinkedHashSet<>();
		Map<String, Path> cache = Map.of();
		if (platform.isLinux()) {
			addPathList(dirs, System.getenv("LD_LIBRARY_PATH"));
			for (String env : new String[]{"CUDA_HOME", "CUDA_PATH"}) {
				String home = System.getenv(env);
				if (home != null && !home.isBlank()) {
					dirs.add(Path.of(home, "lib64"));
				}
			}
			for (String dir : new String[]{"/opt/cuda/lib64", "/usr/local/cuda/lib64", "/opt/rocm/lib",
				"/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib"}) {
				dirs.add(Path.of(dir));
			}
			cache = readLdCache(platform);
		} else if (platform.isWindows()) {
			String cudaPath = System.getenv("CUDA_PATH");
			if (cudaPath != null && !cudaPath.isBlank()) {
				dirs.add(Path.of(cudaPath, "bin", "x64"));
				dirs.add(Path.of(cudaPath, "bin"));
			}
			// cuDNN's Windows installer: C:\Program Files\NVIDIA\CUDNN\v9.x\bin\13.x\x64
			addCudnnWindowsDirs(dirs, Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "NVIDIA", "CUDNN"));
			addPathList(dirs, System.getenv("PATH"));
			// The NVIDIA driver's nvcuda.dll.
			dirs.add(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32"));
		}
		return new SystemLibraries(platform, cache, List.copyOf(dirs));
	}

	/** Absolute path of a library by file name (soname on Linux), if installed. */
	Optional<Path> find(String fileName) {
		Path cached = ldCache.get(fileName);
		if (cached != null && Files.isRegularFile(cached)) {
			return Optional.of(cached);
		}
		for (Path dir : directories) {
			Path candidate = dir.resolve(fileName);
			if (Files.isRegularFile(candidate)) {
				return Optional.of(candidate.toAbsolutePath());
			}
		}
		return Optional.empty();
	}

	Platform platform() {
		return platform;
	}

	private static void addPathList(Set<Path> dirs, String list) {
		if (list == null) {
			return;
		}
		for (String entry : list.split(File.pathSeparator)) {
			if (!entry.isBlank()) {
				try {
					dirs.add(Path.of(entry.strip()));
				} catch (RuntimeException ignored) {
					// Malformed PATH entry.
				}
			}
		}
	}

	private static void addCudnnWindowsDirs(Set<Path> dirs, Path root) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (DirectoryStream<Path> versions = Files.newDirectoryStream(root, "v9*")) {
			for (Path version : versions) {
				Path bin = version.resolve("bin");
				if (!Files.isDirectory(bin)) {
					continue;
				}
				try (DirectoryStream<Path> cudaVersions = Files.newDirectoryStream(bin, "13*")) {
					for (Path cuda : cudaVersions) {
						dirs.add(cuda.resolve("x64"));
						dirs.add(cuda);
					}
				}
				dirs.add(bin);
			}
		} catch (IOException e) {
			LOGGER.debug("Could not list {}: {}", root, e.toString());
		}
	}

	/** Parses {@code ldconfig -p}: lines like {@code libcudart.so.13 (libc6,x86-64) => /opt/cuda/lib64/libcudart.so.13}. */
	private static Map<String, Path> readLdCache(Platform platform) {
		String abi = platform == Platform.LINUX_X64 ? "x86-64" : "AArch64";
		for (String ldconfig : new String[]{"/sbin/ldconfig", "/usr/sbin/ldconfig", "/usr/bin/ldconfig", "ldconfig"}) {
			if (ldconfig.startsWith("/") && !Files.isExecutable(Path.of(ldconfig))) {
				continue;
			}
			try {
				Process process = new ProcessBuilder(ldconfig, "-p").redirectErrorStream(true).start();
				Map<String, Path> result = new HashMap<>();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						int arrow = line.indexOf(" => ");
						int paren = line.indexOf(" (");
						if (arrow < 0 || paren < 0 || paren > arrow || !line.substring(paren, arrow).contains(abi)) {
							continue;
						}
						result.putIfAbsent(line.substring(0, paren).strip(), Path.of(line.substring(arrow + 4).strip()));
					}
				}
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				return result;
			} catch (IOException e) {
				LOGGER.debug("{} -p failed: {}", ldconfig, e.toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return Map.of();
	}

	/** Every file of {@code names} that can be found, in order; empty when any of {@code required} is missing. */
	List<Path> resolveAll(List<String> required, List<String> names) {
		for (String name : required) {
			if (find(name).isEmpty()) {
				return List.of();
			}
		}
		List<Path> paths = new ArrayList<>();
		for (String name : names) {
			find(name).ifPresent(paths::add);
		}
		return paths;
	}
}
