package io.github.elpideus.diffusicraft.worldgen;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jspecify.annotations.Nullable;


/**
 * Keeps players from stopping the server. Moving and ticking a player reads the blocks around them, and on the server a
 * read waits for its chunk. A vanilla chunk comes in milliseconds, but here it may need the models: the server then
 * stands still for seconds after a teleport, and past a minute a dedicated server's watchdog ends it.
 *
 * <p>So while the server handles a player, or runs a spawner that looks around one, a chunk that is not there reads as
 * empty, as it does on that player's client, which has no such chunk yet either. It counts as not loaded too, and a
 * biome read from it sees plains for that moment. The chunk itself comes in its turn, from the player's tickets.
 */
public final class UnloadedChunks {
	/** The chunks that read as empty now. Only the server thread sets or asks about it. */
	private static @Nullable ServerChunkCache lenient;

	private UnloadedChunks() {
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
