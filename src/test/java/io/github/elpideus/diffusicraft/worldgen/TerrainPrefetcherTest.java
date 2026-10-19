package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainPrefetcherTest {
	/** Blocks per tile side at the standard scale. */
	private static final double TILE_BLOCKS = TerrainField.TILE / WorldScale.STANDARD.pixelsPerBlock();

	/** Flat land that takes {@code millis} per sample, like a tile's inference, and counts its samples. */
	private static final class SlowSource implements TerrainSource {
		final AtomicInteger samples = new AtomicInteger();
		final long millis;
		volatile boolean broken;

		SlowSource(long millis) {
			this.millis = millis;
		}

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			if (broken) {
				throw new IllegalStateException("no models");
			}
			samples.incrementAndGet();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			int plane = (i1 - i0) * (j1 - j0);
			return new TerrainSample(i0, j0, i1 - i0, j1 - j0, new float[plane],
				new float[TerrainSample.CLIMATE_CHANNELS * plane]);
		}

		@Override
		public int coarseScale() {
			return 4;
		}

		@Override
		public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
			return new float[COARSE_CHANNELS * (ci1 - ci0) * (cj1 - cj0)];
		}
	}

	private static Set<Long> tiles(List<TerrainPrefetcher.Request> plan) {
		Set<Long> keys = new HashSet<>();
		for (TerrainPrefetcher.Request r : plan) {
			keys.add(((long) r.ti() << 32) | (r.tj() & 0xffffffffL));
		}
		return keys;
	}

	private static long tile(int ti, int tj) {
		return ((long) ti << 32) | (tj & 0xffffffffL);
	}

	/** Tiles {@code i0} to {@code i1} down and {@code j0} to {@code j1} across, inclusive. */
	private static Set<Long> tiles(int i0, int j0, int i1, int j1) {
		Set<Long> tiles = new HashSet<>();
		for (int ti = i0; ti <= i1; ti++) {
			for (int tj = j0; tj <= j1; tj++) {
				tiles.add(tile(ti, tj));
			}
		}
		return tiles;
	}

	@Test
	void aStillPlayerGetsTheTilesWithinItsViewAndTheRingAround() {
		TerrainField field = new TerrainField(1, new SlowSource(0));
		// In the middle of tile (0, 0), with a view that stays inside it.
		double mid = TILE_BLOCKS / 2;
		var plan = TerrainPrefetcher.plan(List.of(
			new TerrainPrefetcher.Traveller(field, WorldScale.STANDARD, mid, mid, 0, 0, 100)));
		assertEquals(tiles(-1, -1, 1, 1), tiles(plan));
		assertEquals(tile(0, 0), tile(plan.get(0).ti(), plan.get(0).tj()), "the tile in view first");
		// Near the corner of four tiles, all four and the ring around them.
		plan = TerrainPrefetcher.plan(List.of(
			new TerrainPrefetcher.Traveller(field, WorldScale.STANDARD, 10, -10, 0, 0, 100)));
		assertEquals(tiles(-2, -2, 1, 1), tiles(plan));
	}

	@Test
	void aMovingPlayerGetsTheTilesAheadInOrder() {
		TerrainField field = new TerrainField(1, new SlowSource(0));
		double mid = TILE_BLOCKS / 2;
		// Flying east (+X, which is +j) at elytra speed through the middle of a row of tiles.
		var plan = TerrainPrefetcher.plan(List.of(
			new TerrainPrefetcher.Traveller(field, WorldScale.STANDARD, mid, mid, 33, 0, 100)));
		double reach = mid + 33 * TerrainPrefetcher.HORIZON_SECONDS + 100;
		int last = (int) Math.floor(reach / TILE_BLOCKS);
		assertEquals(tiles(-1, -1, 1, last + 1), tiles(plan));
		int next = 0;
		for (TerrainPrefetcher.Request r : plan) {
			if (r.ti() == 0 && r.tj() >= 0) {
				assertEquals(next++, r.tj(), "tiles in the order the player reaches them");
			}
		}
		assertTrue(last >= 1, "the plan reaches beyond the player's own tile");
	}

	@Test
	void aPlanNeverFillsMoreThanAQuarterOfTheCache() {
		TerrainField field = new TerrainField(1, new SlowSource(0), 16);
		// A very fast traveller in a world where tiles are small, with a wide view.
		var plan = TerrainPrefetcher.plan(List.of(
			new TerrainPrefetcher.Traveller(field, WorldScale.STANDARD, 0, 0, 1000, 1000, 600)));
		assertEquals(4, plan.size());
		assertEquals(0, plan.get(0).seconds());
		// Speeds are capped, so a teleport-like velocity does not send the plan off into the distance.
		double reach = TerrainPrefetcher.MAX_SPEED * TerrainPrefetcher.HORIZON_SECONDS + 600 + TILE_BLOCKS;
		var fast = TerrainPrefetcher.plan(List.of(
			new TerrainPrefetcher.Traveller(new TerrainField(1, new SlowSource(0)), WorldScale.STANDARD, 0, 0,
				1e6, 0, 600)));
		for (TerrainPrefetcher.Request r : fast) {
			assertTrue(r.tj() * TILE_BLOCKS <= reach, "tile " + r.tj() + " is beyond the capped speed");
		}
	}

	@Test
	void prefetchedTilesAreComputedOnceAndReadWithoutWaiting() throws Exception {
		SlowSource source = new SlowSource(30);
		TerrainField field = new TerrainField(1, source);
		assertFalse(field.hasTile(0, 0));
		assertTrue(field.prefetch(0, 0));
		assertTrue(field.hasTile(0, 0));
		assertFalse(field.prefetch(0, 0), "a tile in memory is not computed again");
		assertEquals(1, source.samples.get());
		field.elevation(10.5, 10.5);
		assertEquals(0, field.tileWaitNanos(), "a reader of a prefetched tile does not wait");
		field.elevation(TerrainField.TILE + 10.5, 10.5);
		assertTrue(field.tileWaitNanos() >= 25_000_000L, "a reader of a missing tile waits for it");

		// A failing source fails the prefetch and leaves nothing behind.
		source.broken = true;
		assertThrows(IllegalStateException.class, () -> field.prefetch(5, 5));
		assertFalse(field.hasTile(5, 5));
	}

	@Test
	void flyingOverPrefetchedTerrainRarelyWaits() throws Exception {
		// Time runs 50 times faster than in game: a tile takes 0.05 s here for 2.5 s of inference, and a player at
		// elytra speed crosses a 512-block tile in 0.31 s instead of 15.5. Chunk generation reads the field along the
		// leading edge of the view, every tick.
		long waitWithout = fly(false);
		long waitWith = fly(true);
		assertTrue(waitWithout > 300_000_000L, "without prefetching the flight waited only " + waitWithout / 1e6 + " ms");
		assertTrue(waitWith < waitWithout / 4,
			"waited " + waitWith / 1e6 + " ms with prefetching and " + waitWithout / 1e6 + " ms without");
	}

	/** Nanoseconds that chunk generation waited for tiles over a flight of six tiles east of a ready spawn. */
	private static long fly(boolean prefetch) throws InterruptedException {
		double speedup = 50;
		double speed = 33 * speedup;
		// What chunk generation covers around the player: a view of 12 chunks and the margin of PlayerPrefetch.
		double reach = 13 * 16 + 64;
		TerrainField field = new TerrainField(1, new SlowSource(50));
		double z = TILE_BLOCKS / 2;
		// The spawn area is generated before the player takes off.
		for (int ti = TerrainField.tileOf(pixel(z - reach)); ti <= TerrainField.tileOf(pixel(z + reach)); ti++) {
			for (int tj = TerrainField.tileOf(pixel(-reach)); tj <= TerrainField.tileOf(pixel(reach)); tj++) {
				field.prefetch(ti, tj);
			}
		}
		try (TerrainPrefetcher prefetcher = new TerrainPrefetcher()) {
			long start = System.nanoTime();
			long tick = 0;
			while (true) {
				double seconds = (System.nanoTime() - start) / 1e9;
				double x = speed * seconds;
				if (x > 6 * TILE_BLOCKS) {
					break;
				}
				if (prefetch && tick % 10 == 0) {
					// The plan is in game seconds, so the prefetcher sees the game's speed.
					prefetcher.update(List.of(new TerrainPrefetcher.Traveller(field, WorldScale.STANDARD,
						x, z, speed / speedup, 0, reach)));
				}
				for (double dz = -reach; dz <= reach; dz += reach) {
					field.elevation(pixel(z + dz), pixel(x + reach));
				}
				tick++;
				Thread.sleep((long) (50 / speedup));
			}
		}
		return field.tileWaitNanos();
	}

	private static double pixel(double block) {
		return WorldScale.STANDARD.toPixel((int) Math.floor(block));
	}
}
