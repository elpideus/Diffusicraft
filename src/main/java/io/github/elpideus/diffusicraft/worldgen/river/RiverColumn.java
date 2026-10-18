package io.github.elpideus.diffusicraft.worldgen.river;

/**
 * What the rivers do to one block column, from {@link Rivers#column}. Heights are block Y coordinates.
 *
 * <p>Where {@link #river} is set, the ground between {@link #bandBottom} and {@link #surface} is made solid and
 * everything above {@link #surface} is cut away. Channel columns then hold water from {@link #bed} (exclusive) up to
 * {@link #waterTop}, on a bed of sand, gravel or clay. A lake is a channel too, flagged {@link #lake}.
 */
public final class RiverColumn {
	/** Whether a river or its banks reshape this column. */
	public boolean river;
	/** Height the ground is cut down or built up to: the top solid block is the one below it. */
	public float surface;
	/** Lowest height the ground is built up from where it lies below {@link #surface}. */
	public float bandBottom;
	/** Whether the column is under the river's water, or a lake's. */
	public boolean channel;
	/** Whether the water is a lake's rather than a river's. */
	public boolean lake;
	/** Highest water block. */
	public int waterTop;
	/** Highest block of the river bed, right under the water. */
	public int bed;
	/** Water level in meters. */
	public float level;
	/** Discharge in m³/s. */
	public float discharge;
	/** Whether the column belongs to the river's valley, which takes a river biome. */
	public boolean valley;

	void clear() {
		river = false;
		channel = false;
		lake = false;
		valley = false;
	}

	void set(RiverColumn other) {
		river = other.river;
		surface = other.surface;
		bandBottom = other.bandBottom;
		channel = other.channel;
		lake = other.lake;
		waterTop = other.waterTop;
		bed = other.bed;
		level = other.level;
		discharge = other.discharge;
		valley = other.valley;
	}
}
