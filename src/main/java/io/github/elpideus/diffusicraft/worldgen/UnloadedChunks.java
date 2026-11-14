package io.github.elpideus.diffusicraft.worldgen;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Keeps players from stopping the server. Moving and ticking a player reads the blocks around them, and on the server a
 * read waits for its chunk. A vanilla chunk comes in milliseconds, but here it may need the models: the server then
 * stands still for seconds after a teleport, and past a minute a dedicated server's watchdog ends it.
 *
 * <p>So while the server handles a player, or runs what looks around one (a spawner, a function a data pack ticks or
 * schedules, as Incendium's looks at each player's biome every second, or Lithostitched's look for the structure a
 * player stands in), a chunk that is not there reads as empty, as it does on that player's client, which has no such
 * chunk yet either. It counts as not loaded too, and a biome read from it sees plains for that moment. The chunk itself
 * comes in its turn, from the player's tickets.
 *
 * <p>The far terrain's sketches keep off the server the same way. What grows in one may reach past it for the real
 * level, as a cat does to choose its coat among the structures around it and as a mod may for every tree, and off the
 * server thread such a read makes the server generate that far chunk and hold its tick until it is done. While a thread
 * sketches, every chunk reads as empty, so that a sketch never depends on what happens to be loaded.
 */
public final class UnloadedChunks {
	/** The chunks that read as empty now. Only the server thread sets or asks about it. */
	private static @Nullable ServerChunkCache lenient;
	/** Whether this thread is sketching far terrain. */
	private static final ThreadLocal<Boolean> SKETCHING = ThreadLocal.withInitial(() -> false);

	private UnloadedChunks() {
	}

	/** Runs what places blocks in a sketch of far terrain, on this thread, and returns what it made. */
	public static <T> T sketching(Supplier<T> action) {
		SKETCHING.set(true);
		try {
			return action.get();
		} finally {
			SKETCHING.set(false);
		}
	}

	/** Whether this thread is sketching far terrain. */
	public static boolean sketching() {
		return SKETCHING.get();
	}

	/** Runs what handles a player, or looks around one, in {@code level}. */
	public static void around(ServerLevel level, Runnable action) {
		ServerChunkCache chunks = level.getChunkSource();
		if (chunks == lenient || !(chunks.getGenerator() instanceof NoiseBasedChunkGenerator generator)
			|| !DiffusicraftWorldgen.isDiffusicraft(generator.generatorSettings().value())) {
			action.run();
			return;
		}
		ServerChunkCache outer = lenient;
		lenient = chunks;
		try {
			action.run();
		} finally {
			lenient = outer;
		}
	}

	/** Whether the server thread is handling a player in these chunks. */
	public static boolean lenient(ServerChunkCache chunks) {
		return chunks == lenient;
	}
}
