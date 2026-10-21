package io.github.elpideus.diffusicraft.map;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapTilesTest {
	private final LifoExecutor fine = new LifoExecutor("test fine", 1);
	private final LifoExecutor fast = new LifoExecutor("test fast", 1);

	@AfterEach
	void close() {
		fine.close();
		fast.close();
	}

	private static MapRaster get(MapTiles tiles, int zoom, int x, int y) throws Exception {
		MapTiles.Ticket ticket = tiles.request(zoom, x, y);
		try {
			return MapRaster.decode(ticket.tile.get(10, TimeUnit.SECONDS));
		} finally {
			ticket.release();
		}
	}

	private static int pixel(int x, int y) {
		return y * MapRaster.SIZE + x;
	}

	@Test
	void drawsATileOnceAndKeepsIt() throws Exception {
		FakeLevel level = new FakeLevel();
		MapTiles tiles = new MapTiles(level, null, fine, fast);
		MapRaster first = get(tiles, 2, 0, 0);
		MapRaster again = get(tiles, 2, 0, 0);
		assertEquals(1, level.fineRenders.get());
		assertArrayEquals(first.rgb, again.rgb);
	}

	@Test
	void coarserTilesShowTheFineOnesDrawnUnderThem() throws Exception {
		FakeLevel level = new FakeLevel();
		MapTiles tiles = new MapTiles(level, null, fine, fast);
		MapRaster before = get(tiles, 3, 0, 0);
		assertEquals(FakeLevel.COARSE_RGB, before.rgb[0]);
		assertEquals(0, before.flags & MapRaster.DETAIL);
		assertEquals(0, tiles.changes(0).revision());

		get(tiles, 2, 1, 0);
		MapTiles.Changes changes = tiles.changes(0);
		assertEquals(1, changes.revision());
		assertFalse(changes.all());
		assertEquals(1, changes.tiles().size());
		assertArrayEquals(new int[]{1, 0}, changes.tiles().getFirst());
		assertEquals(0, tiles.changes(1).tiles().size());

		MapRaster after = get(tiles, 3, 0, 0);
		assertEquals(FakeLevel.COARSE_RGB, after.rgb[pixel(0, 0)], "the north-west quarter has no fine tile");
		assertEquals(FakeLevel.FINE_RGB, after.rgb[pixel(MapRaster.SIZE / 2, 0)], "the north-east quarter has one");
		assertEquals(MapRaster.DETAIL, after.flags & MapRaster.DETAIL);
		// Two levels up, the fine tile fills a sixteenth.
		MapRaster top = get(tiles, 5, 0, 0);
		assertEquals(FakeLevel.FINE_RGB, top.rgb[pixel(MapRaster.SIZE / 8, 0)]);
		assertEquals(FakeLevel.COARSE_RGB, top.rgb[pixel(MapRaster.SIZE / 4, 0)]);
		assertEquals(1, level.fineRenders.get());
	}

	@Test
	void aFinerTileDrawsTheFineOneOverItInTheBackground() throws Exception {
		FakeLevel level = new FakeLevel();
		MapTiles tiles = new MapTiles(level, null, fine, fast);
		get(tiles, 0, 5, 6);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (tiles.changes(0).revision() == 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertArrayEquals(new int[]{1, 1}, tiles.changes(0).tiles().getFirst(), "the zoom 2 tile over (5, 6) at zoom 0");
		assertEquals(2, level.fineRenders.get());
	}

	@Test
	void keepsTilesOnDisk(@TempDir Path dir) throws Exception {
		MapTiles tiles = new MapTiles(new FakeLevel(), dir, fine, fast);
		get(tiles, 2, 3, 4);

		FakeLevel level = new FakeLevel();
		MapTiles reopened = new MapTiles(level, dir, fine, fast);
		get(reopened, 2, 3, 4);
		assertEquals(0, level.fineRenders.get());
		MapRaster mixed = get(reopened, 3, 1, 2);
		assertEquals(MapRaster.DETAIL, mixed.flags & MapRaster.DETAIL, "the fine tile on disk is laid over the coarse");
		assertEquals(FakeLevel.FINE_RGB, mixed.rgb[pixel(MapRaster.SIZE / 2, 0)]);
	}

	@Test
	void dropsATileNobodyWaitsForAnyMore() throws Exception {
		FakeLevel level = new FakeLevel();
		MapTiles tiles = new MapTiles(level, null, fine, fast);
		CountDownLatch busy = new CountDownLatch(1), go = new CountDownLatch(1);
		fine.submit(() -> {
			busy.countDown();
			try {
				go.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		busy.await();
		MapTiles.Ticket ticket = tiles.request(2, 0, 0);
		ticket.release();
		go.countDown();
		assertThrows(CancellationException.class, () -> ticket.tile.get(10, TimeUnit.SECONDS));
		assertEquals(0, level.fineRenders.get());
		assertEquals(FakeLevel.FINE_RGB, get(tiles, 2, 0, 0).rgb[0], "a new request draws it");
	}
}
