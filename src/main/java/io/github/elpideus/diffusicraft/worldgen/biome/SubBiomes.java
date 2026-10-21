package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.ANCIENT_WOODLAND;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.BOG;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.FOREST_STEPPE;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.JUNIPER_WOODLAND;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.OLD_GROWTH_JUNGLE;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.REDWOOD_FOREST;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.SHRUB_STEPPE;
import static io.github.elpideus.diffusicraft.worldgen.biome.BiomeRefiner.STEPPE;

/**
 * Sub-biomes: patches of a biome's siblings inside it, a few hundred blocks across, as real land varies within one
 * climate. The climate picks the biome over tens or hundreds of kilometres, so at the larger sizes one forest could run
 * on for days of walking. Real woods break into birch stands, glades, bogs and old growth over a few hundred meters,
 * with the soil, the water and the last fire.
 *
 * <p>The world is divided into cells {@value #CELL} blocks wide, each holding one patch around a point placed at
 * random in it: a blob of a radius of its own, its edge frayed by noise. A column belongs to the patch it lies deepest
 * in, so patches that meet do so along a curve. Each patch draws a number, which the {@link #FAMILIES family} of the
 * column's biome turns into a sibling, or into nothing, which leaves the biome its own. Patches cover about half the
 * land, and on level ground a fifth of a forest is other woods and glades, in the same climate.
 *
 * <p>Some siblings keep to their ground, and their patches shrink back from ground that does not suit them: bogs to
 * flat hollows, dense old woods and groves on the plains to the moister valleys, pines and open scrub to the drier
 * rises, and the groves of giant trees to level land. The ground is the column's height over the land around in units
 * of its relief, as vanilla's peaks and valleys have it. Siblings that need a climate of their own, such as bogs,
 * which need cool, wet summers, come only where it holds.
 *
 * <p>The groves of giant trees, {@link BiomeRefiner#REDWOOD_FOREST redwoods}, {@link BiomeRefiner#ANCIENT_WOODLAND
 * ancient oaks} and {@link BiomeRefiner#OLD_GROWTH_JUNGLE kapoks}, come only as siblings: rare patches of the forests
 * and plains around them, rather than biomes of a climate of their own.
 *
 * <p>The cells are the same size in blocks at every world size. Only the biomes in {@link #FAMILIES} vary, so the
 * biomes of other mods, rivers, shores and seas stay as they are. Pure and static, so it is unit-tested without a
 * world.
 */
public final class SubBiomes {
	/** Side of the cells, in blocks. */
	static final double CELL = 320;
	/** The share of the cell over which its patch's centre may lie, about the middle. */
	private static final double JITTER = 0.8;
	/** The radius of a patch, as a share of the cell, at least and at most. */
	private static final double RADIUS_MIN = 0.28;
	private static final double RADIUS_MAX = 0.5;
	/** Wavelength of the noise that frays the patches' edges, in blocks. */
	private static final double EDGE_WAVELENGTH = 96;
	/** How far that noise moves an edge, as a share of the patch's radius. */
	private static final double EDGE = 0.6;
	private static final int EDGE_OCTAVES = 3;
	/** Relief below which the height over the land around stops counting more, in meters: vanilla's. */
	private static final float LEAST_RELIEF = 15f;
	/** How much of its depth a patch loses per unit of height over, or under, the ground it keeps to. */
	private static final float GROUND = 0.8f;
	/** Relief from which a bog's patch shrinks, in meters, and over how many more it is gone. */
	private static final float WET_RELIEF = 10f;
	private static final float WET_RELIEF_FADE = 15f;
	/** Relief from which a grove of giants shrinks, in meters, and over how many more it is gone. */
	private static final float FLAT_RELIEF = 25f;
	private static final float FLAT_RELIEF_FADE = 35f;

	/** The ground a sibling keeps to. */
	enum Ground {
		/** Any ground. */
		ANY,
		/** The lower half of the land: valleys and hollows, where the water gathers. */
		LOW,
		/** The upper half: rises and ridges, which dry out first. */
		HIGH,
		/** Flat hollows, where the water stands. */
		WET,
		/** Level land, where giant trees stand. */
		FLAT;

