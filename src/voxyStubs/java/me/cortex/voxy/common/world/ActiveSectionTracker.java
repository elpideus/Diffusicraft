package me.cortex.voxy.common.world;

import org.jspecify.annotations.Nullable;

/**
 * Diffusicraft's declaration of the members of Voxy's {@code ActiveSectionTracker} it uses, to compile against: the
 * sections of a world in memory, loaded from its store as they are asked for. Not Voxy's code: Voxy is All Rights
 * Reserved, and at runtime its own class stands in for this one. Never shipped.
 */
public class ActiveSectionTracker {
	public final @Nullable WorldEngine engine = null;

	private ActiveSectionTracker() {
	}

	/**
	 * Reads a section from the store. Returns 0 when it was there, 1 when the store never held it (Voxy then fills it
	 * with air), and less than 0 when it could not be read.
	 */
	public interface SectionLoader {
		int load(WorldSection section);
	}
}
