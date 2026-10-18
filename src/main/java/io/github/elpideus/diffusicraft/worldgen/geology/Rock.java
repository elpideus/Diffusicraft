package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * The rocks the {@link Geology} model places. Some are vanilla blocks, some are Diffusicraft's; the mapping to blocks
 * lives in {@code StrataRule}, so the model stays free of Minecraft classes.
 */
public enum Rock {
	// Sedimentary cover, in beds.
	SANDSTONE(false),
	RED_SANDSTONE(false),
	SHALE(false),
	/** A coal seam: a thin bed of shale rich in coal. */
	COAL_SEAM(false),
	LIMESTONE(false),
	CHALK(false),
	ROCK_SALT(false),
	// Metamorphic: sediments that were buried and folded, and the old crust under them.
	SLATE(false),
	MARBLE(false),
	SCHIST(false),
	GNEISS(false),
	// Upper crust.
	STONE(false),
	GRANITE(false),
	DIORITE(false),
	ANDESITE(false),
	TUFF(false),
	// Lower crust and mantle.
	DEEPSLATE(true),
	GABBRO(true),
	PERIDOTITE(true),
	KIMBERLITE(true),
	// The magma layer above bedrock.
	MAGMA(true),
	BLACKSTONE(true),
	BASALT(true);

	private final boolean deep;

	Rock(boolean deep) {
		this.deep = deep;
	}

	/** Whether ores in this rock take their deepslate form. */
	public boolean deep() {
		return deep;
	}
}
