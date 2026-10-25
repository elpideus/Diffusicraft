package io.github.elpideus.diffusicraft.worldgen.lod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodTilesTest {
	private static final double X = 1234.5;
	private static final double Z = -5678.5;
	private static final int NEAR = 12 * 16;
	private static final int FAR = 16384;
	private static final int DETAIL = 128;

	private static List<LodTiles.Tile> covering(List<LodTiles.Tile> tiles, int x, int z) {
		return tiles.stream().filter(tile -> tile.minBlockX() <= x && x < tile.minBlockX() + tile.size()
			&& tile.minBlockZ() <= z && z < tile.minBlockZ() + tile.size()).toList();
	}

	@Test
	void theTilesCoverTheFarViewOnceAndLeaveOutTheChunksAroundTheViewer() {
		List<LodTiles.Tile> tiles = LodTiles.around(X, Z, NEAR, FAR, DETAIL);
		assertTrue(tiles.size() < 1000, tiles.size() + " tiles");
		for (int dz = -FAR + 7; dz < FAR; dz += 97) {
			for (int dx = -FAR + 7; dx < FAR; dx += 97) {
				double distance = Math.hypot(dx, dz);
				int x = (int) Math.floor(X) + dx;
				int z = (int) Math.floor(Z) + dz;
				List<LodTiles.Tile> found = covering(tiles, x, z);
				if (distance < FAR - LodStore.PAGE && distance > NEAR) {
					assertEquals(1, found.size(), "tiles at " + dx + ", " + dz + ": " + found);
				} else {
					assertTrue(found.size() <= 1, "tiles at " + dx + ", " + dz + ": " + found);
				}
			}
		}
		// A tile within the chunks the world draws is never one of them.
		assertEquals(0, covering(tiles, (int) X + 1, (int) Z + 1).stream()
			.filter(tile -> tile.size() <= NEAR).count());
	}

	@Test
	void theColumnsLieFurtherApartTheFurtherAwayTheyAre() {
		List<LodTiles.Tile> tiles = LodTiles.around(X, Z, NEAR, FAR, DETAIL);
		for (int d = 2 * NEAR; d < FAR - LodStore.PAGE; d = d * 5 / 4) {
			int x = (int) Math.floor(X) + d;
			int z = (int) Math.floor(Z);
			LodTiles.Tile tile = covering(tiles, x, z).getFirst();
			assertTrue(tile.step() <= Math.max(1, d / DETAIL), "step " + tile.step() + " at " + d);
			assertTrue(tile.step() * (2 * DETAIL + 2 * LodStore.PAGE * Math.sqrt(2)) > d,
				"step " + tile.step() + " at " + d);
		}
	}

	@Test
	void aViewerMovingToAndFroAcrossTheLineWhereATileSplitsKeepsItsTiles() {
		// A tile of level 2 splits closer than 512 blocks; the viewer stands south of it.
		LodTiles.Tile tile = new LodTiles.Tile(2, 0, 0);
		double x = 128;
		int far = 4096;
		LodTiles.Chooser chooser = new LodTiles.Chooser();
		assertTrue(splits(chooser.around(x, 256 + 500, 0, far, DETAIL), tile));
		// Without the hysteresis it would no longer split here.
		assertFalse(splits(LodTiles.around(x, 256 + 530, 0, far, DETAIL), tile));
		assertTrue(splits(chooser.around(x, 256 + 530, 0, far, DETAIL), tile));
		assertTrue(splits(chooser.around(x, 256 + 630, 0, far, DETAIL), tile));
		assertFalse(splits(chooser.around(x, 256 + 650, 0, far, DETAIL), tile));
		// And back: it splits again only well inside the line.
		assertFalse(splits(chooser.around(x, 256 + 480, 0, far, DETAIL), tile));
		assertFalse(splits(chooser.around(x, 256 + 420, 0, far, DETAIL), tile));
		assertTrue(splits(chooser.around(x, 256 + 400, 0, far, DETAIL), tile));
	}

	/** Whether the tiles hold finer tiles within {@code tile} rather than {@code tile} itself or a coarser one. */
	private static boolean splits(List<LodTiles.Tile> tiles, LodTiles.Tile tile) {
		List<LodTiles.Tile> found = covering(tiles, tile.minBlockX(), tile.minBlockZ());
		assertEquals(1, found.size(), found.toString());
		return found.getFirst().level() < tile.level();
	}

	@Test
	void aTileIsAPageOfItsLevel() {
		LodTiles.Tile tile = new LodTiles.Tile(3, -2, 5);
		assertEquals(512, tile.size());
		assertEquals(-1024, tile.minBlockX());
		assertEquals(2560, tile.minBlockZ());
		assertEquals(8, tile.step());
		assertEquals(0, LodTiles.distance(tile, -1000, 3000));
		assertEquals(24, LodTiles.distance(tile, -1048, 3000));
	}
}
