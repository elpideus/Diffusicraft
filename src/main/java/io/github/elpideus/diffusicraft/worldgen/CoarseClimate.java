package io.github.elpideus.diffusicraft.worldgen;

/**
 * A scope in which the biome parameters, the terrain surface and the rock come from the coarse map alone
 * ({@link TerrainField#coarseColumn}), and rivers are left out.
 *
 * <p>Some biome searches cover far more land than players will ever load. Stronghold rings, for example, reach about
 * 20 000 blocks from the origin. Running the full model over all of it would take many minutes, so these searches run
 * inside {@link #call}. So do the far levels of detail that Distant Horizons asks for (see
 * {@link io.github.elpideus.diffusicraft.worldgen.lod.LodSampler}). Their results stay deterministic, because the
 * coarse map is. They can disagree with what is finally generated, but only by the details the coarse map leaves out.
 */
public final class CoarseClimate {
	private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

	private CoarseClimate() {
	}

	/** Runs {@code op} on this thread with the climate, surface and strata reading the coarse map. */
	public static <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> op) throws X {
		return ScopedValue.where(ACTIVE, Boolean.TRUE).call(op);
	}

	/** Whether this thread is inside {@link #call}. */
	public static boolean active() {
		return ACTIVE.isBound();
	}
}
