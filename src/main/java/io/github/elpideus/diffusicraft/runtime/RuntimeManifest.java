package io.github.elpideus.diffusicraft.runtime;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pinned GPU runtimes, read from {@code /diffusicraft/runtime-manifest.json}. A runtime (e.g. NVIDIA CUDA) is a
 * list of components; each component is a set of native libraries per platform, taken from one or more archives.
 * Every file is pinned by size and SHA-256, and listed in the order it must be loaded. See docs/RUNTIMES.md.
 */
public record RuntimeManifest(int schema, List<Runtime> runtimes) {
	private static final String RESOURCE = "/diffusicraft/runtime-manifest.json";

	public static RuntimeManifest builtin() {
		try (InputStream in = RuntimeManifest.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing " + RESOURCE);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return new Gson().fromJson(reader, RuntimeManifest.class);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unreadable " + RESOURCE, e);
		}
	}

	public Optional<Runtime> runtime(String id) {
		return runtimes.stream().filter(r -> r.id().equals(id)).findFirst();
	}

	/**
	 * @param device the {@link OnnxModel.Device} this runtime provides
	 */
	public record Runtime(String id, String name, String vendor, OnnxModel.Device device, String description, List<Component> components) {
		/** Whether every component has files for {@code platform}. A runtime without components is user-provided. */
		public boolean supports(Platform platform) {
			return !components.isEmpty() && components.stream().allMatch(c -> c.supports(platform));
		}

		public long downloadBytes(Platform platform) {
			return components.stream().mapToLong(c -> c.downloadBytes(platform)).sum();
		}

		public long installedBytes(Platform platform) {
			return components.stream().mapToLong(c -> c.installedBytes(platform)).sum();
		}

		public Optional<Component> onnxRuntime() {
			return components.stream().filter(c -> c.role() == Role.ONNXRUNTIME).findFirst();
		}
	}

	public enum Role {
		/** ONNX Runtime's own libraries; their directory becomes {@code onnxruntime.native.path}. */
		@SerializedName("onnxruntime") ONNXRUNTIME,
		/** Libraries the execution provider links against; loaded before ONNX Runtime. */
		@SerializedName("libraries") LIBRARIES
	}

	/**
	 * @param systemLibraries per platform, the files whose presence on the system (driver/toolkit install) makes
	 *                        downloading this component unnecessary
	 * @param archives        per platform, where the files come from
	 */
	public record Component(String id, String name, Role role, String license,
							@SerializedName("license_url") URI licenseUrl,
							@SerializedName("system_libraries") Map<String, List<String>> systemLibraries,
							Map<String, List<Archive>> archives) {
		public boolean supports(Platform platform) {
			return !archives(platform).isEmpty();
		}

		public List<Archive> archives(Platform platform) {
			return archives == null ? List.of() : archives.getOrDefault(platform.id(), List.of());
		}

		public List<String> systemLibraries(Platform platform) {
			return systemLibraries == null ? List.of() : systemLibraries.getOrDefault(platform.id(), List.of());
		}

		/** Every file in load order. */
		public List<FileSpec> files(Platform platform) {
			return archives(platform).stream().flatMap(a -> a.files().stream()).toList();
		}

		/** Bytes transferred when the files are extracted remotely (compressed entry sizes). */
		public long downloadBytes(Platform platform) {
			return files(platform).stream().mapToLong(FileSpec::packed).sum();
		}

		public long installedBytes(Platform platform) {
			return files(platform).stream().mapToLong(FileSpec::size).sum();
		}
	}

	/** A ZIP archive (wheel or jar) that holds some of a component's files. */
	public record Archive(URI url, long size, String sha256, List<FileSpec> files) {
	}

	/**
	 * @param entry  path inside the archive
	 * @param name   installed file name
	 * @param packed compressed size inside the archive
	 */
	public record FileSpec(String entry, String name, long size, long packed, String sha256) {
	}
}