		/** How much depth a patch of this ground loses in a column. */
		float penalty(BiomeClimate c) {
			float height = (c.elevation - c.meanElevation) / Math.max(c.relief, LEAST_RELIEF);
			return switch (this) {
				case ANY -> 0f;
				case LOW -> GROUND * Math.max(0f, height);
				case HIGH -> GROUND * Math.max(0f, -height);
				case WET -> GROUND * Math.max(0f, height) + Math.max(0f, c.relief - WET_RELIEF) / WET_RELIEF_FADE;
				case FLAT -> Math.max(0f, c.relief - FLAT_RELIEF) / FLAT_RELIEF_FADE;
			};
		}
	}

	/** A sibling: the share of the patches it takes, the ground it keeps to and the climate it needs, if any. */
	record Sibling(float share, String biome, Ground ground, @Nullable Predicate<BiomeClimate> climate) {
	}

	/** Where peat grows in a hollow: cool summers and enough rain, but not the dry summers of the maquis. */
	private static final Predicate<BiomeClimate> PEAT = c -> c.warmestMonth() < BiomeRefiner.BOG_WARMEST
		&& c.aridity() >= 1f && c.elevation < BiomeRefiner.MOUNTAIN_ELEVATION && !BiomeRefiner.maquis(c);
	/** Where trees grow, a little below the tree line. */
	private static final Predicate<BiomeClimate> TREES = c -> c.warmestMonth() >= BiomeRefiner.TREE_LINE + 1f
		&& c.aridity() >= 1f;
	/** Beyond the tree line. */
	private static final Predicate<BiomeClimate> TREELESS = c -> c.warmestMonth() < BiomeRefiner.TREE_LINE;
	/** Frost-free and humid: the climate of the monsoon forest. */
	private static final Predicate<BiomeClimate> TROPICAL = c -> c.coldestMonth() >= BiomeRefiner.TROPICAL_COLDEST
		&& c.aridity() >= 1f;
	/** Rain enough for an evergreen forest in a valley. */
	private static final Predicate<BiomeClimate> EVERGREEN = c -> c.precipitation >= 1500f;
	private static final Predicate<BiomeClimate> JUNIPERS = c -> BiomeRefiner.juniperWoodland(c, c.aridity());
	private static final Predicate<BiomeClimate> SHRUBS = c -> BiomeRefiner.shrubSteppe(c, c.aridity());
	private static final Predicate<BiomeClimate> GRASS = SHRUBS.negate();
	/** Mild and wet enough for redwoods: not the hard winters nor the dry summers of the inland taiga. */
	private static final Predicate<BiomeClimate> REDWOODS = c -> c.coldestMonth() >= -12f && c.precipitation >= 700f;

