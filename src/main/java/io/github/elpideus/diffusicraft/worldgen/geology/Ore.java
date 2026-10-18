package io.github.elpideus.diffusicraft.worldgen.geology;

/**
 * Ore bodies the {@link Geology} model places on top of vanilla's scattered ores. Each body is an ellipsoid inside one
 * cell of a 3D grid; its size and how much of it is ore depend on the mineral.
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
	CINNABAR(1.5, 3.0, 2.0, 0.30);

	/** Smallest and largest horizontal radius of a body, in blocks. */
	final double minRadius;
	final double maxRadius;
	/** Largest vertical radius, in blocks. */
	final double maxHeight;
	/** Share of the blocks at a body's centre that are ore; it falls off towards the edge. */
	final double grade;

	Ore(double minRadius, double maxRadius, double maxHeight, double grade) {
		this.minRadius = minRadius;
		this.maxRadius = maxRadius;
		this.maxHeight = maxHeight;
		this.grade = grade;
	}
}
