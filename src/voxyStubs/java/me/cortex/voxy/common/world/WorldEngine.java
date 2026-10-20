package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Diffusicraft's declaration of the members of Voxy's {@code WorldEngine} it uses, to compile against. Not Voxy's
 * code: Voxy is All Rights Reserved, and at runtime its own class stands in for this one. Never shipped. The constants
 * are Voxy's values, which compile into Diffusicraft's code.
 */
public final class WorldEngine {
	/** The coarsest level of detail. */
	public static final int MAX_LOD_LAYER = 4;
	/** A {@link #markDirty} flag: which of the section's children hold blocks has changed. */
	public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
	/** A {@link #markDirty} flag: the change is not saved. */
	public static final int UPDATE_TYPE_DONT_SAVE = 4;

	private WorldEngine() {
	}

	/** The section at a level and position, loaded if need be. Release it when done. */
	public WorldSection acquire(int lvl, int x, int y, int z) {
		throw new AssertionError();
	}

	/** Tells the renderer, and the store unless the flags say otherwise, that a section has changed. */
	public void markDirty(WorldSection section, int changeState, int neighborMsk) {
		throw new AssertionError();
	}

	public Mapper getMapper() {
		throw new AssertionError();
	}

	public boolean isLive() {
		throw new AssertionError();
	}
}
