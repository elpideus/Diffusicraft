package io.github.elpideus.diffusicraft.worldgen.biome;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Refines vanilla's choice of biome with the real climate. Vanilla's table only sees five coarse bands of
 * temperature and humidity; this sees the seasons too, which is what tells tundra from taiga, a Mediterranean coast
 * from a monsoon one and a cloud forest from a jungle. It runs on top of the multi-noise pick, so every biome a mod
 * adds to the table is left alone, and only vanilla's own biomes are ever replaced.
 *
 * <p>The rules, first match wins:
 * <ul>
 *   <li><b>Cloud forest</b>: tropical mountainsides (little seasonality) between 1000 and 3500 m, 10 to 20 °C, at
 *       least 1500 mm of rain.</li>
 *   <li>Snow and ice are vanilla's where the warmest month stays below {@value #ICE_WARMEST} °C or the year averages
 *       below {@value #POLAR_TEMPERATURE} °C, so the ground is white most of the year.</li>
 *   <li><b>Salt flat</b>: the flattest desert floors, with less than {@value #SALT_FLAT_RELIEF} m of relief, up on
 *       the cold Altiplano too.</li>
 *   <li><b>Tundra</b> and <b>alpine tundra</b>: beyond the tree line, where the warmest month stays below
 *       {@value #TREE_LINE} °C; alpine on mountains. This is what moves the snow line above the tree line.</li>
 *   <li>Bare summits stay bare.</li>
 *   <li><b>Moorland</b>: rolling uplands from {@value #MOOR_ELEVATION} m with cool summers (warmest month below
 *       {@value #MOOR_WARMEST} °C), mild winters and at least 800 mm of rain, as on Dartmoor or in the Pennines.</li>
 *   <li><b>Temperate rainforest</b>: mild oceanic climates (4 to 14 °C, coldest month above -3 °C) with at least
 *       1600 mm of rain.</li>
 *   <li><b>Maquis</b>: Mediterranean scrub, 12 to 21 °C with mild winters, 250 to 1000 mm of strongly seasonal
 *       rain.</li>
 *   <li><b>Heath</b>: patches of oceanic lowland, 6 to 12 °C with 550 to 1100 mm of rain, as on the Lüneburg
 *       Heath or in Jutland.</li>
 *   <li><b>Cold desert</b>: deserts cooler than {@value #COLD_DESERT_TEMPERATURE} °C.</li>
 *   <li><b>Steppe</b>: semi-arid grassland cooler than 18 °C.</li>
 * </ul>
 * Swamps on tropical coasts become mangroves, and mangroves elsewhere swamps.
 *
 * <p>Pure and static, so it is unit-tested without a world.
 */
public final class BiomeRefiner {
	public static final String TUNDRA = "diffusicraft:tundra";
	public static final String ALPINE_TUNDRA = "diffusicraft:alpine_tundra";
	public static final String CLOUD_FOREST = "diffusicraft:cloud_forest";
	public static final String TEMPERATE_RAINFOREST = "diffusicraft:temperate_rainforest";
	public static final String MAQUIS = "diffusicraft:maquis";
	public static final String COLD_DESERT = "diffusicraft:cold_desert";
	public static final String STEPPE = "diffusicraft:steppe";
	public static final String MOORLAND = "diffusicraft:moorland";
	public static final String HEATH = "diffusicraft:heath";
	public static final String SALT_FLAT = "diffusicraft:salt_flat";
	public static final String SWAMP = "minecraft:swamp";
	public static final String MANGROVE_SWAMP = "minecraft:mangrove_swamp";

	/** Every biome {@link #refine} can return. */
	public static final List<String> TARGETS = List.of(TUNDRA, ALPINE_TUNDRA, CLOUD_FOREST, TEMPERATE_RAINFOREST,
		MAQUIS, COLD_DESERT, STEPPE, MOORLAND, HEATH, SALT_FLAT, SWAMP, MANGROVE_SWAMP);

	/** Warmest month below which snow lies all year round, °C. */
	static final float ICE_WARMEST = 3f;
	/** Annual mean below which the ground is white most of the year, °C. */
	static final float POLAR_TEMPERATURE = -10f;
	/** The tree line: no forest grows where the warmest month stays below this, °C. */
	static final float TREE_LINE = 10f;
	/** Land this rugged, in meters of relief, or this high, in meters, is mountainous. */
	static final float MOUNTAIN_RELIEF = 90f;
	static final float MOUNTAIN_ELEVATION = 1500f;
	/** Deserts cooler than this are cold deserts, °C. */
	static final float COLD_DESERT_TEMPERATURE = 12f;
	/** Coldest month above which a swamp at sea level is a mangrove, °C. */
	static final float MANGROVE_COLDEST = 15f;
	/** Mangroves grow on tidal flats: no higher than this, in meters. */
	static final float MANGROVE_ELEVATION = 10f;
	/** Desert floors flatter than this, in meters of relief, are salt flats: the dry beds of old lakes. */
	static final float SALT_FLAT_RELIEF = 4f;
	/** Moors begin at this elevation, in meters. */
	static final float MOOR_ELEVATION = 300f;
	/** Summers on the moors stay cooler than this, °C: the wind and the cold keep the trees off. */
	static final float MOOR_WARMEST = 15.5f;
	/**
	 * Heaths cover the part of the climate that suits them where the variant noise is below this: about a fifth of
	 * it, in patches among the woods, as people cleared them.
	 */
	static final float HEATH_VARIANT = -0.3f;

	/** What a vanilla biome is, as far as the rules care. */
	enum Kind {
		/** Oceans, shores, rivers, caves and mushroom islands: never refined. */
		FIXED,
		/** Bare or snowy summits. */
		PEAK,
		/** High mountain slopes with vegetation. */
		SLOPE,
		/** Windswept hills. */
		HILLS,
		WETLAND,
		DESERT,
		BADLANDS,
		SNOWY,
		JUNGLE,
		/** Plains, forests, taigas and savannas. */
		LAND
	}

	private static final Map<String, Kind> KINDS = Map.ofEntries(
		fixed("ocean"), fixed("deep_ocean"), fixed("cold_ocean"), fixed("deep_cold_ocean"), fixed("frozen_ocean"),
		fixed("deep_frozen_ocean"), fixed("lukewarm_ocean"), fixed("deep_lukewarm_ocean"), fixed("warm_ocean"),
		fixed("beach"), fixed("snowy_beach"), fixed("stony_shore"), fixed("river"), fixed("frozen_river"),
		fixed("dripstone_caves"), fixed("lush_caves"), fixed("sulfur_caves"), fixed("deep_dark"),
		fixed("mushroom_fields"),
		kind("frozen_peaks", Kind.PEAK), kind("jagged_peaks", Kind.PEAK), kind("stony_peaks", Kind.PEAK),
		kind("snowy_slopes", Kind.SLOPE), kind("grove", Kind.SLOPE), kind("meadow", Kind.SLOPE),
		kind("cherry_grove", Kind.SLOPE),
		kind("windswept_hills", Kind.HILLS), kind("windswept_gravelly_hills", Kind.HILLS),
		kind("windswept_forest", Kind.HILLS), kind("windswept_savanna", Kind.HILLS),
		kind("swamp", Kind.WETLAND), kind("mangrove_swamp", Kind.WETLAND),
		kind("desert", Kind.DESERT),
		kind("badlands", Kind.BADLANDS), kind("eroded_badlands", Kind.BADLANDS),
		kind("wooded_badlands", Kind.BADLANDS),
		kind("snowy_plains", Kind.SNOWY), kind("ice_spikes", Kind.SNOWY), kind("snowy_taiga", Kind.SNOWY),
		kind("jungle", Kind.JUNGLE), kind("sparse_jungle", Kind.JUNGLE), kind("bamboo_jungle", Kind.JUNGLE),
		kind("plains", Kind.LAND), kind("sunflower_plains", Kind.LAND), kind("forest", Kind.LAND),
		kind("flower_forest", Kind.LAND), kind("birch_forest", Kind.LAND), kind("old_growth_birch_forest", Kind.LAND),
		kind("dark_forest", Kind.LAND), kind("pale_garden", Kind.LAND), kind("dappled_forest", Kind.LAND),
		kind("taiga", Kind.LAND), kind("old_growth_pine_taiga", Kind.LAND),
		kind("old_growth_spruce_taiga", Kind.LAND), kind("savanna", Kind.LAND), kind("savanna_plateau", Kind.LAND));

	private BiomeRefiner() {
	}

	private static Map.Entry<String, Kind> fixed(String path) {
		return kind(path, Kind.FIXED);
	}

	private static Map.Entry<String, Kind> kind(String path, Kind kind) {
		return Map.entry("minecraft:" + path, kind);
	}

	/**
	 * The biome that replaces vanilla's pick {@code biome} (an id such as {@code minecraft:plains}) in a column of
	 * this climate, or {@code null} to keep it.
	 */
	public static @Nullable String refine(String biome, BiomeClimate c) {
		Kind kind = KINDS.get(biome);
		if (kind == null || kind == Kind.FIXED) {
			return null;
		}
		if (kind == Kind.WETLAND) {
			boolean mangrove = c.coldestMonth() >= MANGROVE_COLDEST && c.elevation < MANGROVE_ELEVATION;
			if (mangrove != biome.equals(MANGROVE_SWAMP)) {
				return mangrove ? MANGROVE_SWAMP : SWAMP;
			}
			return null;
		}
		boolean dry = kind == Kind.DESERT || kind == Kind.BADLANDS;
		if (!dry && kind != Kind.PEAK && cloudForest(c)) {
			return CLOUD_FOREST;
		}
		float warmest = c.warmestMonth();
		if (warmest < ICE_WARMEST || c.temperature < POLAR_TEMPERATURE) {
			return null;
		}
		float aridity = c.aridity();
		if ((kind == Kind.DESERT || kind == Kind.LAND) && saltFlat(c, aridity)) {
			return SALT_FLAT;
		}
		boolean mountain = kind == Kind.PEAK || kind == Kind.SLOPE || kind == Kind.HILLS
			|| c.relief >= MOUNTAIN_RELIEF || c.elevation >= MOUNTAIN_ELEVATION;
		if (warmest < TREE_LINE) {
			return mountain ? ALPINE_TUNDRA : TUNDRA;
		}
		if (kind == Kind.PEAK || kind == Kind.JUNGLE || kind == Kind.BADLANDS || kind == Kind.SNOWY) {
			return null;
		}
		boolean meadow = biome.equals("minecraft:meadow");
		if ((kind == Kind.LAND || kind == Kind.HILLS || meadow) && moorland(c)) {
			return MOORLAND;
		}
		boolean vegetated = kind != Kind.DESERT;
		if (vegetated && temperateRainforest(c)) {
			return TEMPERATE_RAINFOREST;
		}
		if (kind != Kind.SLOPE || meadow) {
			if (c.elevation < 1200f && maquis(c)) {
				return MAQUIS;
			}
		}
		if (kind == Kind.LAND && heath(c)) {
			return HEATH;
		}
		if (kind == Kind.LAND || kind == Kind.DESERT) {
			if (aridity < 0.5f && c.temperature < COLD_DESERT_TEMPERATURE) {
				return COLD_DESERT;
			}
			if (kind == Kind.LAND && aridity < 1f && c.temperature < 18f) {
				return STEPPE;
			}
		}
		return null;
	}

	static boolean cloudForest(BiomeClimate c) {
		return c.seasonality < 2.5f && c.elevation >= 1000f && c.elevation <= 3500f
			&& c.temperature >= 10f && c.temperature <= 20f && c.precipitation >= 1500f;
	}

	static boolean temperateRainforest(BiomeClimate c) {
		return c.temperature >= 4f && c.temperature <= 14f && c.precipitation >= 1600f && c.seasonality < 7f
			&& c.coldestMonth() > -3f;
	}

	static boolean saltFlat(BiomeClimate c, float aridity) {
		return aridity < 0.5f && c.relief < SALT_FLAT_RELIEF && c.elevation > 2f;
	}

	static boolean moorland(BiomeClimate c) {
		return c.elevation >= MOOR_ELEVATION && c.elevation < MOUNTAIN_ELEVATION && c.relief < MOUNTAIN_RELIEF
			&& c.warmestMonth() < MOOR_WARMEST && c.coldestMonth() > -6f && c.precipitation >= 800f;
	}

	static boolean heath(BiomeClimate c) {
		return c.variant < HEATH_VARIANT && c.elevation < MOOR_ELEVATION && c.temperature >= 6f
			&& c.temperature <= 12f && c.coldestMonth() > -4f && c.precipitation >= 550f && c.precipitation <= 1100f
			&& c.seasonality < 7f;
	}

	static boolean maquis(BiomeClimate c) {
		return c.temperature >= 12f && c.temperature <= 21f && c.coldestMonth() >= 2f
			&& c.precipitation >= 250f && c.precipitation <= 1000f && c.precipitationSeasonality >= 40f;
	}
}
