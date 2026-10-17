package io.github.elpideus.diffusicraft.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/diffusicraft.json}. Edited through the resource screen or by hand; documented in docs/CONFIG.md.
 * Fields are public for Gson; validate with {@link #sanitize()} after changing them.
 */
public final class DiffusicraftConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(DiffusicraftConfig.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Preferred inference device. AUTO picks the installed GPU runtime, else the CPU. */
	public OnnxModel.Device device = OnnxModel.Device.AUTO;
	/** GPU index for multi-GPU machines. */
	public int gpuIndex = 0;
	/**
	 * Cap on the inference memory arena in MiB. 0 picks a cap that leaves room for the game and shader packs;
	 * -1 removes the cap.
	 */
	public int gpuMemoryLimitMb = 0;
	/** CPU threads per inference run; 0 lets ONNX Runtime use every physical core. */
	public int cpuThreads = 0;
	/** Show the resource screen at launch while something recommended is missing. */
	public boolean showSetupScreen = true;
	/** Use CUDA/cuDNN libraries found on the system instead of downloading them. */
	public boolean useSystemLibraries = true;
	/** Where models and runtimes are stored. Empty: a per-user directory shared by every instance. */
	public String storagePath = "";
	/**
	 * Directory with a ROCm build of ONNX Runtime's Java natives. Empty: {@code <storage>/runtimes/<platform>/rocm}.
	 */
	public String rocmNativePath = "";

	public static DiffusicraftConfig load(Path file) {
		if (Files.isRegularFile(file)) {
			try {
				DiffusicraftConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DiffusicraftConfig.class);
				if (config != null) {
					return config.sanitize();
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("Ignoring unreadable {}: {}", file, e.toString());
			}
		}
		return new DiffusicraftConfig();
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			Downloader.moveAtomic(temp, file);
		} catch (IOException e) {
			LOGGER.warn("Could not save {}: {}", file, e.toString());
		}
	}

	public DiffusicraftConfig sanitize() {
		if (device == null) {
			device = OnnxModel.Device.AUTO;
		}
		gpuIndex = Math.max(0, gpuIndex);
		gpuMemoryLimitMb = Math.max(-1, gpuMemoryLimitMb);
		cpuThreads = Math.max(0, cpuThreads);
		storagePath = storagePath == null ? "" : storagePath.strip();
		rocmNativePath = rocmNativePath == null ? "" : rocmNativePath.strip();
		return this;
	}

	public DiffusicraftConfig copy() {
		return GSON.fromJson(GSON.toJson(this), DiffusicraftConfig.class);
	}
}
