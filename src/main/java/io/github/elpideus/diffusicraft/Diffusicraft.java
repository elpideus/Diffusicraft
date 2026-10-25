package io.github.elpideus.diffusicraft;

import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import io.github.elpideus.diffusicraft.compat.dh.DistantHorizonsCompat;
import io.github.elpideus.diffusicraft.map.ReliefMap;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.lod.LodFeed;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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
		FabricLoader loader = FabricLoader.getInstance();
		Resources resources = Resources.init(loader.getGameDir(), loader.getConfigDir());
		DiffusicraftBlocks.init();
		DiffusicraftWorldgen.init();
		LodStore.init();
		LodFeed.init();
		ReliefMap.init();
		if (loader.isModLoaded("distanthorizons") && resources.config().distantHorizonsLods) {
			DistantHorizonsCompat.init();
		}
		if (loader.getEnvironmentType() == EnvType.SERVER) {
			resources.prepareServer();
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
