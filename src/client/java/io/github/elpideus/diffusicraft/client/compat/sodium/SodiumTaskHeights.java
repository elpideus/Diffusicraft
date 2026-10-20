package io.github.elpideus.diffusicraft.client.compat.sodium;

/**
 * The heights Sodium's build tasks count from. Sodium packs the position of each section waiting to be built into 10
 * bits a coordinate and counts heights from section −128 (y = −2048), so it reaches up to y = 14,335; a section
 * outside that range comes back at the wrong height and is never built. Diffusicraft counts them from
 * {@value #BELOW} sections below the lowest of the camera's search area instead. The search area, which holds every
 * section a search can list, then fits in the 1024 heights at any render distance up to 250 chunks, in any level.
 * The sections built are the same as Sodium's own in levels within its range. Applied only when Sodium is installed.
 */
public final class SodiumTaskHeights {
	/** Sections below the lowest of the camera's search area that the 1024 heights start at. */
	static final int BELOW = 512;

	private SodiumTaskHeights() {
	}

	/** The section the heights of a search's tasks count from, for a search whose area starts at {@code lowestY}. */
	public static int lowest(int lowestY) {
		return lowestY - BELOW;
	}

	/** Sodium's section searches, which hold the lowest section of their area. */
	public interface Search {
		int diffusicraft$lowestSection();
	}

	/** Sodium's lists of tasks, which hold the section their heights count from. */
	public interface Tasks {
		void diffusicraft$countFrom(int sectionY);
	}
}
