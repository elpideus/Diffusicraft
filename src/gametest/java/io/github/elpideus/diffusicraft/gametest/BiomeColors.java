package io.github.elpideus.diffusicraft.gametest;

import java.util.Map;

/** Map colours for biomes, close to the ones map viewers use. Unknown biomes get a colour from their name. */
final class BiomeColors {
	private static final Map<String, Integer> COLORS = Map.ofEntries(
		Map.entry("minecraft:plains", 0x8db360),
		Map.entry("minecraft:sunflower_plains", 0xb5db88),
		Map.entry("minecraft:snowy_plains", 0xe8f4f4),
		Map.entry("minecraft:ice_spikes", 0xb4dcdc),
		Map.entry("minecraft:desert", 0xfa9418),
		Map.entry("minecraft:swamp", 0x07f9b2),
		Map.entry("minecraft:mangrove_swamp", 0x2ccc8e),
		Map.entry("minecraft:forest", 0x056621),
		Map.entry("minecraft:flower_forest", 0x2d8e49),
		Map.entry("minecraft:birch_forest", 0x307444),
		Map.entry("minecraft:dark_forest", 0x40511a),
		Map.entry("minecraft:pale_garden", 0x9a9a8f),
		Map.entry("minecraft:dappled_forest", 0x3f7f3a),
		Map.entry("minecraft:old_growth_birch_forest", 0x589c6c),
		Map.entry("minecraft:old_growth_pine_taiga", 0x596651),
		Map.entry("minecraft:old_growth_spruce_taiga", 0x818e79),
		Map.entry("minecraft:taiga", 0x0b6659),
		Map.entry("minecraft:snowy_taiga", 0x31554a),
		Map.entry("minecraft:savanna", 0xbdb25f),
		Map.entry("minecraft:savanna_plateau", 0xa79d64),
		Map.entry("minecraft:windswept_hills", 0x606060),
		Map.entry("minecraft:windswept_gravelly_hills", 0x888888),
		Map.entry("minecraft:windswept_forest", 0x507050),
		Map.entry("minecraft:windswept_savanna", 0xe5da87),
		Map.entry("minecraft:jungle", 0x537b09),
		Map.entry("minecraft:sparse_jungle", 0x628b17),
		Map.entry("minecraft:bamboo_jungle", 0x768e14),
		Map.entry("minecraft:badlands", 0xd94515),
		Map.entry("minecraft:eroded_badlands", 0xff6d3d),
		Map.entry("minecraft:wooded_badlands", 0xb09765),
		Map.entry("minecraft:meadow", 0x60a445),
		Map.entry("minecraft:cherry_grove", 0xf4b4d0),
		Map.entry("minecraft:grove", 0x47726c),
		Map.entry("minecraft:snowy_slopes", 0xc4c4c4),
		Map.entry("minecraft:frozen_peaks", 0xa0a0ff),
		Map.entry("minecraft:jagged_peaks", 0xdcdcc8),
		Map.entry("minecraft:stony_peaks", 0x7b8f74),
		Map.entry("minecraft:river", 0x0000ff),
		Map.entry("minecraft:frozen_river", 0xa0a0ff),
		Map.entry("minecraft:beach", 0xfade55),
		Map.entry("minecraft:snowy_beach", 0xfaf0c0),
		Map.entry("minecraft:stony_shore", 0xa2a284),
		Map.entry("minecraft:mushroom_fields", 0xff00ff),
		Map.entry("minecraft:ocean", 0x000070),
		Map.entry("minecraft:deep_ocean", 0x000030),
		Map.entry("minecraft:warm_ocean", 0x0000ac),
		Map.entry("minecraft:lukewarm_ocean", 0x000090),
		Map.entry("minecraft:deep_lukewarm_ocean", 0x000040),
		Map.entry("minecraft:cold_ocean", 0x202070),
		Map.entry("minecraft:deep_cold_ocean", 0x202038),
		Map.entry("minecraft:frozen_ocean", 0x7070d6),
		Map.entry("minecraft:deep_frozen_ocean", 0x404090),
		Map.entry("diffusicraft:tundra", 0x9fa77a),
		Map.entry("diffusicraft:alpine_tundra", 0x8f9a6e),
		Map.entry("diffusicraft:cloud_forest", 0x2f7a4a),
		Map.entry("diffusicraft:temperate_rainforest", 0x1f5a2a),
		Map.entry("diffusicraft:maquis", 0x9a9a4a),
		Map.entry("diffusicraft:cold_desert", 0xc8b48a),
		Map.entry("diffusicraft:steppe", 0xc3c06a)
	);

	private BiomeColors() {
	}

	static int of(String biome) {
		Integer color = COLORS.get(biome);
		if (color != null) {
			return color;
		}
		int h = biome.hashCode();
		return 0x404040 | (h & 0xbfbfbf);
	}
}
