package io.github.elpideus.diffusicraft.compat.tectonic;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Keeps Tectonic from blending a Diffusicraft world's terrain. Tectonic takes every overworld chunk saved without its
 * blending version for an old one as it loads, and gives it the data vanilla blends new terrain into: in a Diffusicraft
 * world made before Tectonic was installed, the chunks generated next to explored land would bend toward it. Tectonic
 * changes nothing in a Diffusicraft world's terrain, so its chunks are marked current instead.
 *
 * <p>Tectonic is not a dependency: its version is read by reflection, and this class loads only with its mixin.
 */
public final class TectonicBlending {
	private static final String TECTONIC = "dev.worldgen.tectonic.Tectonic";
	private static final @Nullable MethodHandle KEY;
	private static final @Nullable MethodHandle VERSION;

	static {
		MethodHandle key = null;
		MethodHandle version = null;
		try {
			Class<?> tectonic = Class.forName(TECTONIC);
			key = MethodHandles.publicLookup().findStaticGetter(tectonic, "BLENDING_KEY", String.class);
			version = MethodHandles.publicLookup().findStaticGetter(tectonic, "BLENDING_VERSION", int.class);
		} catch (ReflectiveOperationException e) {
			Diffusicraft.LOGGER.warn("Could not read Tectonic's blending version; it may blend old chunks", e);
		}
		KEY = key;
		VERSION = version;
	}

	private TectonicBlending() {
	}

	/** Marks a loading chunk's data as saved by the running Tectonic. */
	public static void markCurrent(CompoundTag chunkData) {
		if (KEY == null || VERSION == null) {
			return;
		}
		try {
			chunkData.putInt((String) KEY.invokeExact(), (int) VERSION.invokeExact());
		} catch (Throwable e) {
			throw new IllegalStateException("Could not read Tectonic's blending version", e);
		}
	}
}
