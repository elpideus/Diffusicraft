package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.runtime.Job;
import io.github.elpideus.diffusicraft.runtime.NativeHeap;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
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
 * the first chunk needs terrain. Each reads the pipeline through {@link Uplift}, which raises the mountains. The models
 * are loaded once and freed when the server stops, which gives the GPU memory back to the game and, through
 * {@link NativeHeap}, the system the memory their loading took, and stay refused until the next server starts: the far
 * terrain and the relief map finish their work after a server stops, and would load them all over again, for a world
 * that is gone and sometimes while the game quits. Pipeline windows are cached on disk in
 * {@code <world>/diffusicraft/<model revision>/map<map version>/<seed>} so a world reloads quickly and stays identical
 * even if the model files change.
 */
public final class TerrainFields {
	/** The seed of a world's terrain is derived from the world seed and this name. */
	public static final Identifier SEED_ID = Diffusicraft.id("terrain");
	/** The seed of a world's rocks is derived from the world seed and this name. */
	public static final Identifier GEOLOGY_SEED_ID = Diffusicraft.id("geology");

	private static final Map<Long, TerrainField> FIELDS = new ConcurrentHashMap<>();
	private static volatile Path storageRoot;
	private static ModelBundle models;
	/** Whether a server has stopped and no other has started. Guarded by the class. */
	private static boolean stopped;

	private TerrainFields() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(TerrainFields::starting);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopped());
		PlayerPrefetch.register();
	}

	private static synchronized void starting(MinecraftServer server) {
		// Work left over from the last world can have asked for its field since it closed.
		closeAll();
		stopped = false;
		storageRoot = server.getWorldPath(LevelResource.ROOT).resolve(Diffusicraft.MOD_ID);
	}

	private static synchronized void stopped() {
		stopped = true;
		closeAll();
	}

	/** The field for the world that is compiling its density functions. */
	public static TerrainField forContext(DensityFunction.CompileContext context) {
		return get(context.createRandom(SEED_ID).nextLong(), context.createRandom(GEOLOGY_SEED_ID).nextLong());
	}

	/** The field for the world of a random state, for code that runs after the density functions are compiled. */
	public static TerrainField forState(RandomState state) {
		return forRandom(((RandomStateAccessor) (Object) state).diffusicraft$random());
	}

	/** The field for the world whose random state has this positional random factory. */
	public static TerrainField forRandom(PositionalRandomFactory random) {
		return get(random.fromHashOf(SEED_ID).nextLong(), random.fromHashOf(GEOLOGY_SEED_ID).nextLong());
	}

	/**
	 * The field of a terrain seed. Both seeds come from the world seed, so a terrain seed always comes with the same
	 * geology seed.
	 */
	public static TerrainField get(long seed, long geologySeed) {
		return FIELDS.computeIfAbsent(seed, s -> new TerrainField(s, geologySeed, new Uplift(new PipelineSource(s))));
	}

	/** Where pipeline windows are cached, or {@code null} outside a server (nothing is written to disk then). */
	static Path storageRoot() {
		return storageRoot;
	}

	/**
	 * The shared models, loaded on first use. If they are still downloading, waits for the download; if they are
	 * missing, fails with a message that says how to get them.
	 */
	static ModelBundle models() {
		Resources resources = Resources.get();
		// Outside the lock: a download can take an hour, and stopping the server needs the lock.
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
		synchronized (TerrainFields.class) {
			if (stopped) {
				throw new IllegalStateException("The server has stopped");
			}
			if (models != null) {
				return models;
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
	}

	/** Frees every field and the models. Fields requested later start fresh. */
	private static synchronized void closeAll() {
		for (TerrainField field : FIELDS.values()) {
			field.close();
		}
		FIELDS.clear();
		if (models != null) {
			models.close();
			models = null;
			NativeHeap.trim();
		}
		storageRoot = null;
	}
}