	/** Each biome's siblings, in the order their shares are counted. */
	static final Map<String, List<Sibling>> FAMILIES = Map.ofEntries(
		// Temperate woods: birch stands, glades in bloom, dark old woods in the moist valleys, light woods on the rises.
		family("minecraft:forest",
			of(0.16f, "minecraft:birch_forest"),
			of(0.08f, "minecraft:flower_forest"),
			of(0.08f, "minecraft:plains"),
			of(0.08f, "minecraft:dark_forest", Ground.LOW),
			of(0.06f, "minecraft:dappled_forest", Ground.HIGH),
			of(0.04f, "minecraft:old_growth_birch_forest"),
			of(0.05f, ANCIENT_WOODLAND, Ground.FLAT)),
		family("minecraft:birch_forest",
			of(0.14f, "minecraft:forest"),
			of(0.10f, "minecraft:old_growth_birch_forest"),
			of(0.06f, "minecraft:flower_forest"),
			of(0.06f, "minecraft:plains"),
			of(0.03f, ANCIENT_WOODLAND, Ground.FLAT)),
		family("minecraft:old_growth_birch_forest",
			of(0.16f, "minecraft:birch_forest"),
			of(0.06f, "minecraft:flower_forest")),
		family("minecraft:dark_forest",
			of(0.14f, "minecraft:forest", Ground.HIGH),
			of(0.06f, "minecraft:birch_forest", Ground.HIGH),
			of(0.04f, "minecraft:pale_garden", Ground.LOW),
			of(0.06f, ANCIENT_WOODLAND, Ground.FLAT)),
		family("minecraft:pale_garden",
			of(0.16f, "minecraft:dark_forest")),
		family("minecraft:flower_forest",
			of(0.14f, "minecraft:forest"),
			of(0.06f, "minecraft:birch_forest"),
			of(0.08f, "minecraft:plains"),
			of(0.04f, ANCIENT_WOODLAND, Ground.FLAT)),
		family("minecraft:dappled_forest",
			of(0.10f, "minecraft:forest", Ground.LOW),
			of(0.10f, "minecraft:plains"),
			of(0.05f, "minecraft:birch_forest")),
		// Meadows: sunflowers and flowers, groves where the water gathers, along streams and in the hollows, and here
		// and there the ancient oaks of an old wood pasture.
		family("minecraft:plains",
			of(0.12f, "minecraft:sunflower_plains"),
			of(0.12f, "minecraft:forest", Ground.LOW),
			of(0.04f, "minecraft:birch_forest", Ground.LOW),
			of(0.05f, "minecraft:flower_forest"),
			of(0.05f, "minecraft:dappled_forest"),
			of(0.06f, ANCIENT_WOODLAND, Ground.FLAT)),
		family("minecraft:sunflower_plains",
			of(0.16f, "minecraft:plains"),
			of(0.08f, "minecraft:forest", Ground.LOW),
			of(0.04f, ANCIENT_WOODLAND, Ground.FLAT)),
		family(ANCIENT_WOODLAND,
			of(0.14f, "minecraft:forest"),
			of(0.06f, "minecraft:flower_forest"),
			of(0.06f, "minecraft:plains")),
		// The boreal forest: old spruce in the moist valleys, pine on the dry rises, birch where fire has been, muskeg,
		// and redwoods on the level land of its milder, wetter edges.
		family("minecraft:taiga",
			of(0.10f, "minecraft:old_growth_spruce_taiga", Ground.LOW),
			of(0.10f, "minecraft:old_growth_pine_taiga", Ground.HIGH),
			of(0.08f, "minecraft:birch_forest"),
			of(0.10f, BOG, Ground.WET, PEAT),
			of(0.04f, REDWOOD_FOREST, Ground.FLAT, REDWOODS)),
		family("minecraft:old_growth_spruce_taiga",
			of(0.14f, "minecraft:taiga", Ground.HIGH),
			of(0.06f, BOG, Ground.WET, PEAT),
			of(0.08f, REDWOOD_FOREST, Ground.FLAT, REDWOODS)),
		family("minecraft:old_growth_pine_taiga",
			of(0.14f, "minecraft:taiga"),
			of(0.06f, "minecraft:old_growth_spruce_taiga", Ground.LOW),
			of(0.06f, REDWOOD_FOREST, Ground.FLAT, REDWOODS)),
		family(REDWOOD_FOREST,
			of(0.12f, "minecraft:old_growth_spruce_taiga"),
			of(0.06f, "minecraft:taiga", Ground.HIGH)),
		family("minecraft:snowy_taiga",
			of(0.14f, "minecraft:snowy_plains", Ground.HIGH)),
		family("minecraft:snowy_plains",
			of(0.10f, "minecraft:snowy_taiga", Ground.LOW, TREES),
			of(0.03f, "minecraft:ice_spikes", Ground.ANY, TREELESS)),
		family("minecraft:ice_spikes",
			of(0.20f, "minecraft:snowy_plains")),
		// Tree islands in the subalpine meadows, where the snow melts first and the wind is broken.
		family("minecraft:meadow",
			of(0.08f, "minecraft:taiga", Ground.LOW, TREES)),
		// The jungle: thinner on the rises, bamboo in the valleys, and the kapoks of the old growth on level land.
		family("minecraft:jungle",
			of(0.10f, "minecraft:sparse_jungle", Ground.HIGH),
			of(0.10f, "minecraft:bamboo_jungle", Ground.LOW),
			of(0.10f, OLD_GROWTH_JUNGLE, Ground.FLAT)),
		family("minecraft:sparse_jungle",
			of(0.14f, "minecraft:jungle", Ground.LOW),
			of(0.04f, "minecraft:bamboo_jungle", Ground.LOW),
			of(0.04f, OLD_GROWTH_JUNGLE, Ground.LOW)),
		family("minecraft:bamboo_jungle",
			of(0.18f, "minecraft:jungle"),
			of(0.06f, OLD_GROWTH_JUNGLE, Ground.FLAT)),
		family(OLD_GROWTH_JUNGLE,
			of(0.12f, "minecraft:jungle"),
			of(0.06f, "minecraft:bamboo_jungle", Ground.LOW)),
		// Islands of dry forest and gallery forests along the streams, where the fires do not reach.
		family("minecraft:savanna",
			of(0.08f, BiomeRefiner.MONSOON_FOREST, Ground.LOW, TROPICAL),
			of(0.04f, "minecraft:sparse_jungle", Ground.LOW, TROPICAL)),
		family("minecraft:desert",
			of(0.06f, BiomeRefiner.STONY_DESERT, Ground.HIGH)),
		family(BiomeRefiner.DESERT_SCRUB,
			of(0.10f, "minecraft:desert", Ground.LOW),
			of(0.06f, BiomeRefiner.STONY_DESERT, Ground.HIGH)),
		family(BiomeRefiner.MIXED_FOREST,
			of(0.10f, "minecraft:taiga", Ground.HIGH),
			of(0.10f, "minecraft:birch_forest"),
			of(0.10f, "minecraft:forest", Ground.LOW),
			of(0.05f, BOG, Ground.WET, PEAT),
			of(0.03f, "minecraft:flower_forest"),
			of(0.04f, REDWOOD_FOREST, Ground.FLAT, REDWOODS)),
		family(BiomeRefiner.LARCH_TAIGA,
			of(0.08f, BOG, Ground.WET, PEAT),
			of(0.06f, "minecraft:taiga", Ground.LOW),
			of(0.05f, "minecraft:birch_forest")),
		// Open tundra on the exposed rises, closed spruce in the sheltered valleys.
		family(BiomeRefiner.FOREST_TUNDRA,
			of(0.14f, BiomeRefiner.TUNDRA, Ground.HIGH),
			of(0.08f, "minecraft:taiga", Ground.LOW),
			of(0.06f, BOG, Ground.WET, PEAT)),
		family(BiomeRefiner.TEMPERATE_RAINFOREST,
			of(0.06f, BOG, Ground.WET, PEAT),
			of(0.14f, REDWOOD_FOREST, Ground.FLAT)),
		// Woods in the ravines of the steppe, as in its balkas, and shrubs on its dry rises.
		family(STEPPE,
			of(0.08f, FOREST_STEPPE, Ground.LOW),
			of(0.08f, SHRUB_STEPPE, Ground.HIGH)),
		family(FOREST_STEPPE,
			of(0.12f, STEPPE, Ground.HIGH),
			of(0.06f, "minecraft:forest", Ground.LOW),
			of(0.06f, "minecraft:birch_forest")),
		family(SHRUB_STEPPE,
			of(0.10f, STEPPE, Ground.LOW),
			of(0.06f, JUNIPER_WOODLAND, Ground.HIGH, JUNIPERS)),
		family(JUNIPER_WOODLAND,
			of(0.10f, STEPPE, Ground.LOW, GRASS),
			of(0.10f, SHRUB_STEPPE, Ground.LOW, SHRUBS)),
		// Blanket bog where the moor is flat.
		family(BiomeRefiner.MOORLAND,
			of(0.14f, BOG, Ground.WET, PEAT)),
		family(BOG,
			of(0.10f, "minecraft:taiga", Ground.HIGH)),
		family(BiomeRefiner.MONSOON_FOREST,
			of(0.08f, "minecraft:bamboo_jungle", Ground.LOW),
			of(0.08f, BiomeRefiner.SAVANNA, Ground.HIGH),
			of(0.05f, "minecraft:jungle", Ground.LOW, EVERGREEN)),
		// Bamboo groves, and cherries on the hills.
		family(BiomeRefiner.LAUREL_FOREST,
			of(0.10f, "minecraft:bamboo_jungle"),
			of(0.04f, "minecraft:cherry_grove", Ground.HIGH)));

