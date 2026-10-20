package io.github.elpideus.diffusicraft.client.compat.voxy;

/**
 * The heights Voxy can hold. Voxy keys its sections by level and position and packs the height into 8 signed bits at
 * every level, so its finest sections, 32 blocks tall, reach from y = {@value #MIN_Y} to y = {@value #MAX_Y}, and a
 * section above or below wraps round to the other end. Diffusicraft's taller levels (Earth 1:1 and up to 32,736 blocks;
 * see docs/HEIGHT.md) are cut to that range in Voxy: its far terrain stops there, as that of Distant Horizons does.
 */
public final class VoxyHeights {
	/** Lowest block Voxy holds. */
	public static final int MIN_Y = -4096;
	/** Highest block Voxy holds. */
	public static final int MAX_Y = 4095;
	/** Range of Voxy's coarsest sections, 512 blocks tall, that lie within those heights. */
	public static final int MIN_TOP_SECTION = MIN_Y >> 9;
	public static final int MAX_TOP_SECTION = MAX_Y >> 9;

	private VoxyHeights() {
	}

	/** Whether Voxy holds the 16-block chunk section at height {@code sectionY}. */
	public static boolean holdsSection(int sectionY) {
		return sectionY >= MIN_Y >> 4 && sectionY <= MAX_Y >> 4;
	}
}
