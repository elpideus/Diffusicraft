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
	/** Furthest a server sends far terrain, in blocks. */
	private static final int MAX_FAR_TERRAIN_RADIUS = 65536;
	/** Nearest and furthest the client draws its own far terrain, in blocks. */
	private static final int MIN_FAR_TERRAIN_DISTANCE = 512;
	private static final int MAX_FAR_TERRAIN_DISTANCE = 65536;

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
	/**
	 * Run the decoder in half precision on NVIDIA GPUs: faster, and the terrain moves by a few centimetres. Read when
	 * the models load.
	 */
	public boolean halfPrecision = true;
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
	/**
	 * Draw Distant Horizons' far terrain in Diffusicraft worlds straight from the terrain, without generating chunks.
	 * Off: Distant Horizons generates chunks for its LODs, or uses another mod's generator. Needs a game restart.
	 */
	public boolean distantHorizonsLods = true;
	/**
	 * Draw Voxy's far terrain in Diffusicraft worlds straight from the terrain where Voxy has not seen the chunks: in
	 * singleplayer from the world's own generator, on a server from the far terrain it sends. Off: Voxy shows only the
	 * chunks it has loaded. Read when Voxy opens a world.
	 */
	public boolean voxyLods = true;
	/**
	 * On a server, send the far terrain of Diffusicraft worlds to the clients that ask for it, for Voxy to draw. Read
	 * when the server starts.
	 */
	public boolean serveFarTerrain = true;
	/** How far from a player, in blocks, a server sends far terrain. Read when the server starts. */
	public int farTerrainRadius = 8192;
	/**
	 * Draw Diffusicraft's own far terrain past the chunks of Diffusicraft worlds, in singleplayer from the world's
	 * generator and on a server from the far terrain it sends. Off while Distant Horizons or Voxy is installed, which
	 * draw theirs. Takes effect at once. See docs/LOD.md.
	 */
	public boolean farTerrain = true;
	/** How far the client draws its own far terrain, in blocks. Read when a world opens. */
	public int farTerrainDistance = 8192;
	/**
	 * Section rows above and below the camera that the client keeps render data for. 0 uses the render distance, which
	 * is as far as vanilla draws vertically; smaller values save memory in tall dimensions. Read at startup.
	 */
	public int verticalRenderDistance = 0;
	/**
	 * Compute terrain in the background where the players are heading, so that chunks rarely wait for inference.
	 * Read when a world opens.
	 */
	public boolean generateAhead = true;
	/** Offer the relief map: {@code /diffusicraft map} and the pause menu's button. See docs/MAP.md. */
	public boolean reliefMap = true;
	/**
	 * Address the relief map's web server listens on. The default lets only this machine open it; {@code 0.0.0.0}
	 * lets every machine that can reach it, with a link. Read when the map starts.
	 */
	public String reliefMapAddress = "127.0.0.1";
	/** Port of the relief map's web server; 0 picks a free one each time. Read when the map starts. */
	public int reliefMapPort = 0;
	/**
	 * Address the links point to, such as {@code https://map.example.net} behind a reverse proxy. Empty: the address
	 * and port the map listens on.
	 */
	public String reliefMapUrl = "";

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
		verticalRenderDistance = Math.max(0, verticalRenderDistance);
		farTerrainRadius = Math.clamp(farTerrainRadius, 0, MAX_FAR_TERRAIN_RADIUS);
		farTerrainDistance = Math.clamp(farTerrainDistance, MIN_FAR_TERRAIN_DISTANCE, MAX_FAR_TERRAIN_DISTANCE);
		storagePath = storagePath == null ? "" : storagePath.strip();
		rocmNativePath = rocmNativePath == null ? "" : rocmNativePath.strip();
		reliefMapAddress = reliefMapAddress == null ? "" : reliefMapAddress.strip();
		reliefMapPort = Math.clamp(reliefMapPort, 0, 65535);
		reliefMapUrl = reliefMapUrl == null ? "" : reliefMapUrl.strip();
		return this;
	}

	public DiffusicraftConfig copy() {
		return GSON.fromJson(GSON.toJson(this), DiffusicraftConfig.class);
	}
}
