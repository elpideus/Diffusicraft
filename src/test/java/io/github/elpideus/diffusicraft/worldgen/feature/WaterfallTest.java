package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterfallTest {
	private static final int MIN_DROP = 8;
	/** As wide as the waterfalls of a Vanilla world fall at most. */
	private static final int MAX_WIDTH = 3;
	private static final int NO_SEA = Integer.MIN_VALUE + 1;

	/**
	 * Ground drawn by a function, solid from the bottom up to its top, and the sea over it wherever it lies below
	 * {@code sea}. Reading a column outside the 3×3 chunks around the chunk at {@code (x0, z0)} fails, as a feature
	 * may read no farther.
	 */
	private record Terrain(IntBinaryOperator height, int sea, int x0, int z0) implements Waterfall.Ground {
		@Override
		public int top(int x, int z) {
			assertTrue(near(x, z, x0, z0), "read the ground at " + x + ", " + z);
			return height.applyAsInt(x, z);
		}

		@Override
		public int water(int x, int z) {
			return top(x, z) < sea ? sea : Waterfall.DRY;
		}
	}

	private static boolean near(int x, int z, int x0, int z0) {
		return x >= x0 - 16 && x < x0 + 32 && z >= z0 - 16 && z < z0 + 32;
	}

	private static Waterfall plan(Terrain terrain, long seed) {
		return plan(terrain, MAX_WIDTH, seed);
	}

	private static Waterfall plan(Terrain terrain, int maxWidth, long seed) {
		return Waterfall.plan(terrain, terrain.x0, terrain.z0, MIN_DROP, maxWidth, RandomSource.create(seed));
	}

	@Test
	void aCliffOverTheSeaFallsStraightIntoIt() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 40, 50, 0, 0);
		int planned = 0;
		for (long seed = 0; seed < 64; seed++) {
			Waterfall fall = plan(cliff, seed);
			if (fall == null) {
				continue;
			}
			planned++;
			check(cliff, fall);
			assertEquals(100, fall.y);
			assertEquals(50, fall.drop);
			assertEquals(7, fall.lip.getX());
			assertTrue(fall.width >= 1 && fall.width <= MAX_WIDTH, fall.width + " wide");
			// From under the spill down to the sea, a block out from the edge.
			assertEquals(fall.width * 49, fall.falling.size());
			for (long pos : fall.falling) {
				assertEquals(8, BlockPos.getX(pos));
				assertTrue(BlockPos.getY(pos) >= 51, "falling under the sea at " + BlockPos.of(pos));
			}
		}
		// The edge is one column in sixteen, and the plan tries 24 of them.
		assertTrue(planned >= 40, planned + " waterfalls in 64 chunks along a cliff");
	}

	@Test
	void aLongCliffTakesWideFalls() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 40, 50, 0, 0);
		int planned = 0, widest = 0, width = 0;
		for (long seed = 0; seed < 64; seed++) {
			Waterfall fall = plan(cliff, Waterfall.WIDEST, seed);
			if (fall != null) {
				planned++;
				check(cliff, fall);
				assertTrue(fall.width <= Waterfall.WIDEST, fall.width + " wide");
				widest = Math.max(widest, fall.width);
				width += fall.width;
			}
		}
		assertTrue(planned >= 40, planned + " waterfalls in 64 chunks along a cliff");
		// From a third as wide as the widest to as wide, where the cliff runs on.
		assertEquals(Waterfall.WIDEST, widest);
		assertTrue(width >= 6 * planned, width / (double) planned + " blocks wide on average");
	}

	@Test
	void aWideFallBeatsAHigherNarrowOne() {
		// A cliff whose edge is ragged where it is highest, at 112, its notches a block wide, and level beyond, at
		// 106, over the sea at 66.
		Terrain cliff = new Terrain((x, z) -> z < 8 ? x < 7 + (~z & 1) ? 112 : 60 : x < 8 ? 106 : 60, 66, 0, 0);
		int notched = 0, level = 0;
		for (long seed = 0; seed < 512; seed++) {
			Waterfall fall = plan(cliff, Waterfall.WIDEST, seed);
			if (fall == null) {
				continue;
			}
			check(cliff, fall);
			if (fall.y == 112) {
				assertEquals(1, fall.width, "wider than a notch");
				notched++;
			} else {
				level++;
			}
		}
		// A notch takes the falls only in a chunk where no column of the level edge was tried: had the highest drop
		// been kept, it would take them wherever one of its columns was.
		assertTrue(level >= 3 * notched, notched + " falls from the notches, " + level + " from the level edge");
	}

	@Test
	void theWidestFallsAreInTheWettestLandOfTheLargestWorld() {
		assertEquals(Waterfall.WIDEST, Waterfall.widest(WorldScale.ULTRA, 1));
		assertEquals(3, Waterfall.widest(WorldScale.VANILLA, 1));
		assertEquals(Waterfall.WIDEST / 2, Waterfall.widest(WorldScale.ULTRA, 0));
		for (int k = 1; k < WorldScale.values().length; k++) {
			WorldScale smaller = WorldScale.values()[k - 1], larger = WorldScale.values()[k];
			assertTrue(Waterfall.widest(larger, 0.5f) >= Waterfall.widest(smaller, 0.5f), larger + " narrower");
		}
	}

	/** A mesa: a round, level top and sheer sides, rough, over the sea. */
	@Test
	void everyWaterfallDownAMesaHoldsItsWater() {
		IntBinaryOperator mesa = (x, z) -> ((x - 8) * (x - 8) + (z - 8) * (z - 8) <= 100 ? 100 : 50) + (hash(x, z) & 1);
		int planned = 0;
		for (int width : new int[]{MAX_WIDTH, Waterfall.WIDEST}) {
			for (int cz = -1; cz <= 1; cz++) {
				for (int cx = -1; cx <= 1; cx++) {
					Terrain terrain = new Terrain(mesa, 66, cx * 16, cz * 16);
					for (long seed = 0; seed < 8; seed++) {
						Waterfall waterfall = plan(terrain, width, seed);
						if (waterfall != null) {
							planned++;
							check(terrain, waterfall);
							assertEquals(66, waterfall.y - waterfall.drop, "into the sea");
						}
					}
				}
			}
		}
		assertTrue(planned > 20, planned + " waterfalls");
	}

	@Test
	void slopesFinsCrestsAndDryGroundTakeNone() {
		IntBinaryOperator[] grounds = {
			(x, z) -> 100 - x / 2,
			(x, z) -> 100 - x,
			// A face falling two blocks a block out into the sea, which a fall could reach only down a gorge.
			(x, z) -> Math.max(40, 100 - 2 * Math.max(0, x - 7)),
			(x, z) -> x == 7 ? 100 : 60,
			// A ridge and a peak over the sea, in steps of ten. A staircase that went on would take waterfalls,
			// each step held by the next.
			(x, z) -> Math.max(60, 100 - 10 * Math.abs(x - 7)),
			(x, z) -> Math.max(60, 100 - 10 * Math.max(Math.abs(x - 7), Math.abs(z - 7))),
		};
		for (int k = 0; k < grounds.length; k++) {
			Terrain terrain = new Terrain(grounds[k], 66, 0, 0);
			for (long seed = 0; seed < 32; seed++) {
				assertNull(plan(terrain, seed), "a waterfall on ground " + k + ", seed " + seed);
			}
		}
		// A cliff over dry ground, where the falls would end on it.
		Terrain dry = new Terrain((x, z) -> x < 8 ? 100 : 60, NO_SEA, 0, 0);
		for (long seed = 0; seed < 32; seed++) {
			assertNull(plan(dry, seed), "a waterfall onto dry ground, seed " + seed);
		}
	}

	@Test
	void theSameDrawLaysOutTheSameWaterfall() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 40, 50, 0, 0);
		for (long seed = 0; seed < 16; seed++) {
			Waterfall a = plan(cliff, seed), b = plan(cliff, seed);
			assertEquals(a == null, b == null);
			if (a != null) {
				assertEquals(a.sources, b.sources);
				assertEquals(a.cut, b.cut);
				assertEquals(a.falling, b.falling);
				assertEquals(a.ticks, b.ticks);
			}
		}
	}

	/** Plateaus of eight by eight blocks at heights nine blocks apart, roughened, with the sea over the lowest. */
	@Test
	void everyWaterfallOnRoughGroundHoldsItsWater() {
		IntBinaryOperator plateaus = (x, z) -> 60 + 9 * (hash(x >> 3, z >> 3) & 7) + (hash(x, z) & 1);
		int planned = 0;
		for (int width : new int[]{MAX_WIDTH, Waterfall.WIDEST}) {
			for (int cz = -3; cz <= 3; cz++) {
				for (int cx = -3; cx <= 3; cx++) {
					Terrain terrain = new Terrain(plateaus, 66, cx * 16, cz * 16);
					for (long seed = 0; seed < 10; seed++) {
						Waterfall fall = plan(terrain, width, seed);
						if (fall != null) {
							planned++;
							check(terrain, fall);
						}
					}
				}
			}
		}
		assertTrue(planned > 40, planned + " waterfalls into the sea");
	}

	private static int hash(int a, int b) {
		long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (int) h;
	}

	/** Checks that a waterfall's water stays where it is put, and that it lies within the chunks it may write to. */
	private static void check(Terrain ground, Waterfall fall) {
		assertTrue(fall.drop >= MIN_DROP, "falls " + fall.drop);
		assertEquals(fall.width, fall.spilling.size());
		LongOpenHashSet sources = new LongOpenHashSet(fall.sources), cut = new LongOpenHashSet(fall.cut);
		LongOpenHashSet spilling = new LongOpenHashSet(fall.spilling), falling = new LongOpenHashSet(fall.falling);
		for (LongArrayList positions : new LongArrayList[]{fall.sources, fall.cut, fall.spilling, fall.falling,
			fall.ticks}) {
			for (long pos : positions) {
				assertTrue(near(BlockPos.getX(pos), BlockPos.getZ(pos), ground.x0, ground.z0), "writes at "
					+ BlockPos.of(pos));
			}
		}
		for (long pos : fall.cut) {
			int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
			assertTrue(y <= ground.top(x, z), "cuts the air");
			// Nor does it open the ground to water beside it.
			for (int d = 0; d < 4; d++) {
				int nx = x + (d == 0 ? 1 : d == 2 ? -1 : 0), nz = z + (d == 1 ? 1 : d == 3 ? -1 : 0);
				int water = ground.water(nx, nz);
				assertTrue(cut.contains(BlockPos.asLong(nx, y, nz)) || water == Waterfall.DRY || water < y,
					"the water at " + nx + ", " + nz + " pours in at " + BlockPos.of(pos));
			}
		}
		// Still water: held on every side and from below, by the ground, by water, or by the falls themselves.
		for (long pos : fall.sources) {
			int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
			assertTrue(sources.contains(BlockPos.offset(pos, 0, -1, 0)) || ground.top(x, z) >= y - 1, "nothing under "
				+ BlockPos.of(pos));
			for (int d = 0; d < 4; d++) {
				int nx = x + (d == 0 ? 1 : d == 2 ? -1 : 0), nz = z + (d == 1 ? 1 : d == 3 ? -1 : 0);
				long next = BlockPos.asLong(nx, y, nz);
				boolean held = sources.contains(next) || spilling.contains(next) || falling.contains(next)
					|| ground.top(nx, nz) >= y && !cut.contains(next) || ground.water(nx, nz) >= y;
				assertTrue(held, "the water at " + BlockPos.of(pos) + " runs out to " + BlockPos.of(next));
			}
		}
		// Running water: over the edge, then down through the air into the water below.
		for (long pos : fall.spilling) {
			assertTrue(falling.contains(BlockPos.offset(pos, 0, -1, 0)), "nothing falls from " + BlockPos.of(pos));
			assertEquals(fall.y, BlockPos.getY(pos));
		}
		for (long pos : fall.falling) {
			int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
			assertTrue(ground.top(x, z) < y, "falls through the ground at " + BlockPos.of(pos));
			assertTrue(falling.contains(BlockPos.offset(pos, 0, -1, 0)) || ground.water(x, z) == y - 1,
				"the falls stop short of the water at " + BlockPos.of(pos));
		}
		assertNotNull(fall.lip);
	}
}
