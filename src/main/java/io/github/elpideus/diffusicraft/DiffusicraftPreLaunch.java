package io.github.elpideus.diffusicraft;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Runs before Minecraft classes load. Used to read early (restart-required) settings. */
public final class DiffusicraftPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
	}
}
