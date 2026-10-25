package io.github.elpideus.diffusicraft.client.compat.sodium;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

/**
 * Whether Sodium has built the mesh of a chunk section, for the far terrain, which leaves out the chunks the world
 * draws. Sodium draws the chunks in vanilla's place, so vanilla's own meshes stay empty. Calls
 * {@code SodiumWorldRenderer.instanceNullable()} and its {@code isSectionReady(int, int, int)} through method handles,
 * so that Diffusicraft does not compile against Sodium. Load it only when Sodium is installed.
 */
public final class SodiumSections {
	private static final @Nullable MethodHandle INSTANCE;
	private static final @Nullable MethodHandle READY;

	static {
		MethodHandle instance = null;
		MethodHandle ready = null;
		try {
			Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			instance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer))
				.asType(MethodType.methodType(Object.class));
			ready = lookup.findVirtual(renderer, "isSectionReady",
					MethodType.methodType(boolean.class, int.class, int.class, int.class))
				.asType(MethodType.methodType(boolean.class, Object.class, int.class, int.class, int.class));
		} catch (ReflectiveOperationException e) {
			Diffusicraft.LOGGER.warn("Sodium has no SodiumWorldRenderer.isSectionReady; the far terrain will leave out every "
				+ "chunk within the render distance, built or not", e);
		}
		INSTANCE = instance;
		READY = ready;
	}

	private SodiumSections() {
	}

	/**
	 * Whether Sodium has built the section at section coordinates {@code (x, y, z)}, or true where that cannot be told.
	 * On the render thread.
	 */
	public static boolean ready(int x, int y, int z) {
		if (INSTANCE == null || READY == null) {
			return true;
		}
		try {
			Object renderer = INSTANCE.invokeExact();
			return renderer == null || (boolean) READY.invokeExact(renderer, x, y, z);
		} catch (Throwable e) {
			throw new IllegalStateException("Sodium's isSectionReady failed", e);
		}
	}
}
