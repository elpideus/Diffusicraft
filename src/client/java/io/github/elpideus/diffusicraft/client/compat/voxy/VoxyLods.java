package io.github.elpideus.diffusicraft.client.compat.voxy;

import io.github.elpideus.diffusicraft.runtime.Resources;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Voxy's far terrain drawn from Diffusicraft's terrain, where Voxy has not seen the chunks (see {@link VoxyTerrain}).
 * Called from Diffusicraft's mixins into Voxy, which are applied only when it is installed.
 */
public final class VoxyLods {
	/** Statuses of Voxy's store: the section was there, or it never held it. */
	static final int LOADED = 0;
	static final int MISSING = 1;
	static final AtomicLong FILLED = new AtomicLong();
	static final AtomicLong FAILED = new AtomicLong();

	private VoxyLods() {
	}

	/** Gives the world Voxy opened for {@code level} the terrain to fill it from, unless the config turns that off. */
	public static void opened(WorldEngine engine, ResourceKey<Level> level) {
		if (Resources.get().config().voxyLods) {
			((VoxyTerrain.Carrier) (Object) engine).diffusicraft$setTerrain(new VoxyTerrain(level));
		}
	}

	/** Sections filled with blocks from the terrain since the game started. */
	public static long filled() {
		return FILLED.get();
	}

	/** Sections that could not be filled since the game started. */
	public static long failed() {
		return FAILED.get();
	}

	/** The status of a section Voxy's store was asked for, after Diffusicraft has filled it if the store missed it. */
	public static int loaded(WorldEngine engine, WorldSection section, int status) {
		if (status != MISSING) {
			return status;
		}
		VoxyTerrain terrain = ((VoxyTerrain.Carrier) (Object) engine).diffusicraft$terrain();
		return terrain == null ? status : terrain.load(engine, section);
	}

	/** Called as Voxy writes the chunk section at {@code (x, y, z)}, counted in chunk sections, into its world. */
	public static void writing(WorldEngine engine, int x, int y, int z) {
		VoxyTerrain terrain = ((VoxyTerrain.Carrier) (Object) engine).diffusicraft$terrain();
		if (terrain != null) {
			terrain.writing(x, y, z);
		}
	}
}
