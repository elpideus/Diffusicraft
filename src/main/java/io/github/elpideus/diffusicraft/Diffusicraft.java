package io.github.elpideus.diffusicraft;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common (client + dedicated server) entrypoint. */
public final class Diffusicraft implements ModInitializer {
	public static final String MOD_ID = "diffusicraft";
	public static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft");

	@Override
	public void onInitialize() {
		LOGGER.info("Diffusicraft initializing");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
