package io.github.elpideus.diffusicraft.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen;

/** Mod Menu's "Configure" button opens the resource screen. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ResourceManagerScreen::from;
	}
}
