package io.github.elpideus.diffusicraft.worldgen.geology;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ore bodies the {@link Geology} model places on top of vanilla's scattered ores. Each body is an ellipsoid inside one
 * cell of a 3D grid; its size and how much of it is ore depend on the mineral.
 *
 * <p>The minerals after {@link #CINNABAR} are those of tech mods such as Create, Tech Reborn or Mekanism. Diffusicraft
 * adds no blocks for them: it places the ore a mod adds under the common tag {@code c:ores/<name>}, for one of the
 * {@link #tags} names, and places none where no mod adds one.
 */
public enum Ore {
	COAL(0, 0, 0, 0.65),
	IRON(2.5, 4.5, 2.5, 0.35),
	COPPER(3.0, 5.0, 3.0, 0.22),
	GOLD(1.5, 3.0, 2.0, 0.16),
	REDSTONE(2.0, 3.5, 2.5, 0.32),
	LAPIS(2.0, 3.0, 2.0, 0.24),
	EMERALD(1.0, 2.0, 1.5, 0.12),
	DIAMOND(1.0, 2.0, 1.5, 0.10),
	/** Vanilla's sulfur block, around volcanic vents and above the magma layer. */
	SULFUR(2.5, 4.5, 3.0, 0.50),
	/** Vanilla's cinnabar, the mercury ore of hot springs and volcanic rock. */
	CINNABAR(1.5, 3.0, 2.0, 0.30),
	/** Sphalerite, Create's zinc: with lead in limestone and shale, and in volcanic rock. */
	ZINC(2.5, 4.5, 2.5, 0.28, "zinc", "sphalerite"),
	/** Galena, with zinc. */
	LEAD(2.0, 4.0, 2.5, 0.25, "lead", "galena"),
	/** In the veins of volcanic rock, and with lead. */
	SILVER(1.5, 3.0, 2.0, 0.18, "silver"),
	/** Cassiterite, in the veins at the top of granite. */
	TIN(2.0, 3.5, 3.0, 0.22, "tin", "cassiterite"),
	/** With tin in granite, and in schist. */
	TUNGSTEN(1.5, 2.5, 2.0, 0.16, "tungsten", "wolframite", "scheelite"),
	/** The sulfides of the mafic rocks: gabbro and peridotite, and the deepslate. */
	NICKEL(2.5, 4.5, 2.5, 0.28, "nickel", "pentlandite"),
	/** In sandstone where groundwater left it, in black shale and in granite. */
	URANIUM(1.5, 3.0, 2.0, 0.16, "uranium", "uraninite"),
	/**
	 * The platinum metals of the mafic rocks: platinum, osmium and iridium. Platinum's ore is cooperite, which Tech
	 * Reborn calls by its old name, sheldonite.
	 */
	PLATINUM(1.0, 2.5, 1.5, 0.14, "platinum", "cooperite", "sheldonite"),
	OSMIUM(2.0, 3.5, 2.0, 0.22, "osmium"),
	IRIDIUM(1.0, 2.0, 1.5, 0.10, "iridium"),
	/** With zinc and lead in limestone, and in granite. */
	FLUORITE(1.5, 3.0, 2.0, 0.25, "fluorite"),
	/** Rubies grow in marble, sapphires in schist and gneiss, peridot in the peridotite it is named after. */
	RUBY(1.0, 2.0, 1.5, 0.12, "ruby"),
	SAPPHIRE(1.0, 2.0, 1.5, 0.12, "sapphire"),
	PERIDOT(1.0, 2.5, 1.5, 0.14, "peridot"),
	/**
	 * Bauxite, the aluminium ore. It is no body in the rock but a layer of the laterite that the wet tropics weather
	 * out of any rock (see {@link Soils#bauxite}); {@link #grade} is its share of that layer.
	 */
	ALUMINUM(0, 0, 0, 0.45, "aluminum", "aluminium", "bauxite");

	/** The ores Minecraft has, which are always there. */
	public static final Set<Ore> VANILLA = Collections.unmodifiableSet(EnumSet.range(COAL, CINNABAR));

	/** Smallest and largest horizontal radius of a body, in blocks. */
	final double minRadius;
	final double maxRadius;
	/** Largest vertical radius, in blocks. */
	final double maxHeight;
	/** Share of the blocks at a body's centre that are ore; it falls off towards the edge. */
	final double grade;
	private final List<String> tags;

	Ore(double minRadius, double maxRadius, double maxHeight, double grade, String... tags) {
		this.minRadius = minRadius;
		this.maxRadius = maxRadius;
		this.maxHeight = maxHeight;
		this.grade = grade;
		this.tags = List.of(tags);
	}

	/**
	 * The names under which mods tag a mineral's ores, {@code c:ores/<name>}, the metal's first: some mods name their
	 * ores after the mineral rather than the metal. Empty for vanilla's ores.
	 */
	public List<String> tags() {
		return tags;
	}
}
