package io.github.elpideus.diffusicraft.terrain.diffusion;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import io.github.elpideus.diffusicraft.runtime.Downloader;
import io.github.elpideus.diffusicraft.runtime.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates, verifies and downloads the pinned Terrain Diffusion files listed in
 * {@code /diffusicraft/model-manifest.json}. Files live in {@code <root>/<revision>/}; every file is checked
 * against the manifest's size and SHA-256. A verified file gets a {@code .verified} sidecar recording its size
 * and modification time, so the 2 GB base model is hashed once rather than at every launch.
 *
 * <p>Interrupted downloads resume from their {@code .part} file when the server supports range requests.
 * Files are shared by every game instance; see {@link io.github.elpideus.diffusicraft.runtime.Resources}.
 */
public final class ModelAssets {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModelAssets.class);
	private static final String MANIFEST_RESOURCE = "/diffusicraft/model-manifest.json";

	public record FileSpec(String name, long size, String sha256) {
	}

	public record Manifest(String repository, String revision, String license, String urlTemplate, List<FileSpec> files) {
		public static Manifest builtin() {
			try (InputStream in = ModelAssets.class.getResourceAsStream(MANIFEST_RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("Missing " + MANIFEST_RESOURCE);
				}
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					Json json = new Gson().fromJson(reader, Json.class);
					return new Manifest(json.repository, json.revision, json.license, json.urlTemplate, List.copyOf(json.files));
				}
			} catch (IOException e) {
				throw new IllegalStateException("Unreadable " + MANIFEST_RESOURCE, e);
			}
		}

		public URI uri(FileSpec file) {
			return URI.create(urlTemplate.replace("{repository}", repository).replace("{revision}", revision).replace("{file}", file.name()));
		}

		public long totalBytes() {
			return files.stream().mapToLong(FileSpec::size).sum();
		}

		private static final class Json {
			String repository;
			String revision;
			String license;
			@SerializedName("url_template") String urlTemplate;
			List<FileSpec> files;
		}
	}

	private final Manifest manifest;
	private final Path directory;

	/**
	 * @param manifest  pinned file list
	 * @param directory directory holding the files of this manifest's revision
	 */
	public ModelAssets(Manifest manifest, Path directory) {
		this.manifest = manifest;
		this.directory = directory;
	}

	/**
	 * Location {@code <storage>/models/<revision>}. The system property {@code diffusicraft.modelDir} overrides it
	 * with a directory that directly holds the files (used by tests).
	 */
	public static ModelAssets forStorage(Path storage) {
		Manifest manifest = Manifest.builtin();
		String override = System.getProperty("diffusicraft.modelDir");
		Path dir = override != null && !override.isBlank()
			? Path.of(override)
			: storage.resolve("models").resolve(manifest.revision());
		return new ModelAssets(manifest, dir);
	}

	public Manifest manifest() {
		return manifest;
	}

	public Path directory() {
		return directory;
	}

	public Path path(String fileName) {
		return directory.resolve(fileName);
	}

	/** Files that are absent or do not match the manifest. May hash files that have no valid sidecar. */
	public List<FileSpec> invalidFiles() throws IOException {
		List<FileSpec> invalid = new ArrayList<>();
		for (FileSpec file : manifest.files()) {
			if (!isValid(file)) {
				invalid.add(file);
			}
		}
		return invalid;
	}

	/**
	 * Whether every file is present with a matching {@code .verified} sidecar. Never hashes, so it is cheap enough
	 * for the render thread; a file without a sidecar counts as missing until {@link #invalidFiles} has checked it.
	 */
	public boolean quickCheck() {
		try {
			for (FileSpec file : manifest.files()) {
				Path path = path(file.name());
				Path sidecar = sidecar(path);
				if (!Files.isRegularFile(path) || Files.size(path) != file.size() || !Files.isRegularFile(sidecar)
					|| !Files.readString(sidecar, StandardCharsets.UTF_8).strip().equals(stamp(path, file))) {
					return false;
				}
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** Downloads every missing or corrupt file, resuming interrupted downloads. Only checks when all are present. */
	public synchronized void ensure(Job job) throws IOException {
		Files.createDirectories(directory);
		List<FileSpec> invalid = invalidFiles();
		if (invalid.isEmpty()) {
			return;
		}
		job.addTotal(invalid.stream().mapToLong(FileSpec::size).sum());
		LOGGER.info("Downloading {} Terrain Diffusion file(s), {} MB, from {} @ {}",
			invalid.size(), job.total() >> 20, manifest.repository(), manifest.revision());
		for (FileSpec file : invalid) {
			job.checkCancelled();
			job.detail(file.name());
			Path target = path(file.name());
			Downloader.download(manifest.uri(file), target, file.size(), file.sha256(), job);
			Files.writeString(sidecar(target), stamp(target, file), StandardCharsets.UTF_8);
			LOGGER.info("Downloaded {}", file.name());
		}
	}

	private boolean isValid(FileSpec file) throws IOException {
		Path path = path(file.name());
		if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
			return false;
		}
		Path sidecar = sidecar(path);
		String stamp = stamp(path, file);
		if (Files.isRegularFile(sidecar) && Files.readString(sidecar, StandardCharsets.UTF_8).strip().equals(stamp)) {
			return true;
		}
		String actual = Downloader.sha256(path);
		if (!actual.equals(file.sha256())) {
			LOGGER.warn("{} is corrupt (sha256 {}, expected {})", path, actual, file.sha256());
			return false;
		}
		Files.writeString(sidecar, stamp, StandardCharsets.UTF_8);
		return true;
	}

	private static Path sidecar(Path path) {
		return path.resolveSibling(path.getFileName() + ".verified");
	}

	private static String stamp(Path path, FileSpec file) throws IOException {
		return file.sha256() + " " + file.size() + " " + Files.getLastModifiedTime(path).toMillis();
	}
}
