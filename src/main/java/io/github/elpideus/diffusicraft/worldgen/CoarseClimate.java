package io.github.elpideus.diffusicraft.worldgen;

/**
 * A scope in which the biome parameters come from the coarse map alone ({@link TerrainField#coarseColumn}).
 *
 * <p>Some biome searches cover far more land than players will ever load. Stronghold rings, for example, reach about
 * 20 000 blocks from the origin. Running the full model over all of it would take many minutes, so these searches run
 * inside {@link #call}. Their results stay deterministic, because the coarse map is. They can disagree with the
 * biomes that are finally generated, but only by the details the coarse map leaves out.
 */
public final class CoarseClimate {
	private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

	private CoarseClimate() {
	}

	/** Runs {@code op} on this thread with the climate functions reading the coarse map. */
	public static <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> op) throws X {
		return ScopedValue.where(ACTIVE, Boolean.TRUE).call(op);
	}

	/** Whether this thread is inside {@link #call}. */
	public static boolean active() {
		return ACTIVE.isBound();
	}
}
