package io.github.elpideus.diffusicraft.terrain.diffusion.tensor;

import java.io.IOException;

/**
 * Optional persistent cache for computed windows, so terrain that was generated once is never inferred again
 * (for example when a chunk is regenerated, or when LOD mods sample far terrain after a restart).
 * Implementations must be thread-safe and must never return a partially written window.
 */
public interface WindowStore {
	/** Returns the stored window, or {@code null} if it is absent. */
	Tile load(String tensorId, int wi, int wj, int channels, int size) throws IOException;

	void save(String tensorId, int wi, int wj, Tile tile) throws IOException;
}
