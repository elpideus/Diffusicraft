package io.github.elpideus.diffusicraft.map;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A level as the relief map sees it: what to draw at each zoom level, and the answers to the page's questions. Zoom
 * level {@code z} draws {@code 2^z} blocks per pixel, and its tile {@code (x, y)} covers the blocks from
 * {@code x * 256 * 2^z} and {@code y * 256 * 2^z} east and south.
 */
interface MapLevel {
	/** The level's id, such as {@code minecraft:overworld}. */
	String id();

	/**
	 * The coarsest zoom level drawn from the full-resolution terrain, about a pixel of the terrain per pixel. Coarser
	 * levels are drawn from the coarse map, with the finer tiles drawn so far laid over it.
	 */
	int fineZoom();

	/** The coarsest zoom level. */
	int maxZoom();

	int minY();

	int maxY();

	int seaLevel();

	double metersPerBlock();

	/** Blocks from the centre to the edge of the world. */
	int border();

	/** The biomes the tiles' pixels index, by id. */
	List<String> biomes();

	/** The biomes' names, in the order of {@link #biomes}. */
	List<String> biomeNames();

	/** Draws a tile, from the coarse map alone if {@code coarse}. */
	MapRaster render(int zoom, int x, int y, boolean coarse);

	/** The column at a block, from the full-resolution terrain, or {@code null} if it is outside the world. */
	@Nullable Point point(int x, int z);

	/**
	 * Places of a biome, nearest {@code (x, z)} first, searched on the coarse map until {@code deadline}
	 * ({@link System#nanoTime}).
	 */
	List<Point> search(int biome, int x, int z, long deadline);

	/** A column: the Y of the ground's top block, the water over it in blocks and the biome's index. */
	record Point(int x, int z, int ground, int water, int biome) {
	}
}
