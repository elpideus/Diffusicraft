package io.github.elpideus.diffusicraft.worldgen.lod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which tiles of far terrain a viewer needs, and how fine each one is. A tile is a page of {@link LodStore}: the
 * {@value LodStore#PAGE}² columns of a level of detail, {@code 2^level} blocks apart. The tiles form a quadtree, as
 * Distant Horizons' do: a tile splits into the four of the level below while the viewer is close to it, so that the
 * columns of the tiles drawn lie about {@code distance / detail} blocks apart.
 */
public final class LodTiles {
	/**
	 * How much further than it would split a tile that split the last time stays split, and how much closer a tile
	 * that did not split must come to split ({@link Chooser}).
	 */
	static final double HYSTERESIS = 1.25;

	/** A tile: the page at {@code (x, z)} of a level. */
	public record Tile(int level, int x, int z) {
		/** Blocks along each side. */
		public int size() {
			return LodStore.PAGE << level;
		}

		public int minBlockX() {
			return x * size();
		}

		public int minBlockZ() {
			return z * size();
		}

		/** The blocks between columns. */
		public int step() {
			return 1 << level;
		}
	}

	private LodTiles() {
	}

	/**
	 * The tiles around block {@code (x, z)} out to {@code far} blocks, but those that lie wholly within {@code near}
	 * blocks of it each way, where the world's own chunks are drawn. A tile closer than {@code detail} times its step
	 * splits, so that a larger {@code detail} keeps finer tiles further out.
	 */
	public static List<Tile> around(double x, double z, int near, int far, int detail) {
		return new Chooser().around(x, z, near, far, detail);
	}

	/**
	 * Chooses the tiles around a viewer again and again as it moves, as {@link LodTiles#around} does, but a tile that
	 * split the last time stays split until the viewer is {@value LodTiles#HYSTERESIS} times as far from it as it
	 * would split at, and one that did not must come as much closer to split. A viewer that moves to and fro across
	 * the line where a tile splits then keeps the same tiles, instead of building the four finer ones and the coarse
	 * one again and again, as Distant Horizons' quadtree keeps its own. Not thread-safe.
	 */
	public static final class Chooser {
		private Set<Tile> split = Set.of();
		private Set<Tile> whole = Set.of();

		/** The tiles around block {@code (x, z)}, as {@link LodTiles#around} has them but for the hysteresis. */
		public List<Tile> around(double x, double z, int near, int far, int detail) {
			List<Tile> tiles = new ArrayList<>();
			Set<Tile> splitNow = new HashSet<>();
			int top = LodStore.LEVELS - 1;
			int size = LodStore.PAGE << top;
			int minX = Math.floorDiv((int) Math.floor(x) - far, size);
			int maxX = Math.floorDiv((int) Math.floor(x) + far, size);
			int minZ = Math.floorDiv((int) Math.floor(z) - far, size);
			int maxZ = Math.floorDiv((int) Math.floor(z) + far, size);
			for (int tz = minZ; tz <= maxZ; tz++) {
				for (int tx = minX; tx <= maxX; tx++) {
					visit(new Tile(top, tx, tz), x, z, near, far, detail, tiles, splitNow);
				}
			}
			split = splitNow;
			whole = new HashSet<>(tiles);
			return tiles;
		}

		/** Forgets the tiles chosen so far, as for a viewer that went elsewhere. */
		public void reset() {
			split = Set.of();
			whole = Set.of();
		}

		private void visit(Tile tile, double x, double z, int near, int far, int detail, List<Tile> tiles,
						   Set<Tile> splitNow) {
			double distance = distance(tile, x, z);
			if (distance > far || within(tile, x, z, near)) {
				return;
			}
			double reach = (double) detail * tile.step();
			if (split.contains(tile)) {
				reach *= HYSTERESIS;
			} else if (whole.contains(tile)) {
				reach /= HYSTERESIS;
			}
			if (tile.level() > 0 && distance < reach) {
				splitNow.add(tile);
				for (int i = 0; i < 4; i++) {
					visit(new Tile(tile.level() - 1, 2 * tile.x() + (i & 1), 2 * tile.z() + (i >> 1)), x, z, near,
						far, detail, tiles, splitNow);
				}
			} else {
				tiles.add(tile);
			}
		}
	}

	/** How far {@code (x, z)} is from the nearest point of the tile, in blocks: 0 inside it. */
	public static double distance(Tile tile, double x, double z) {
		double dx = Math.max(0, Math.max(tile.minBlockX() - x, x - (tile.minBlockX() + tile.size())));
		double dz = Math.max(0, Math.max(tile.minBlockZ() - z, z - (tile.minBlockZ() + tile.size())));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Whether the whole tile lies within {@code near} blocks of {@code (x, z)}. The world draws its chunks in a disc, not a
	 * square, so a tile in the corners of the square around the disc is still needed.
	 */
	private static boolean within(Tile tile, double x, double z, int near) {
		double dx = Math.max(Math.abs(tile.minBlockX() - x), Math.abs(tile.minBlockX() + tile.size() - x));
		double dz = Math.max(Math.abs(tile.minBlockZ() - z), Math.abs(tile.minBlockZ() + tile.size() - z));
		return dx * dx + dz * dz <= (double) near * near;
	}
}
