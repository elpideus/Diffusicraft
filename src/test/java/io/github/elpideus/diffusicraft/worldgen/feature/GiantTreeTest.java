package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantTreeTest {
	private static final GiantTreeFeature.Form[] FORMS = GiantTreeFeature.Form.values();
	/** The heights each form grows to in tools/biomes.py, least and most: a 1:1 world's. */
	private static final int[][] HEIGHTS = {{110, 230}, {48, 80}, {80, 130}};
	/** The share of those that the giants grow to in the smallest world. */
	private static final double SMALLEST = GiantTree.size(WorldScale.VANILLA);

	/** The least and most heights of a form, in the smallest world and at 1:1. */
	private static int[] heights(GiantTreeFeature.Form form) {
		int[] full = HEIGHTS[form.ordinal()];
		return new int[]{(int) Math.round(full[0] * SMALLEST), (int) Math.round(full[1] * SMALLEST), full[0], full[1]};
	}

	/**
	 * The reaches a form grows with: its own, as a structure gives it, and those of a feature, which writes no farther
	 * than {@link GiantTree#MAX_REACH} and less near the edge of its chunks.
	 */
	private static int[] reaches(GiantTreeFeature.Form form, int height) {
		int own = GiantTree.reach(form, height);
		return own == GiantTree.MAX_REACH ? new int[]{own, 12} : new int[]{own, GiantTree.MAX_REACH, 12};
	}

	private static GiantTree.Blocks grow(GiantTreeFeature.Form form, int height, int reach, long seed) {
		return GiantTree.grow(form, height, reach, RandomSource.create(seed));
	}

	private static GiantTree.Blocks grow(GiantTreeFeature.Form form, int height, long seed) {
		return grow(form, height, GiantTree.reach(form, height), seed);
	}

	@Test
	void everyFormKeepsWithinItsReachAndHeight() {
		for (GiantTreeFeature.Form form : FORMS) {
			for (int height : heights(form)) {
				for (int reach : reaches(form, height)) {
					for (long seed = 0; seed < 8; seed++) {
						GiantTree.Blocks tree = grow(form, height, reach, seed);
						String what = form + " " + height + " tall, reach " + reach + ", seed " + seed;
						for (LongOpenHashSet blocks : new LongOpenHashSet[]{tree.wood, tree.leaves}) {
							for (long pos : blocks) {
								assertTrue(Math.abs(BlockPos.getX(pos)) <= reach && Math.abs(BlockPos.getZ(pos)) <= reach
									&& BlockPos.getY(pos) >= 0, what + " reaches " + BlockPos.of(pos));
							}
						}
						assertTrue(tree.top <= height + 2 && tree.top >= height * 0.8, what + " tops out at " + tree.top);
						assertTrue(!tree.foot.isEmpty() && tree.leaves.size() > 400, what + " holds " + tree.leaves.size()
							+ " leaves");
					}
				}
			}
		}
	}

	@Test
	void theSameDrawGrowsTheSameTree() {
		for (GiantTreeFeature.Form form : FORMS) {
			GiantTree.Blocks a = grow(form, 120, GiantTree.MAX_REACH, 7), b = grow(form, 120, GiantTree.MAX_REACH, 7);
			assertEquals(a.wood, b.wood, form.toString());
			assertEquals(a.leaves, b.leaves, form.toString());
			GiantTree.Blocks other = grow(form, 120, GiantTree.MAX_REACH, 8);
			assertTrue(!other.leaves.equals(a.leaves), form + " is the same tree for another draw");
		}
	}

	@Test
	void aTreeLaidChunkByChunkIsTheWholeTree() {
		for (GiantTreeFeature.Form form : FORMS) {
			for (long seed = 0; seed < 2; seed++) {
				int height = heights(form)[3];
				GiantTree plan = GiantTree.plan(form, height, GiantTree.reach(form, height), RandomSource.create(seed));
				GiantTree.Blocks whole = plan.blocks();
				Long2IntOpenHashMap distances = whole.distances();
				LongOpenHashSet wood = new LongOpenHashSet(), leaves = new LongOpenHashSet();
				// The chunks around a foot that stands off their corners, as the world's do.
				for (int minZ = -plan.reach - 5; minZ <= plan.reach; minZ += 16) {
					for (int minX = -plan.reach - 11; minX <= plan.reach; minX += 16) {
						BoundingBox chunk = new BoundingBox(minX, -16, minZ, minX + 15, plan.top() + 16, minZ + 15);
						GiantTree.Blocks slice = plan.blocks(chunk.inflatedBy(GiantTree.LEAF_REACH));
						Long2IntOpenHashMap near = slice.distances();
						for (long pos : slice.wood) {
							if (chunk.isInside(BlockPos.of(pos))) {
								wood.add(pos);
							}
						}
						for (long pos : slice.leaves) {
							if (chunk.isInside(BlockPos.of(pos))) {
								leaves.add(pos);
								assertEquals(distances.get(pos), near.get(pos), form + " leaf at " + BlockPos.of(pos));
							}
						}
					}
				}
				assertEquals(whole.wood, wood, form + ", seed " + seed);
				assertEquals(whole.leaves, leaves, form + ", seed " + seed);
			}
		}
	}

	@Test
	void theWoodHangsTogetherFromTheFoot() {
		for (GiantTreeFeature.Form form : FORMS) {
			for (int height : heights(form)) {
				for (int reach : reaches(form, height)) {
					for (long seed = 0; seed < 6; seed++) {
						GiantTree.Blocks tree = grow(form, height, reach, seed);
						int reached = connected(tree.wood, tree.foot);
						assertEquals(tree.wood.size(), reached, form + " " + height + " tall, reach " + reach + ", seed "
							+ seed + ": " + reached + " of " + tree.wood.size() + " logs hang together");
					}
				}
			}
		}
	}

	/** How many of {@code blocks} join {@code from} through blocks that touch at a face, an edge or a corner. */
	private static int connected(LongOpenHashSet blocks, LongOpenHashSet from) {
		LongOpenHashSet seen = new LongOpenHashSet(from);
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		from.forEach(queue::enqueue);
		while (!queue.isEmpty()) {
			long pos = queue.dequeueLong();
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						long next = BlockPos.offset(pos, dx, dy, dz);
						if (blocks.contains(next) && seen.add(next)) {
							queue.enqueue(next);
						}
					}
				}
			}
		}
		return seen.size();
	}

	@Test
	void nearlyEveryLeafLivesOnTheWood() {
		for (GiantTreeFeature.Form form : FORMS) {
			for (int height : heights(form)) {
				for (int reach : reaches(form, height)) {
					for (long seed = 0; seed < 6; seed++) {
						GiantTree.Blocks tree = grow(form, height, reach, seed);
						Long2IntOpenHashMap distances = tree.distances();
						float alive = distances.size() / (float) tree.leaves.size();
						assertTrue(alive >= 0.97f, form + " " + height + " tall, reach " + reach + ", seed " + seed + ": "
							+ alive + " of the leaves live");
						for (int distance : distances.values()) {
							assertTrue(distance >= 1 && distance <= GiantTree.LEAF_REACH);
						}
					}
				}
			}
		}
	}

	@Test
	void redwoodsStandBareUnderANarrowCrownAndKapoksOnButtresses() {
		for (long seed = 0; seed < 6; seed++) {
			GiantTree.Blocks redwood = grow(GiantTreeFeature.Form.REDWOOD, 200, seed);
			int lowest = redwood.leaves.longStream().mapToInt(BlockPos::getY).min().orElseThrow();
			assertTrue(lowest >= 200 * 0.4, "the redwood's crown starts at " + lowest);

			GiantTree.Blocks kapok = grow(GiantTreeFeature.Form.KAPOK, 110, seed);
			double trunk = 1.3 + 110 / 55.0;
			assertTrue(kapok.footReach >= trunk + 4, "the kapok's buttresses reach " + kapok.footReach);
			// The umbrella: most of the leaves in the top quarter.
			long high = kapok.leaves.longStream().filter(pos -> BlockPos.getY(pos) > 110 * 0.7).count();
			assertTrue(high > kapok.leaves.size() * 0.9, "the kapok holds " + high + " of " + kapok.leaves.size()
				+ " leaves in its crown");
			int across = across(kapok);
			assertTrue(across >= 110 * 0.5, "the kapok's umbrella is " + across + " blocks across");
		}
	}

	@Test
	void oaksSpreadBroadDomesOverABareTrunk() {
		for (int height : heights(GiantTreeFeature.Form.OAK)) {
			for (long seed = 0; seed < 6; seed++) {
				GiantTree.Blocks oak = grow(GiantTreeFeature.Form.OAK, height, seed);
				String what = "the oak " + height + " tall, seed " + seed;
				int across = across(oak);
				assertTrue(across >= height * 0.85, what + " is " + across + " blocks across");
				// The trunk shows under the crown: no leaves near it below where it forks.
				long hidden = oak.leaves.longStream().filter(pos -> BlockPos.getY(pos) < height * 0.18
					&& Math.hypot(BlockPos.getX(pos), BlockPos.getZ(pos)) < height * 0.15).count();
				assertEquals(0, hidden, what + " hides its trunk under " + hidden + " leaves");
				// The dome is open inside, so that its limbs show: few leaves near the trunk under its middle.
				long inside = oak.leaves.longStream().filter(pos -> BlockPos.getY(pos) < height * 0.45
					&& Math.hypot(BlockPos.getX(pos), BlockPos.getZ(pos)) < height * 0.25).count();
				assertTrue(inside < oak.leaves.size() * 0.02, what + " holds " + inside + " leaves inside its dome");
			}
		}
	}

	/** How far a tree's leaves spread across, the least of east to west and north to south. */
	private static int across(GiantTree.Blocks tree) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (long pos : tree.leaves) {
			minX = Math.min(minX, BlockPos.getX(pos));
			maxX = Math.max(maxX, BlockPos.getX(pos));
			minZ = Math.min(minZ, BlockPos.getZ(pos));
			maxZ = Math.max(maxZ, BlockPos.getZ(pos));
		}
		return Math.min(maxX - minX, maxZ - minZ) + 1;
	}

	@Test
	void aTallTreeGrowsQuickly() {
		long start = System.nanoTime();
		for (long seed = 0; seed < 10; seed++) {
			grow(GiantTreeFeature.Form.REDWOOD, 230, GiantTree.MAX_REACH, seed).distances();
		}
		long perTree = (System.nanoTime() - start) / 10 / 1_000_000;
		assertTrue(perTree < 200, "a redwood takes " + perTree + " ms");
		System.out.println("A 230-block redwood takes " + perTree + " ms");
	}
}