	private SubBiomes() {
	}

	private static Map.Entry<String, List<Sibling>> family(String biome, Sibling... siblings) {
		return Map.entry(biome, List.of(siblings));
	}

	private static Sibling of(float share, String biome) {
		return of(share, biome, Ground.ANY);
	}

	private static Sibling of(float share, String biome, Ground ground) {
		return new Sibling(share, biome, ground, null);
	}

	private static Sibling of(float share, String biome, Ground ground, Predicate<BiomeClimate> climate) {
		return new Sibling(share, biome, ground, climate);
	}

	/** Every biome {@link #vary} can return. */
	public static List<String> targets() {
		return FAMILIES.values().stream().flatMap(List::stream).map(Sibling::biome).distinct().sorted().toList();
	}

	/** Finds the patch that the column at block {@code (x, z)} lies in, for {@link #vary}. */
	public static void locate(long seed, int x, int z, BiomeClimate c) {
		long cells = GeoNoise.hash(seed, 0x53554242, 0x494F4D45, 0);
		double u = x / CELL, v = z / CELL;
		int cu = (int) Math.floor(u), cv = (int) Math.floor(v);
		double deepest = Double.NEGATIVE_INFINITY;
		long patch = 0;
		for (int du = -1; du <= 1; du++) {
			for (int dv = -1; dv <= 1; dv++) {
				long h = GeoNoise.hash(cells, cu + du, cv + dv, 0);
				double pu = cu + du + 0.5 + JITTER * (GeoNoise.unit(h) - 0.5);
				double pv = cv + dv + 0.5 + JITTER * (GeoNoise.unit2(h) - 0.5);
				double radius = RADIUS_MIN + (RADIUS_MAX - RADIUS_MIN) * GeoNoise.unit(GeoNoise.hash(h, 1, 0, 0));
				double depth = 1 - Math.hypot(u - pu, v - pv) / radius;
				if (depth > deepest) {
					deepest = depth;
					patch = h;
				}
			}
		}
		// A patch reaches at most RADIUS_MAX (1 + EDGE) cells from its centre, so one in a farther cell never would.
		double edge = EDGE * GeoNoise.fractal(cells + 1, x / EDGE_WAVELENGTH, z / EDGE_WAVELENGTH, EDGE_OCTAVES);
		c.patchDepth = (float) (deepest + edge);
		c.patch = (float) GeoNoise.unit(GeoNoise.hash(patch, 2, 0, 0));
	}

	/**
	 * The sibling that replaces {@code biome} in a column {@link #locate located} in its patch, or {@code null} to keep
	 * it.
	 */
	public static @Nullable String vary(String biome, BiomeClimate c) {
		if (c.patchDepth <= 0f) {
			return null;
		}
		List<Sibling> siblings = FAMILIES.get(biome);
		if (siblings == null) {
			return null;
		}
		float from = 0f;
		for (Sibling sibling : siblings) {
			from += sibling.share;
			if (c.patch < from) {
				boolean fits = c.patchDepth > sibling.ground.penalty(c)
					&& (sibling.climate == null || sibling.climate.test(c));
				return fits ? sibling.biome : null;
			}
		}
		return null;
	}
}
