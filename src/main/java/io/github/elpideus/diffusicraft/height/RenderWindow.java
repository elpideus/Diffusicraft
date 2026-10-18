package io.github.elpideus.diffusicraft.height;

/**
 * The rows of sections the client keeps render data for: a window of {@link #rows()} section rows around the camera,
 * instead of every row of the level.
 *
 * <p>Vanilla's render grid holds (2r+1)² × every section row, but only draws sections within r rows of the camera.
 * In a 32,736-block level that is 2046 rows, of which 2r+1 can ever be drawn. The window holds those rows and moves
 * with the camera. Row slots rotate like the grid's columns do: section row y always lives in slot
 * {@code floorMod(y, rows)}, so moving one row up reuses one slot and leaves the others alone.
 *
 * <p>Pure Java, so it can be tested without the game. The client's {@code WindowedSectionStorage} uses it.
 *
 * @param minSectionY the level's lowest section row
 * @param maxSectionY the level's highest section row
 * @param rows        how many rows the window holds, at most the level's height in sections
 */
public record RenderWindow(int minSectionY, int maxSectionY, int rows) {
	public RenderWindow {
		if (rows < 1 || rows > maxSectionY - minSectionY + 1) {
			throw new IllegalArgumentException("A window of " + rows + " rows in sections " + minSectionY + ".." + maxSectionY);
		}
	}

	/**
	 * The window for a level and render distance.
	 *
	 * @param verticalDistance rows above and below the camera; 0 or anything above the render distance means the
	 *                         render distance, which is as far as vanilla draws vertically anyway
	 */
	public static RenderWindow of(int minSectionY, int maxSectionY, int renderDistance, int verticalDistance) {
		int reach = verticalDistance > 0 ? Math.min(verticalDistance, renderDistance) : renderDistance;
		return new RenderWindow(minSectionY, maxSectionY, Math.min(2 * reach + 1, maxSectionY - minSectionY + 1));
	}

	/** Whether the window holds the whole level, so a vanilla grid costs nothing more. */
	public boolean coversLevel() {
		return rows == maxSectionY - minSectionY + 1;
	}

	/** The lowest row in the window when the camera is in section row {@code cameraSectionY}. */
	public int lowest(int cameraSectionY) {
		// Centred on the camera and kept inside the level, so a camera above or below it still sees its edge.
		return Math.clamp((long) cameraSectionY - (rows - 1) / 2, minSectionY, maxSectionY - rows + 1);
	}

	/** The slot that section row {@code sectionY} occupies whenever it is in the window. */
	public int slot(int sectionY) {
		return Math.floorMod(sectionY, rows);
	}

	/** The section row in {@code slot} when the window starts at {@code lowest}. */
	public int sectionY(int slot, int lowest) {
		return lowest + Math.floorMod(slot - lowest, rows);
	}
}
