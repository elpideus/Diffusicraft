package io.github.elpideus.diffusicraft.client.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * What the blocks of the far terrain are made of, numbered as Distant Horizons numbers its block materials, so that
 * shader packs written for it light leaves, water and glowing blocks of our far terrain as they would its own. One per
 * level: the tags it reads come with the level's server.
 */
final class FarMaterials {
	static final int UNKNOWN = 0;
	static final int LEAVES = 1;
	static final int STONE = 2;
	static final int WOOD = 3;
	static final int DIRT = 5;
	static final int LAVA = 6;
	static final int DEEPSLATE = 7;
	static final int SNOW = 8;
	static final int SAND = 9;
	static final int TERRACOTTA = 10;
	static final int NETHER_STONE = 11;
	static final int WATER = 12;
	static final int GRASS = 13;
	static final int ILLUMINATED = 15;

	private final Map<BlockState, Integer> materials = new ConcurrentHashMap<>();

	int of(BlockState state) {
		return materials.computeIfAbsent(state, FarMaterials::material);
	}

	private static int material(BlockState state) {
		if (state.getFluidState().is(Fluids.WATER) && !state.isSolid()) {
			return WATER;
		}
		if (state.getFluidState().is(Fluids.LAVA)) {
			return LAVA;
		}
		if (state.getLightEmission() > 0) {
			return ILLUMINATED;
		}
		if (state.is(BlockTags.LEAVES)) {
			return LEAVES;
		}
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) {
			return WOOD;
		}
		if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
			return GRASS;
		}
		if (state.is(BlockTags.SNOW) || state.is(BlockTags.ICE)) {
			return SNOW;
		}
		if (state.is(BlockTags.SAND)) {
			return SAND;
		}
		if (state.is(BlockTags.TERRACOTTA)) {
			return TERRACOTTA;
		}
		if (state.is(BlockTags.DIRT)) {
			return DIRT;
		}
		if (state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
			return DEEPSLATE;
		}
		if (state.is(BlockTags.BASE_STONE_NETHER)) {
			return NETHER_STONE;
		}
		if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
			return STONE;
		}
		return UNKNOWN;
	}
}
