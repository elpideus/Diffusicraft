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
	void aCliffTakesAWaterfallIntoAPoolAtItsFoot() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 60, NO_SEA, 0, 0);
		int planned = 0;
		for (long seed = 0; seed < 64; seed++) {
			Waterfall fall = plan(cliff, seed);
			if (fall == null) {
				continue;
			}
			planned++;
			check(cliff, fall);
			assertEquals(100, fall.y);
			assertEquals(40, fall.drop);
			assertEquals(7, fall.lip.getX());
			assertTrue(fall.pool, "no pool at the foot of a cliff on level ground");
			assertTrue(fall.width >= 1 && fall.width <= MAX_WIDTH, fall.width + " wide");
			assertEquals(fall.width * 39, fall.falling.size());
			assertEquals(0, fall.gorge, "a gorge under a sheer cliff");
		}
		// The edge is one column in sixteen, and the plan tries 24 of them.
		assertTrue(planned >= 40, planned + " waterfalls in 64 chunks along a cliff");
	}

	@Test
	void aLongCliffTakesWideFalls() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 60, NO_SEA, 0, 0);
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
	void theWidestFallsAreInTheWettestLandOfTheLargestWorld() {
		assertEquals(Waterfall.WIDEST, Waterfall.widest(WorldScale.ULTRA, 1));
		assertEquals(3, Waterfall.widest(WorldScale.VANILLA, 1));
		assertEquals(Waterfall.WIDEST / 2, Waterfall.widest(WorldScale.ULTRA, 0));
		for (int k = 1; k < WorldScale.values().length; k++) {
			WorldScale smaller = WorldScale.values()[k - 1], larger = WorldScale.values()[k];
			assertTrue(Waterfall.widest(larger, 0.5f) >= Waterfall.widest(smaller, 0.5f), larger + " narrower");
		}
	}

	@Test
	void aCliffOverTheSeaFallsIntoIt() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 40, 50, 0, 0);
		int planned = 0;
		for (long seed = 0; seed < 32; seed++) {
			Waterfall fall = plan(cliff, seed);
			if (fall != null) {
				planned++;
				check(cliff, fall);
				assertTrue(!fall.pool, "a pool in the sea");
				assertEquals(50, fall.drop);
				for (long pos : fall.falling) {
					assertTrue(BlockPos.getY(pos) >= 51, "falling under the sea at " + BlockPos.of(pos));
				}
			}
		}
		assertTrue(planned > 0);
	}

	@Test
	void aSteepFaceTakesAWaterfallDownTheHeadOfAGorge() {
		// Level ground at 100, then a face falling two blocks a block out, down to 40.
		Terrain face = new Terrain((x, z) -> Math.max(40, 100 - 2 * Math.max(0, x - 7)), NO_SEA, 0, 0);
		int planned = 0;
		for (long seed = 0; seed < 64; seed++) {
			Waterfall fall = plan(face, seed);
			if (fall == null) {
				continue;
			}
			planned++;
			check(face, fall);
			assertTrue(fall.gorge > 0, "no gorge down a face that slopes");
			assertTrue(fall.pool, "no pool at the head of the gorge");
			// Straight down, from the stream to the pool at the gorge's floor.
			assertEquals(fall.width * (fall.drop - 1), fall.falling.size());
			int foot = fall.y - fall.drop;
			for (int out = 1; out <= fall.gorge + 1; out++) {
				int x = fall.lip.getX() + out;
				assertTrue(face.top(x, fall.lip.getZ()) >= foot, "the gorge cuts below its floor at " + x);
			}
		}
		assertTrue(planned >= 40, planned + " waterfalls in 64 chunks along a face");
	}

	/** A mesa: a round, level top and steep sides, rough, with the sea over the lowest ground or none. */
	@Test
	void everyWaterfallDownAMesaHoldsItsWater() {
		int planned = 0, gorges = 0, seas = 0;
		for (int width : new int[]{MAX_WIDTH, Waterfall.WIDEST}) {
			for (int sea : new int[]{NO_SEA, 66}) {
				for (int steep = 2; steep <= 4; steep++) {
					int fall = steep;
					IntBinaryOperator mesa = (x, z) -> Math.max(50, 100 - fall * Math.max(0,
						(int) Math.sqrt((x - 8) * (x - 8) + (z - 8) * (z - 8)) - 10)) + (hash(x, z) & 1);
					for (int cz = -1; cz <= 1; cz++) {
						for (int cx = -1; cx <= 1; cx++) {
							Terrain terrain = new Terrain(mesa, sea, cx * 16, cz * 16);
							for (long seed = 0; seed < 8; seed++) {
								Waterfall waterfall = plan(terrain, width, seed);
								if (waterfall != null) {
									planned++;
									check(terrain, waterfall);
									gorges += waterfall.gorge > 0 ? 1 : 0;
									seas += sea != NO_SEA && waterfall.y - waterfall.drop == sea ? 1 : 0;
								}
							}
						}
					}
				}
			}
		}
		assertTrue(planned > 100 && gorges > 40 && seas > 0, planned + " waterfalls, " + gorges + " down gorges, "
			+ seas + " to the sea");
	}

	@Test
	void slopesFinsAndCrestsTakeNone() {
		IntBinaryOperator[] grounds = {
			(x, z) -> 100 - x / 2,
			// As steep as a staircase, but not steep enough to have cut a gorge.
			(x, z) -> 100 - x,
			(x, z) -> x == 7 ? 100 : 60,
			// A ridge and a peak over level ground, in steps of ten. A staircase that went on would take waterfalls,
			// each step held by the next.
			(x, z) -> Math.max(60, 100 - 10 * Math.abs(x - 7)),
			(x, z) -> Math.max(60, 100 - 10 * Math.max(Math.abs(x - 7), Math.abs(z - 7))),
		};
		for (int k = 0; k < grounds.length; k++) {
			Terrain terrain = new Terrain(grounds[k], NO_SEA, 0, 0);
			for (long seed = 0; seed < 32; seed++) {
				assertNull(plan(terrain, seed), "a waterfall on ground " + k + ", seed " + seed);
			}
		}
	}

	@Test
	void theSameDrawLaysOutTheSameWaterfall() {
		Terrain cliff = new Terrain((x, z) -> x < 8 ? 100 : 60, NO_SEA, 0, 0);
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
		int planned = 0, pools = 0, seas = 0;
		for (int width : new int[]{MAX_WIDTH, Waterfall.WIDEST}) {
			for (int cz = -3; cz <= 3; cz++) {
				for (int cx = -3; cx <= 3; cx++) {
					Terrain terrain = new Terrain(plateaus, 66, cx * 16, cz * 16);
					for (long seed = 0; seed < 10; seed++) {
						Waterfall fall = plan(terrain, width, seed);
						if (fall != null) {
							planned++;
							check(terrain, fall);
							pools += fall.pool ? 1 : 0;
							seas += !fall.pool && fall.falling.longStream()
								.anyMatch(pos -> terrain.water(BlockPos.getX(pos), BlockPos.getZ(pos)) != Waterfall.DRY) ? 1 : 0;
						}
					}
				}
			}
		}
		assertTrue(planned > 100 && pools > 0 && seas > 0, planned + " waterfalls, " + pools + " into pools of their own, "
			+ seas + " into the sea");
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
		// Running water: over the edge, then down through the air to the pool, the water or the ground.
		for (long pos : fall.spilling) {
			assertTrue(falling.contains(BlockPos.offset(pos, 0, -1, 0)), "nothing falls from " + BlockPos.of(pos));
			assertEquals(fall.y, BlockPos.getY(pos));
		}
		for (long pos : fall.falling) {
			int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
			assertTrue(ground.top(x, z) < y || cut.contains(pos), "falls through the ground at " + BlockPos.of(pos));
			long below = BlockPos.offset(pos, 0, -1, 0);
			boolean solid = !cut.contains(below) && ground.top(x, z) >= y - 1;
			assertTrue(falling.contains(below) || sources.contains(below) || solid || ground.water(x, z) == y - 1,
				"the falls stop short at " + BlockPos.of(pos));
		}
		assertNotNull(fall.lip);
	}
}
