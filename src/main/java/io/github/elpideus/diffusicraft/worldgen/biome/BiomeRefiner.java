package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.Glaciation;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refines vanilla's choice of biome with the real climate. Vanilla's table only sees five coarse bands of
 * temperature and humidity; this sees the seasons too, which is what tells tundra from taiga, a Mediterranean coast
 * from a monsoon one and a cloud forest from a jungle. It runs on top of the multi-noise pick, so every biome a mod
 * adds to the table is left alone, and only vanilla's own biomes are ever replaced.
 *
 * <p>The rules, first match wins:
 * <ul>
 *   <li><b>Fjord</b>: the drowned valleys that {@link Glaciation} carves into cold mountain coasts, where vanilla
 *       puts an ocean, cold or not. Frozen oceans stay frozen, as in Greenland's fjords.</li>
 *   <li>Wetlands, from vanilla's swamp band: <b>mangroves</b> on tropical coasts, páramo on tropical mountains,
 *       <b>bogs</b> where the summers stay cooler than {@value #BOG_WARMEST} °C and are not dry, tundra beyond the
 *       tree line, swamps elsewhere.</li>
 *   <li><b>Cloud forest</b>: tropical mountainsides (little seasonality) between 1000 and 3500 m, 10 to 20 °C, at
 *       least 1500 mm of rain.</li>
 *   <li>Snow and ice where the warmest month stays below {@value #ICE_WARMEST} °C, or where the year averages below
 *       {@value #POLAR_TEMPERATURE} °C and the summers are too cool for trees, so the ground is white most of the
 *       year. Vanilla's frozen band has snowy taigas there, and taigas where it is wettest: they become snowy plains,
 *       and its slopes and hills snowy slopes. Its other snowy biomes and its peaks stay.</li>
 *   <li><b>Salt flat</b>: the flattest desert floors, with less than {@value #SALT_FLAT_RELIEF} m of relief, up on
 *       the cold Altiplano too.</li>
 *   <li><b>Páramo</b> and <b>puna</b>: tropical mountains from {@value #TROPICAL_ALPINE_ELEVATION} m where the year
 *       averages below {@value #TROPICAL_TREE_LINE} °C. Every month is a growing season there, so the tree line follows
 *       the year rather than the summer, and the cloud forest below gives way to them. Wet ones, with at least
 *       {@value #PARAMO_ARIDITY} times the rain they need, are páramo, as in the northern Andes or on Mount Kenya; dry
 *       ones puna, as on the Altiplano or Mauna Kea.</li>
 *   <li><b>Tundra</b> and <b>alpine tundra</b>: beyond the tree line, where the warmest month stays below
 *       {@value #TREE_LINE} °C; alpine on mountains. This is what moves the snow line above the tree line.</li>
 *   <li>Meadows keep to the subalpine belt, where the warmest month stays below {@value #SUBALPINE_WARMEST} °C, and
 *       to ground too dry for a closed forest. Lower down, on a humid mountainside, vanilla's meadow becomes the
 *       forest it puts on the lowlands beside it, so the woods no longer break into strips of meadow along every
 *       valley side.</li>
 *   <li><b>Monsoon forest</b>: frost-free lowlands (coldest month from {@value #TROPICAL_COLDEST} °C) where vanilla
 *       puts a temperate forest, and seasonal jungles (rain seasonality from {@value #MONSOON_SEASONALITY} %) with less
 *       than {@value #MONSOON_RAIN} mm of rain: the teak and sal forests of India and Southeast Asia, which shed their
 *       leaves in the dry season.</li>
 *   <li><b>Laurel forest</b>: jungles where the coldest month falls below {@value #TROPICAL_COLDEST} °C. Vanilla's
 *       jungles start at a yearly mean of 15 °C, which takes in the humid subtropics and their frosty winters: the
 *       evergreen oaks and laurels of southern China and Japan, and the live oaks and magnolias of the American
 *       Southeast.</li>
 *   <li>Bare summits stay bare, and the other jungles stay jungle.</li>
 *   <li><b>Moorland</b>: rolling uplands from {@value #MOOR_ELEVATION} m with cool summers (warmest month below
 *       {@value #MOOR_WARMEST} °C), mild winters and at least 800 mm of rain, as on Dartmoor or in the Pennines.</li>
 *   <li><b>Bog</b>: the flattest land with cool summers and at least twice the rain it needs, as in Finland, the
 *       West Siberian Plain or the Hudson Bay Lowlands, but not in the dry summers of a Mediterranean climate.
 *       Vanilla only puts swamps on low ground; bogs grow on flat land at any height below the mountains.</li>
 *   <li><b>Larch taiga</b>: where the coldest month falls below {@value #LARCH_COLDEST} °C, as in eastern Siberia.
 *       The ground stays frozen all year a metre down, and on the thin layer that thaws in summer the evergreen
 *       spruces give way to larches, which shed their needles for the winter. Vanilla puts its snowy plains and taigas
 *       (and ice spikes) there in the frozen band, below -5 °C, and its forests and taigas above it. Ground too dry
 *       for a forest keeps vanilla's pick. Larches grow in the coldest winters of all, at Verkhoyansk and Oymyakon,
 *       wherever the summers are warm enough for trees.</li>
 *   <li><b>Temperate rainforest</b>: mild oceanic climates (4 to 14 °C, coldest month above -3 °C) with at least
 *       1600 mm of rain.</li>
 *   <li><b>Maquis</b>: Mediterranean scrub, 12 to 21 °C with mild winters, 250 to 1000 mm of strongly seasonal
 *       rain.</li>
 *   <li><b>Heath</b>: patches of oceanic lowland, 6 to 12 °C with 550 to 1100 mm of rain, as on the Lüneburg
 *       Heath or in Jutland.</li>
 *   <li><b>Mixed forest</b>: vanilla's forests and taigas where the year averages {@value #MIXED_COOLEST} to
 *       {@value #MIXED_WARMEST} °C, the warmest month reaches {@value #OAK_SUMMER} °C and the coldest falls below
 *       {@value #MIXED_COLDEST} °C. Oaks, limes and birches grow among the spruces and pines there, as in southern
 *       Finland and Sweden, around Moscow, by the Great Lakes and in Manchuria. Vanilla draws its line between taiga
 *       and forest at 5 °C, through the middle of it. Milder winters favour the beech, and the forest is broadleaf, as
 *       in Denmark. On a humid mountainside the mixed forest also takes the meadow's place, as the beech and fir of the
 *       Vosges do.</li>
 *   <li><b>Cold desert</b>: deserts cooler than {@value #COLD_DESERT_TEMPERATURE} °C.</li>
 *   <li><b>Desert scrub</b>: the other deserts but the driest, which get less than {@value #SCRUB_ARIDITY} of the
 *       rain they would need to be steppe. Only those, as in the heart of the Sahara, are bare sand; in the others
 *       shrubs grow over gravel and stony ground, as in the Mojave, the Sonoran Desert and the Karoo.</li>
 *   <li><b>Steppe</b>: semi-arid grassland cooler than 18 °C.</li>
 * </ul>
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
	public static final String DESERT_SCRUB = "diffusicraft:desert_scrub";
	public static final String STEPPE = "diffusicraft:steppe";
	public static final String MOORLAND = "diffusicraft:moorland";
	public static final String HEATH = "diffusicraft:heath";
	public static final String SALT_FLAT = "diffusicraft:salt_flat";
	public static final String PARAMO = "diffusicraft:paramo";
	public static final String PUNA = "diffusicraft:puna";
	public static final String MONSOON_FOREST = "diffusicraft:monsoon_forest";
	public static final String LAUREL_FOREST = "diffusicraft:laurel_forest";
	public static final String BOG = "diffusicraft:bog";
	public static final String FJORD = "diffusicraft:fjord";
	public static final String LARCH_TAIGA = "diffusicraft:larch_taiga";
	public static final String MIXED_FOREST = "diffusicraft:mixed_forest";
	public static final String SWAMP = "minecraft:swamp";
	public static final String MANGROVE_SWAMP = "minecraft:mangrove_swamp";
	public static final String FOREST = "minecraft:forest";
	public static final String BIRCH_FOREST = "minecraft:birch_forest";
	public static final String TAIGA = "minecraft:taiga";
	public static final String SNOWY_PLAINS = "minecraft:snowy_plains";
	public static final String SNOWY_SLOPES = "minecraft:snowy_slopes";

	/** Every biome {@link #refine} can return. */
	public static final List<String> TARGETS = List.of(TUNDRA, ALPINE_TUNDRA, CLOUD_FOREST, TEMPERATE_RAINFOREST,
		MAQUIS, COLD_DESERT, STEPPE, MOORLAND, HEATH, SALT_FLAT, PARAMO, PUNA, MONSOON_FOREST, LAUREL_FOREST, BOG,
		FJORD, LARCH_TAIGA, DESERT_SCRUB, MIXED_FOREST, SWAMP, MANGROVE_SWAMP, FOREST, BIRCH_FOREST, TAIGA,
		SNOWY_PLAINS, SNOWY_SLOPES);

	/**
	 * The foot of the subalpine belt: where the warmest month passes this, °C, some 600 m below the tree line, a humid
	 * mountainside is closed forest. Above it the forest opens into meadows on its way up to the tree line.
	 */
	static final float SUBALPINE_WARMEST = 14f;
	/** Warmest month below which snow lies all year round, °C. */
	static final float ICE_WARMEST = 3f;
	/** Annual mean below which the ground is white most of the year where the summers are too cool for trees, °C. */
	static final float POLAR_TEMPERATURE = -10f;
	/** The tree line: no forest grows where the warmest month stays below this, °C. */
	static final float TREE_LINE = 10f;
	/** Seasonality below which a climate is tropical, °C: the months differ less than the days and nights. */
	static final float TROPICAL_SEASONALITY = 2.5f;
	/** Tropical mountains are alpine from this elevation, in meters, where they are cold enough. */
	static final float TROPICAL_ALPINE_ELEVATION = 2500f;
	/** The tropical tree line: no forest grows where the year averages below this, °C. */
	static final float TROPICAL_TREE_LINE = 10f;
	/** Precipitation over the aridity threshold above which tropical alpine land is páramo rather than puna. */
	static final float PARAMO_ARIDITY = 3f;
	/** Coldest month from which the lowlands are tropical, °C: frost never comes. */
	static final float TROPICAL_COLDEST = 15f;
	/** Precipitation seasonality (BIO15, %) from which a tropical forest sheds its leaves in the dry season. */
	static final float MONSOON_SEASONALITY = 80f;
	/** Rain, in mm, from which even a seasonal tropical forest stays evergreen. */
	static final float MONSOON_RAIN = 2000f;
	/** Land this rugged, in meters of relief, or this high, in meters, is mountainous. */
	static final float MOUNTAIN_RELIEF = 90f;
	static final float MOUNTAIN_ELEVATION = 1500f;
	/** Deserts cooler than this are cold deserts, °C. */
	static final float COLD_DESERT_TEMPERATURE = 12f;
	/**
	 * Precipitation over the aridity threshold below which a desert is hyperarid, bare sand and rock: about a
	 * twentieth of the water the sun could take back.
	 */
	static final float SCRUB_ARIDITY = 0.15f;
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
	/** Summers in a bog stay cooler than this, °C; warmer wetlands are swamps. */
	static final float BOG_WARMEST = 20f;
	/** Land flatter than this, in meters of relief, holds its water and grows a bog in a cool, wet climate. */
	static final float BOG_RELIEF = 6f;
	/** Precipitation over the aridity threshold that a bog needs: the wetland band's. */
	static final float BOG_ARIDITY = 2f;
	/**
	 * Heaths cover the part of the climate that suits them where the variant noise is below this: about a fifth of
	 * it, in patches among the woods, as people cleared them.
	 */
	static final float HEATH_VARIANT = -0.3f;
	/**
	 * Coldest month below which the ground is frozen all year round under a thin layer that thaws in summer, °C: the
	 * taiga there is larch.
	 */
	static final float LARCH_COLDEST = -25f;
	/** Annual mean from which oaks and limes join the spruces and pines of the taiga, °C. */
	static final float MIXED_COOLEST = 3.5f;
	/** Annual mean from which the broadleaf trees crowd out the conifers, °C. */
	static final float MIXED_WARMEST = 9f;
	/** The oak's northern limit: its acorns ripen only where the warmest month reaches this, °C. */
	static final float OAK_SUMMER = 16f;
	/** Coldest month the mixed forest falls below, °C: milder winters favour the beech. */
	static final float MIXED_COLDEST = -1f;
	/** Meters the ice must have lowered ground at sea level for a drowned valley to be a fjord, not a thin strip. */
	static final float FJORD_CUT = 120f;

	/** What a vanilla biome is, as far as the rules care. */
	enum Kind {
		/** Shores, rivers, caves, mushroom islands and the frozen and warm oceans: never refined. */
		FIXED,
		/** The temperate and cold oceans, deep or not. */
		SEA,
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
		kind("ocean", Kind.SEA), kind("deep_ocean", Kind.SEA), kind("cold_ocean", Kind.SEA),
		kind("deep_cold_ocean", Kind.SEA), fixed("frozen_ocean"),
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

	/** Vanilla's lowland woods that the mixed forest replaces. Its old-growth, dark and flower forests stay. */
	private static final Set<String> MIXED_WOODS = Set.of("minecraft:forest", "minecraft:birch_forest",
		"minecraft:taiga");

	/** Vanilla's open land, as opposed to its woods. */
	private static final Set<String> OPEN_LAND = Set.of("minecraft:plains", "minecraft:sunflower_plains",
		"minecraft:savanna", "minecraft:savanna_plateau");

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
		if (kind == Kind.SEA) {
			return fjord(c) ? FJORD : null;
		}
		if (kind == Kind.WETLAND) {
			String wetland = wetland(c);
			return wetland.equals(biome) ? null : wetland;
		}
		boolean dry = kind == Kind.DESERT || kind == Kind.BADLANDS;
		if (!dry && kind != Kind.PEAK && cloudForest(c)) {
			return CLOUD_FOREST;
		}
		float warmest = c.warmestMonth();
		if (warmest < ICE_WARMEST || c.temperature < POLAR_TEMPERATURE && warmest < TREE_LINE) {
			return snow(biome, kind);
		}
		float aridity = c.aridity();
		if ((kind == Kind.DESERT || kind == Kind.LAND) && saltFlat(c, aridity)) {
			return SALT_FLAT;
		}
		boolean mountain = kind == Kind.PEAK || kind == Kind.SLOPE || kind == Kind.HILLS
			|| c.relief >= MOUNTAIN_RELIEF || c.elevation >= MOUNTAIN_ELEVATION;
		if (tropicalAlpine(c)) {
			return aridity >= PARAMO_ARIDITY ? PARAMO : PUNA;
		}
		if (warmest < TREE_LINE) {
			return mountain ? ALPINE_TUNDRA : TUNDRA;
		}
		if (monsoonForest(biome, kind, c, aridity)) {
			return MONSOON_FOREST;
		}
		if (kind == Kind.JUNGLE && c.coldestMonth() < TROPICAL_COLDEST) {
			return LAUREL_FOREST;
		}
		if (kind == Kind.PEAK || kind == Kind.JUNGLE || kind == Kind.BADLANDS) {
			return null;
		}
		if (kind == Kind.SNOWY) {
			return larchTaiga(c, aridity) ? LARCH_TAIGA : null;
		}
		boolean meadow = biome.equals("minecraft:meadow");
		if ((kind == Kind.LAND || kind == Kind.HILLS || meadow) && moorland(c)) {
			return MOORLAND;
		}
		if (kind == Kind.LAND && bog(c, aridity)) {
			return BOG;
		}
		if (kind == Kind.LAND && larchTaiga(c, aridity)) {
			return LARCH_TAIGA;
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
		if (meadow && aridity >= 1f && warmest >= SUBALPINE_WARMEST) {
			return montaneForest(c, aridity);
		}
		if (kind == Kind.LAND && heath(c)) {
			return HEATH;
		}
		if (MIXED_WOODS.contains(biome) && aridity >= 1f && mixedForest(c)) {
			return MIXED_FOREST;
		}
		if (kind == Kind.LAND || kind == Kind.DESERT) {
			if (aridity < 0.5f && c.temperature < COLD_DESERT_TEMPERATURE) {
				return COLD_DESERT;
			}
			if (kind == Kind.DESERT && aridity >= SCRUB_ARIDITY) {
				return DESERT_SCRUB;
			}
			if (kind == Kind.LAND && aridity < 1f && c.temperature < 18f) {
				return STEPPE;
			}
		}
		return null;
	}

	/**
	 * The woods of vanilla's lowland row in the band and humidity where its plateau row has a meadow: forest, and where
	 * there is twice the rain the ground needs (vanilla's wetter half), birch forest, or taiga in the band below 5 °C.
	 * In the mixed forest's climate, mixed forest.
	 */
	private static String montaneForest(BiomeClimate c, float aridity) {
		if (mixedForest(c)) {
			return MIXED_FOREST;
		}
		if (aridity < 2f) {
			return FOREST;
		}
		return c.temperature < 5f ? TAIGA : BIRCH_FOREST;
	}

	/**
	 * Vanilla's snow for polar ground, where it may have picked a forest: its wettest frozen band holds snowy taigas and
	 * taigas, and the band above it forests and slopes of spruce.
	 */
	private static @Nullable String snow(String biome, Kind kind) {
		return switch (kind) {
			case LAND -> SNOWY_PLAINS;
			case SNOWY -> biome.equals("minecraft:snowy_taiga") ? SNOWY_PLAINS : null;
			case SLOPE, HILLS -> biome.equals(SNOWY_SLOPES) ? null : SNOWY_SLOPES;
			default -> null;
		};
	}

	/** The wetland that vanilla's swamp band holds in this climate. */
	static String wetland(BiomeClimate c) {
		if (c.coldestMonth() >= MANGROVE_COLDEST && c.elevation < MANGROVE_ELEVATION) {
			return MANGROVE_SWAMP;
		}
		if (tropicalAlpine(c)) {
			// The páramo is a land of bogs and lakes.
			return PARAMO;
		}
		float warmest = c.warmestMonth();
		if (warmest < TREE_LINE) {
			return TUNDRA;
		}
		return warmest < BOG_WARMEST && !maquis(c) ? BOG : SWAMP;
	}

	static boolean fjord(BiomeClimate c) {
		return c.iceCut >= FJORD_CUT && Glaciation.drowned(c.elevation, c.iceCut);
	}

	/** Peat grows only where the ground stays wet all summer, so a Mediterranean climate, where maquis grows, has none. */
	static boolean bog(BiomeClimate c, float aridity) {
		return c.relief < BOG_RELIEF && c.elevation < MOUNTAIN_ELEVATION && c.warmestMonth() < BOG_WARMEST
			&& aridity >= BOG_ARIDITY && !maquis(c);
	}

	/** The summers are warm enough for trees wherever this is asked. */
	static boolean larchTaiga(BiomeClimate c, float aridity) {
		return c.coldestMonth() < LARCH_COLDEST && aridity >= 1f;
	}

	/** Whether the temperatures are the mixed forest's. Its callers check that the ground is humid enough for woods. */
	static boolean mixedForest(BiomeClimate c) {
		return c.temperature >= MIXED_COOLEST && c.temperature < MIXED_WARMEST && c.warmestMonth() >= OAK_SUMMER
			&& c.coldestMonth() < MIXED_COLDEST;
	}

	static boolean cloudForest(BiomeClimate c) {
		return c.seasonality < TROPICAL_SEASONALITY && c.elevation >= 1000f && c.elevation <= 3500f
			&& c.temperature >= 10f && c.temperature <= 20f && c.precipitation >= 1500f;
	}

	static boolean tropicalAlpine(BiomeClimate c) {
		return c.seasonality < TROPICAL_SEASONALITY && c.elevation >= TROPICAL_ALPINE_ELEVATION
			&& c.temperature < TROPICAL_TREE_LINE;
	}

	/**
	 * Vanilla's table only has temperate woods for tropical land between its savannas and its jungles, and only
	 * jungles for wetter land, whatever the seasons.
	 */
	static boolean monsoonForest(String biome, Kind kind, BiomeClimate c, float aridity) {
		if (c.coldestMonth() < TROPICAL_COLDEST || aridity < 1f) {
			return false;
		}
		if (kind == Kind.JUNGLE) {
			return c.precipitationSeasonality >= MONSOON_SEASONALITY && c.precipitation < MONSOON_RAIN;
		}
		return kind == Kind.LAND && !OPEN_LAND.contains(biome);
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
