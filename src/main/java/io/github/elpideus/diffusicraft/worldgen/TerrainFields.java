package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.runtime.Job;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link TerrainField} of every seed in use, and the models they share.
 *
 * <p>Fields are created when the density functions compile (once per world and dimension) but load nothing until
 * the first chunk needs terrain. The models are loaded once and freed when the server stops, which gives the GPU
 * memory back to the game. Pipeline windows are cached on disk in {@code <world>/diffusicraft/<model revision>/<seed>}
 * so a world reloads quickly and stays identical even if the model files change.
 */
public final class TerrainFields {
	/** The seed of a world's terrain is derived from the world seed and this name. */
	public static final Identifier SEED_ID = Diffusicraft.id("terrain");

	private static final Map<Long, TerrainField> FIELDS = new ConcurrentHashMap<>();
	private static volatile Path storageRoot;
	private static ModelBundle models;

	private TerrainFields() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server ->
			storageRoot = server.getWorldPath(LevelResource.ROOT).resolve(Diffusicraft.MOD_ID));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> closeAll());
	}

	/** The field for the world that is compiling its density functions. */
	public static TerrainField forContext(DensityFunction.CompileContext context) {
		return get(context.createRandom(SEED_ID).nextLong());
	}

	/** The field for the world of a random state, for code that runs after the density functions are compiled. */
	public static TerrainField forState(RandomState state) {
		return get(((RandomStateAccessor) (Object) state).diffusicraft$random().fromHashOf(SEED_ID).nextLong());
	}

	public static TerrainField get(long seed) {
		return FIELDS.computeIfAbsent(seed, s -> new TerrainField(s, new PipelineSource(s)));
	}

	/** Where pipeline windows are cached, or {@code null} outside a server (nothing is written to disk then). */
	static Path storageRoot() {
		return storageRoot;
	}

	/**
	 * The shared models, loaded on first use. If they are still downloading, waits for the download; if they are
	 * missing, fails with a message that says how to get them.
	 */
	static synchronized ModelBundle models() {
		if (models != null) {
			return models;
		}
		Resources resources = Resources.get();
		if (!resources.models().quickCheck()) {
			Optional<Job> download = resources.job(Resources.MODELS_JOB);
			if (download.isPresent()) {
				Diffusicraft.LOGGER.info("Waiting for the Terrain Diffusion models to finish downloading");
				download.get().completion().join();
			}
			if (!resources.models().quickCheck()) {
				throw new IllegalStateException("The Terrain Diffusion models are not downloaded. Open Diffusicraft's "
					+ "resource screen (Create World > More, or Mod Menu) to download them; a dedicated server "
					+ "downloads them at startup.");
			}
		}
		try {
			long start = System.nanoTime();
			models = ModelBundle.load(resources.models(), resources.modelSettings());
			Diffusicraft.LOGGER.info("Loaded the Terrain Diffusion models in {} ms",
				(System.nanoTime() - start) / 1_000_000);
			return models;
		} catch (Exception e) {
			throw new IllegalStateException("Could not load the Terrain Diffusion models: " + e.getMessage(), e);
		}
	}

	/** Frees every field and the models. Fields requested later start fresh. */
	public static synchronized void closeAll() {
		for (TerrainField field : FIELDS.values()) {
			field.close();
		}
		FIELDS.clear();
		if (models != null) {
			models.close();
			models = null;
		}
		storageRoot = null;
	}
}
