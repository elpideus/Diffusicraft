package io.github.elpideus.diffusicraft.client.compat.sodium;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

/**
 * Whether Sodium draws a chunk section, for the far terrain, which leaves out the chunks the world draws. Sodium draws
 * the chunks in vanilla's place, so vanilla's own meshes stay empty. Calls
 * {@code SodiumWorldRenderer.instanceNullable()} and its {@code isSectionReady(int, int, int)} and
 * {@code isBoxVisible(double, double, double, double, double, double)} through method handles, so that Diffusicraft
 * does not compile against Sodium. Load it only when Sodium is installed.
 */
public final class SodiumSections {
	private static final @Nullable MethodHandle INSTANCE;
	private static final @Nullable MethodHandle READY;
	private static final @Nullable MethodHandle VISIBLE;

	static {
		MethodHandle instance = null;
		MethodHandle ready = null;
		MethodHandle visible = null;
		try {
			Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			instance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer))
				.asType(MethodType.methodType(Object.class));
			ready = lookup.findVirtual(renderer, "isSectionReady",
					MethodType.methodType(boolean.class, int.class, int.class, int.class))
				.asType(MethodType.methodType(boolean.class, Object.class, int.class, int.class, int.class));
			visible = lookup.findVirtual(renderer, "isBoxVisible", MethodType.methodType(boolean.class, double.class,
					double.class, double.class, double.class, double.class, double.class))
				.asType(MethodType.methodType(boolean.class, Object.class, double.class, double.class, double.class,
					double.class, double.class, double.class));
		} catch (ReflectiveOperationException e) {
			Diffusicraft.LOGGER.warn("Sodium has no SodiumWorldRenderer.isSectionReady or isBoxVisible; the far "
				+ "terrain will leave out every chunk within the render distance, built or not", e);
			ready = null;
		}
		INSTANCE = instance;
		READY = ready;
		VISIBLE = visible;
	}

	private SodiumSections() {
	}

	/**
	 * Whether Sodium draws the section at section coordinates {@code (x, y, z)} where the camera can see it, or true
	 * where that cannot be told. Sodium has built its mesh, and the tree of the sections it draws holds it, or it has
	 * nothing to draw: a section Sodium has just built joins the tree only once Sodium culls again, a frame or a few
	 * later, and until then Sodium leaves it out. On the render thread, once Sodium has culled for the frame.
	 */
	public static boolean drawn(int x, int y, int z) {
		if (INSTANCE == null || READY == null || VISIBLE == null) {
			return true;
		}
		try {
			Object renderer = INSTANCE.invokeExact();
			if (renderer == null) {
				return true;
			}
			// A box no wider than a point, at the section's centre, which Sodium widens by half a block: only the
			// section itself.
			double centerX = (x << 4) + 8;
			double centerY = (y << 4) + 8;
			double centerZ = (z << 4) + 8;
			return (boolean) READY.invokeExact(renderer, x, y, z)
				&& (boolean) VISIBLE.invokeExact(renderer, centerX, centerY, centerZ, centerX, centerY, centerZ);
		} catch (Throwable e) {
			throw new IllegalStateException("Sodium's isSectionReady or isBoxVisible failed", e);
		}
	}
}
