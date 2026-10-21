package io.github.elpideus.diffusicraft.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands Distant Horizons a generator of its own for every Diffusicraft level, so its far terrain comes straight from
 * the terrain instead of from generated chunks. Other levels keep Distant Horizons' own generator, or whatever another
 * mod registered. Only loaded when Distant Horizons is; see docs/COMPATIBILITY.md.
 */
public final class DistantHorizonsCompat {
	/**
	 * The highest an LOD column reaches, in blocks above the bottom of its level. Distant Horizons packs the bottom and
	 * the height of each run into 12 bits each and wraps anything larger, so a level taller than this (Diffusicraft's
	 * Extreme and Ultra sizes) is cut off at this height in its LODs.
	 */
	public static final int MAX_HEIGHT = 4095;

	private static final Logger LOGGER = LoggerFactory.getLogger(DistantHorizonsCompat.class);

	private DistantHorizonsCompat() {
	}

	public static void init() {
		DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
			@Override
			public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
				register(event.value.levelWrapper);
			}
		});
		LOGGER.info("Distant Horizons found: Diffusicraft levels get their LODs from the terrain");
	}

	private static void register(IDhApiLevelWrapper levelWrapper) {
		// Distant Horizons generates on the server's levels; the client's copies only render.
		if (levelWrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL
			|| !(levelWrapper.getWrappedMcObject() instanceof ServerLevel level)) {
			return;
		}
		LodSampler sampler = LodSampler.of(level);
		if (sampler == null) {
			return;
		}
		DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper,
			new DiffusicraftLodGenerator(sampler, levelWrapper));
		if (result.success) {
			LOGGER.info("Distant Horizons LODs for {} come from the terrain", level.dimension().identifier());
			// Players are told in the chat; a dedicated server's operator learns it here.
			if (level.getHeight() > MAX_HEIGHT + 1) {
				LOGGER.warn("{} is taller than Distant Horizons holds: its LODs stop at y = {}, and higher ground "
					+ "shows flat in them", level.dimension().identifier(), level.getMinY() + MAX_HEIGHT - 1);
			}
		} else {
			LOGGER.warn("Distant Horizons refused the LOD generator for {}: {}", level.dimension().identifier(),
				result.message);
		}
	}
}
