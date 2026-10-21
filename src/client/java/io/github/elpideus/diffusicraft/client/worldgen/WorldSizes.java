package io.github.elpideus.diffusicraft.client.worldgen;

import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The size of a world being created. Each size is its own world preset; only the default one is listed among the
 * world types, and the others are picked from it.
 */
public final class WorldSizes {
	private WorldSizes() {
	}

	/** The size the world will be generated at, or {@code null} when it is not a Diffusicraft world. */
	public static WorldScale selected(WorldCreationUiState ui) {
		Holder<WorldPreset> preset = ui.getWorldType().preset();
		return preset == null ? null : preset.unwrapKey().map(DiffusicraftWorldgen::scale).orElse(null);
	}

	/** Makes the world a Diffusicraft world of the given size. */
	public static void select(WorldCreationUiState ui, WorldScale scale) {
		Holder<WorldPreset> preset = ui.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
			.get(DiffusicraftWorldgen.preset(scale))
			.orElseThrow(() -> new IllegalStateException("No world preset for the size " + scale.getSerializedName()));
		ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
	}
}
