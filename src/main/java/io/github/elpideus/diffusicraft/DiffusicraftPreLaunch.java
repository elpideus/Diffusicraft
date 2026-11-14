package io.github.elpideus.diffusicraft;

import io.github.elpideus.diffusicraft.compat.IncompatibleMods;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Runs before Minecraft classes load. */
public final class DiffusicraftPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		IncompatibleMods.stopIfInstalled();
	}
}
