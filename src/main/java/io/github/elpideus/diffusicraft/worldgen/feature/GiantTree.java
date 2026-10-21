package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan of one giant tree, before it meets the world: the parts it is built of, a trunk, limbs and clumps of
 * leaves, in positions relative to the foot of its trunk, the block at {@code (0, 0, 0)} just above the ground.
 * {@link #blocks} lays the parts in blocks, in the whole tree or in any box of it, and the blocks of a box are those of
 * the whole tree there, so that a tree can be placed a piece at a time. {@link GiantTreeFeature} places it.
 *
 * <p>Every form is a trunk that tapers from a flared foot, and limbs that carry the leaves in clumps. The clumps keep
 * within a few blocks of the wood, so that the leaves stay alive (see {@link Blocks#distances}), and large ones hold
 * twigs of their own. Nothing reaches farther than {@code reach} blocks from the trunk's axis. Pure, so it is
 * unit-tested without a world.
 */
final class GiantTree {
	/** The farthest any block may lie from the trunk's axis, in blocks: a feature writes in the 3×3 chunks around it. */
	static final int MAX_REACH = 21;
	/** The farthest a leaf lives from the wood, as {@code LeavesBlock} counts it: the leaves beyond decay. */
	static final int LEAF_REACH = 6;
	/** Clumps of leaves wider than this hold twigs, in blocks of radius. */
	private static final double TWIGGED = 3.5;
	/** Share of the blocks of a clump's outer part that hold leaves, which makes its edge ragged. */
	private static final double RAGGED = 0.7;
	/**
	 * How fast the giants shrink with the world: each halving of its size takes this power of two off their height,
	 * about a sixth, so that at 1:32 they stand at 42% of their height at 1:1. The land shrinks some sixteen times
	 * over the same sizes, so the giants of a small world are still large for it, and those of a large one enormous.
	 */
	private static final double SHRINK = 0.25;

	/** The farthest any block lies from the trunk's axis. */
	final int reach;
	private final List<Part> parts = new ArrayList<>();
	/** The highest any part may reach. */
	private int top;
	/** The draws of the plan, while it is made. */
	private final RandomSource random;

	private GiantTree(int reach, RandomSource random) {
		this.reach = Math.max(4, reach);
		this.random = random;
	}

	/** The share of their height at 1:1 that the giants grow to in a world of {@code scale}. */
	static double size(WorldScale scale) {
		return Math.pow(scale.ratio(), -SHRINK);
	}

	/** Plans a tree of {@code form} about {@code height} blocks tall, none of it farther than {@code reach} out. */
	static GiantTree plan(GiantTreeFeature.Form form, int height, int reach, RandomSource random) {
		GiantTree tree = new GiantTree(reach, random);
		switch (form) {
			case REDWOOD -> tree.redwood(height);
			case OAK -> tree.oak(height);
			case KAPOK -> tree.kapok(height);
		}
		return tree;
	}

	/** Grows a whole tree of {@code form}: {@link #plan}, laid in blocks. */
	static Blocks grow(GiantTreeFeature.Form form, int height, int reach, RandomSource random) {
		return plan(form, height, reach, random).blocks();
	}

	/** The highest any block of the tree may lie, over its foot. */
	int top() {
		return top;
	}

	/** The blocks of the whole tree. */
	Blocks blocks() {
		return blocks(new BoundingBox(-reach, 0, -reach, reach, top, reach));
	}

	/**
	 * The blocks of the tree within {@code box}, relative to its foot, as they lie in the whole tree. The distances of
	 * its leaves from the wood are those in the whole tree too for leaves at least {@value #LEAF_REACH} blocks inside
	 * the box, which it takes to lay a chunk: its own box grown by that much on every side.
	 */
	Blocks blocks(BoundingBox box) {
		Blocks blocks = new Blocks(box, reach);
		for (Part part : parts) {
			if (part.bounds().intersects(box)) {
				part.lay(blocks);
			}
		}
		blocks.leaves.removeAll(blocks.wood);
		for (long pos : blocks.wood) {
			if (BlockPos.getY(pos) == 0) {
				blocks.foot.add(pos);
				blocks.footReach = Math.max(blocks.footReach,
					Math.max(Math.abs(BlockPos.getX(pos)), Math.abs(BlockPos.getZ(pos))));
			}
			blocks.top = Math.max(blocks.top, BlockPos.getY(pos));
		}
		for (long pos : blocks.leaves) {
			blocks.top = Math.max(blocks.top, BlockPos.getY(pos));
		}
		return blocks;
	}

	/**
	 * A coast redwood or a giant sequoia: a straight column of a trunk, bare for half its height, under a narrow cone of
	 * short, drooping branches whose sprays of needles lie flat, and a spire on top. The foot flares into ribs.
	 */
	private void redwood(int height) {
		int top = height - 1;
		double foot = 1.4 + height / 45.0;
		int crownBase = (int) (height * (0.45 + 0.15 * random.nextDouble()));
		trunk(top, foot, 0.6, 1.3, 7, foot * 0.7, 4 + foot * 1.5);
		double longest = Math.min(reach - 3, 4 + height / 14.0);
		// Stubs of the branches the shade has killed, on the bare trunk.
		for (int y = crownBase / 2; y < crownBase; y += 3 + random.nextInt(4)) {
			if (random.nextFloat() < 0.4f) {
				double angle = random.nextDouble() * Math.PI * 2;
				double from = radius(y, top, foot, 0.6, 1.3);
				limb(angle, y, from, from + 2 + random.nextInt(3), y - 1, 0, 0);
			}
		}
		for (int y = crownBase; y < top - 2; y += 2 + random.nextInt(2)) {
			// From the widest whorl at the crown's base to the shortest under the spire.
			double k = (top - y) / (double) (top - crownBase);
			int branches = 2 + random.nextInt(3);
			for (int b = 0; b < branches; b++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double spray = 2.2 + 1.2 * k;
				double length = Math.min(longest * (0.25 + 0.75 * Math.pow(k, 0.9)) * (0.8 + 0.3 * random.nextDouble()),
					reach - spray - 0.5);
				double from = radius(y, top, foot, 0.6, 1.3);
				if (length <= from + 1) {
					continue;
				}
				// The branch droops away from the trunk and turns up at its tip.
				double droop = length * (0.1 + 0.15 * k);
				double bend = from + (length - from) * 0.7;
				double[] mid = limb(angle, y, from, bend, y - droop, k > 0.6 && height > 150 ? 0.8 : 0, 0);
				double[] tip = segment(mid, angle, length - bend, droop * 0.5, 0, 0);
				for (double d = from + (length - from) * 0.35; d <= length; d += 2.5) {
					double t = (d - from) / (length - from);
					double y0 = d <= bend ? y - droop * (d - from) / (bend - from) : mid[1] + (tip[1] - mid[1])
						* (d - bend) / (length - bend);
					clump(Math.cos(angle) * d, y0 + 0.5, Math.sin(angle) * d, spray * (0.7 + 0.3 * t),
						1.2 + 0.4 * k);
				}
			}
		}
		// The spire: the leader, sheathed in needles to the top.
		for (int y = top - 6; y <= top + 1; y++) {
			double r = 0.8 + 1.6 * (top + 1 - y) / 7.0;
			clump(0, y, 0, r, 0.6);
		}
	}

	/**
	 * An ancient oak: a short, massive trunk that forks into great limbs, under a broad dome of clumps of leaves. The
	 * lower limbs reach out wide and nearly level, the upper ones climb steeply into the top of the dome, and a few
	 * long ones reach out low under it.
	 */
	private void oak(int height) {
		double foot = 1.5 + height / 38.0;
		int fork = (int) (height * (0.25 + 0.08 * random.nextDouble()));
		trunk(fork + 2, foot, foot * 0.65, 1, 5, foot * 0.6, 3 + foot);
		double crown = Math.min(7, 4 + height / 25.0);
		double widest = reach - crown - 0.5;
		double domeTop = height - crown * 0.6;
		double turn = random.nextDouble() * Math.PI * 2;
		// The lower tier, out to the edge of the dome.
		int lower = 5 + random.nextInt(2);
		for (int l = 0; l < lower; l++) {
			double angle = turn + (l + 0.3 * random.nextDouble()) * Math.PI * 2 / lower;
			double rise = fork + (domeTop - fork) * (0.25 + 0.25 * random.nextDouble());
			double[] end = branchOut(angle, fork - 1, foot * 0.5, widest * (0.8 + 0.2 * random.nextDouble()), rise,
				crown);
			clump(end[0], end[1], end[2], crown, crown * 0.7);
		}
		// The upper tier, between the lower limbs and up into the top of the dome.
		double[] leader = segment(new double[]{0, fork, 0}, 0, 0, domeTop - fork, foot * 0.45, 0.8);
		clump(leader[0], leader[1], leader[2], crown, crown * 0.7);
		int upper = 3 + random.nextInt(3);
		for (int l = 0; l < upper; l++) {
			double angle = turn + (l + 0.5 + 0.3 * random.nextDouble()) * Math.PI * 2 / upper;
			double from = fork + (domeTop - fork) * (0.15 + 0.2 * random.nextDouble());
			double rise = from + (domeTop - from) * (0.7 + 0.3 * random.nextDouble());
			double[] end = branchOut(angle, from, foot * 0.35, widest * (0.4 + 0.25 * random.nextDouble()), rise,
				crown);
			clump(end[0], end[1], end[2], crown, crown * 0.7);
		}
		// Low limbs, reaching out nearly level under the dome.
		for (int l = random.nextInt(3); l > 0; l--) {
			double angle = random.nextDouble() * Math.PI * 2;
			double y = fork * (0.6 + 0.25 * random.nextDouble());
			double small = crown * 0.75;
			double out = reach - small - 0.5;
			double[] end = limb(angle, y, radius(y, fork + 2, foot, foot * 0.65, 1), out,
				y + out * (0.15 + 0.2 * random.nextDouble()), foot * 0.3, 0.5);
			clump(end[0], end[1], end[2], small, small * 0.6);
			clump(Math.cos(angle) * out * 0.6, end[1] - 1, Math.sin(angle) * out * 0.6, small * 0.8, small * 0.5);
		}
	}

	/**
	 * A kapok, the emergent of the rainforest: a pale, unbranched column on great plank buttresses, rising over the
	 * canopy to a flat umbrella of near-level limbs.
	 */
	private void kapok(int height) {
		double foot = 1.3 + height / 55.0;
		int crownBase = (int) (height * (0.72 + 0.08 * random.nextDouble()));
		trunk(crownBase + 2, foot, foot * 0.7, 1, 0, 0, 0);
		buttresses(4 + random.nextInt(3), foot, 3 + height / 22.0, 4 + height * 0.1);
		double crown = 5 + random.nextDouble();
		int limbs = 6 + random.nextInt(3);
		double turn = random.nextDouble() * Math.PI * 2;
		double flat = 0.7 * (height - crownBase);
		for (int l = 0; l < limbs; l++) {
			double angle = turn + (l + 0.4 * random.nextDouble()) * Math.PI * 2 / limbs;
			double out = (reach - crown - 0.5) * (0.7 + 0.3 * random.nextDouble());
			double rise = crownBase + flat * (0.6 + 0.3 * random.nextDouble());
			double[] end = branchOut(angle, crownBase, foot * 0.45, out, rise, crown);
			clump(end[0], end[1], end[2], crown, crown * 0.5);
		}
		// The leader, up to the middle of the umbrella.
		double[] leader = segment(new double[]{0, crownBase + 2, 0}, 0, 0, flat - 2, foot * 0.4, 0.8);
		clump(leader[0], leader[1], leader[2], crown + 2, 3);
	}

	/**
	 * The trunk from the ground to {@code top}: {@code foot} blocks of radius at the ground and {@code tip} at the top,
	 * along a curve of {@code taper}, and a flare of {@code flare} blocks over the first {@code flareHeight} ones, drawn
	 * out into {@code ribs} ribs.
	 */
	private void trunk(int top, double foot, double tip, double taper, int ribs, double flare, double flareHeight) {
		add(new Trunk(top, foot, tip, taper, ribs, flare, flareHeight, random.nextDouble() * Math.PI * 2));
	}

	/** The radius of a trunk at height {@code y}, as {@link #trunk} tapers it. */
	private static double radius(double y, int top, double foot, double tip, double taper) {
		return tip + (foot - tip) * Math.pow(1 - Math.min(1, y / top), taper);
	}

	/**
	 * Plank buttresses, as on a kapok: walls a block thick that run out from the trunk and fall to the ground, out to
	 * {@code out} blocks past the trunk at the foot, and up to {@code tall} blocks high on it.
	 */
	private void buttresses(int count, double foot, double out, double tall) {
		LongArrayList walls = new LongArrayList();
		double turn = random.nextDouble() * Math.PI * 2;
		for (int b = 0; b < count; b++) {
			double angle = turn + (b + 0.5 * random.nextDouble()) * Math.PI * 2 / count;
			double cos = Math.cos(angle), sin = Math.sin(angle);
			double wave = random.nextDouble() * Math.PI * 2;
			for (int y = 0; y < tall; y++) {
				double length = foot + out * Math.pow(1 - y / tall, 1.5);
				for (double d = foot - 0.5; d <= length; d += 0.5) {
					// Each wall winds a little as it runs out.
					double side = 0.8 * Math.sin(d * 0.4 + wave);
					walls.add(BlockPos.asLong((int) Math.round(cos * d - sin * side), y,
						(int) Math.round(sin * d + cos * side)));
				}
			}
		}
		add(new Logs(walls.toLongArray()));
	}

	/**
	 * A limb from the trunk's axis at {@code fromY}, bending outward and up to a point {@code out} blocks out and as
	 * high as {@code rise}, forking on the way. Returns its end, where the caller puts the crown's largest clump; the
	 * fork's end has a clump of its own.
	 */
	private double[] branchOut(double angle, double fromY, double thick, double out, double rise, double crown) {
		double bendOut = out * (0.35 + 0.2 * random.nextDouble());
		double bendY = fromY + (rise - fromY) * (0.5 + 0.2 * random.nextDouble());
		double[] bend = limb(angle, fromY, 0, bendOut, bendY, thick, thick * 0.6);
		double[] end = segment(bend, angle, out - bendOut, rise - bendY, thick * 0.6, 0.5);
		// The fork, to one side.
		double side = angle + (random.nextBoolean() ? 1 : -1) * (0.35 + 0.25 * random.nextDouble());
		double sideOut = Math.min(out * (0.6 + 0.2 * random.nextDouble()), reach - crown * 0.8 - 0.5) - bendOut;
		if (sideOut > 1) {
			double[] fork = segment(bend, side, sideOut, (rise - bendY) * 0.6, thick * 0.4, 0.5);
			clump(fork[0], fork[1], fork[2], crown * 0.8, crown * 0.55);
		}
		// Smaller clumps along the outer half, so the crown is not a ring of balls.
		for (double t = 0.45; t < 0.95; t += 0.25) {
			clump(bend[0] + (end[0] - bend[0]) * t, bend[1] + (end[1] - bend[1]) * t - 0.5,
				bend[2] + (end[2] - bend[2]) * t, crown * 0.6, crown * 0.45);
		}
		return end;
	}

	/** A limb straight out from the trunk's axis at {@code angle}, from {@code from} to {@code to} blocks out. */
	private double[] limb(double angle, double fromY, double from, double to, double toY, double thick, double tip) {
		double[] start = {Math.cos(angle) * from, fromY, Math.sin(angle) * from};
		return segment(start, angle, to - from, toY - fromY, thick, tip);
	}

	/**
	 * Wood from {@code start} for {@code out} blocks out at {@code angle} and {@code up} blocks up, {@code thick} blocks
	 * of radius at the start and {@code tip} at the end. Returns its end.
	 */
	private double[] segment(double[] start, double angle, double out, double up, double thick, double tip) {
		double[] end = {start[0] + Math.cos(angle) * out, start[1] + up, start[2] + Math.sin(angle) * out};
		add(new Segment(start[0], start[1], start[2], end[0], end[1], end[2], thick, tip));
		return end;
	}

	/**
	 * A clump of leaves, an ellipsoid {@code width} blocks in radius and {@code tall} high, with a ragged edge. A wide
	 * one holds twigs, so that its leaves stay within {@value #LEAF_REACH} blocks of wood.
	 */
	private void clump(double x, double y, double z, double width, double tall) {
		add(new Clump(x, y, z, width, tall, random.nextLong()));
		if (width > TWIGGED) {
			int twigs = 4 + random.nextInt(3);
			for (int t = 0; t < twigs; t++) {
				double angle = random.nextDouble() * Math.PI * 2;
				segment(new double[]{x, y, z}, angle, width * 0.7, (random.nextDouble() - 0.3) * tall * 0.5, 0, 0);
			}
			// And one up into its top, where a tall clump's leaves lie farthest from the rest.
			if (tall > TWIGGED) {
				segment(new double[]{x, y, z}, 0, 0, tall * 0.6, 0, 0);
			}
		}
	}

	private void add(Part part) {
		parts.add(part);
		top = Math.max(top, part.bounds().maxY());
	}

	private static int round(double value) {
		return (int) Math.round(value);
	}

	/** One part of the plan, which lays its blocks in those of a box. */
	private sealed interface Part {
		/** The box the part lies in. */
		BoundingBox bounds();

		void lay(Blocks blocks);
	}

	/** The trunk, as {@link #trunk} describes it, turned by {@code phase}. */
	private record Trunk(int top, double foot, double tip, double taper, int ribs, double flare, double flareHeight,
						 double phase) implements Part {
		@Override
		public BoundingBox bounds() {
			int bound = (int) Math.ceil(foot + flare + 0.5);
			return new BoundingBox(-bound, 0, -bound, bound, top, bound);
		}

		@Override
		public void lay(Blocks blocks) {
			BoundingBox box = blocks.box;
			for (int y = Math.max(0, box.minY()); y <= Math.min(top, box.maxY()); y++) {
				double r = radius(y, top, foot, tip, taper);
				double spread = y < flareHeight ? flare * Math.pow(1 - y / flareHeight, 2) : 0;
				int bound = (int) Math.ceil(r + spread + 0.5);
				for (int dz = Math.max(-bound, box.minZ()); dz <= Math.min(bound, box.maxZ()); dz++) {
					for (int dx = Math.max(-bound, box.minX()); dx <= Math.min(bound, box.maxX()); dx++) {
						double rib = ribs == 0 ? 1 : Math.pow(Math.max(0, Math.cos(ribs * (Math.atan2(dz, dx) - phase))), 4);
						if (Math.hypot(dx, dz) <= r + spread * (0.35 + 0.65 * rib) + 0.3) {
							blocks.wood(dx, y, dz);
						}
					}
				}
			}
		}
	}

	/** Logs laid one by one, as the walls of buttresses. */
	private record Logs(long[] positions, BoundingBox bounds) implements Part {
		Logs(long[] positions) {
			this(positions, bounds(positions));
		}

		private static BoundingBox bounds(long[] positions) {
			int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
			for (long pos : positions) {
				int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				minZ = Math.min(minZ, z);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
				maxZ = Math.max(maxZ, z);
			}
			return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
		}

		@Override
		public void lay(Blocks blocks) {
			for (long pos : positions) {
				blocks.wood(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
			}
		}
	}

	/**
	 * Wood along a straight line, {@code thick} blocks of radius at its start and {@code tip} at its end: balls every
	 * half block, or single logs where it is thinner than a block.
	 */
	private record Segment(double x0, double y0, double z0, double x1, double y1, double z1, double thick,
						   double tip) implements Part {
		@Override
		public BoundingBox bounds() {
			int r = (int) Math.ceil(Math.max(thick, tip)) + 1;
			return new BoundingBox(round(Math.min(x0, x1)) - r, round(Math.min(y0, y1)) - r,
				round(Math.min(z0, z1)) - r, round(Math.max(x0, x1)) + r, round(Math.max(y0, y1)) + r,
				round(Math.max(z0, z1)) + r);
		}

		@Override
		public void lay(Blocks blocks) {
			BoundingBox box = blocks.box;
			double length = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
			int steps = Math.max(1, (int) Math.ceil(length * 2));
			for (int s = 0; s <= steps; s++) {
				double t = s / (double) steps;
				int x = round(x0 + (x1 - x0) * t);
				int y = round(y0 + (y1 - y0) * t);
				int z = round(z0 + (z1 - z0) * t);
				double r = thick + (tip - thick) * t;
				if (r < 0.75) {
					blocks.wood(x, y, z);
					continue;
				}
				int bound = (int) Math.ceil(r);
				if (x + bound < box.minX() || x - bound > box.maxX() || y + bound < box.minY()
					|| y - bound > box.maxY() || z + bound < box.minZ() || z - bound > box.maxZ()) {
					continue;
				}
				for (int dy = -bound; dy <= bound; dy++) {
					for (int dz = -bound; dz <= bound; dz++) {
						for (int dx = -bound; dx <= bound; dx++) {
							if (dx * dx + dy * dy + dz * dz <= r * r + 0.25) {
								blocks.wood(x + dx, y + dy, z + dz);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * A clump of leaves around a log, as {@link #clump} describes it. Which blocks of its outer part hold leaves
	 * follows from {@code seed} and the block alone, so that any box of it is laid the same.
	 */
	private record Clump(double x, double y, double z, double width, double tall, long seed) implements Part {
		@Override
		public BoundingBox bounds() {
			int bx = (int) Math.ceil(width), by = (int) Math.ceil(tall);
			return new BoundingBox(round(x) - bx, round(y) - by, round(z) - bx, round(x) + bx, round(y) + by,
				round(z) + bx);
		}

		@Override
		public void lay(Blocks blocks) {
			BoundingBox box = blocks.box;
			BoundingBox bounds = bounds();
			for (int py = Math.max(bounds.minY(), box.minY()); py <= Math.min(bounds.maxY(), box.maxY()); py++) {
				for (int pz = Math.max(bounds.minZ(), box.minZ()); pz <= Math.min(bounds.maxZ(), box.maxZ()); pz++) {
					for (int px = Math.max(bounds.minX(), box.minX()); px <= Math.min(bounds.maxX(), box.maxX()); px++) {
						double dx = px - x, dy = py - y, dz = pz - z;
						double d = (dx * dx + dz * dz) / (width * width) + dy * dy / (tall * tall);
						if (d <= 1 && (d < 0.55 || ragged(px, py, pz))) {
							blocks.leaf(px, py, pz);
						}
					}
				}
			}
			blocks.wood(round(x), round(y), round(z));
		}

		/** Whether a block of the outer part holds a leaf: a hash of the clump's seed and the block. */
		private boolean ragged(int px, int py, int pz) {
			long h = seed ^ px * 0x9E3779B97F4A7C15L ^ py * 0xC2B2AE3D27D4EB4FL ^ pz * 0x165667B19E3779F9L;
			h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
			h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
			h ^= h >>> 31;
			return (h >>> 11) * 0x1.0p-53 < RAGGED;
		}
	}

	/**
	 * The blocks of a tree within a box, as positions relative to its foot: its wood and its leaves, and the columns of
	 * its foot.
	 */
	static final class Blocks {
		final LongOpenHashSet wood = new LongOpenHashSet();
		final LongOpenHashSet leaves = new LongOpenHashSet();
		/** The columns of the trunk's foot, which the roots fill down to the ground. */
		final LongOpenHashSet foot = new LongOpenHashSet();
		/** The farthest the foot reaches from the axis, buttresses included. */
		int footReach;
		/** The height of the top block. */
		int top;

		private final BoundingBox box;
		private final int reach;

		private Blocks(BoundingBox box, int reach) {
			this.box = box;
			this.reach = reach;
		}

		private void wood(int x, int y, int z) {
			if (holds(x, y, z)) {
				wood.add(BlockPos.asLong(x, y, z));
			}
		}

		private void leaf(int x, int y, int z) {
			if (holds(x, y, z)) {
				leaves.add(BlockPos.asLong(x, y, z));
			}
		}

		/** Whether a block lies in the box, within the tree's reach and over the ground. */
		private boolean holds(int x, int y, int z) {
			return Math.abs(x) <= reach && Math.abs(z) <= reach && y >= 0 && box.isInside(x, y, z);
		}

		/**
		 * Each leaf's distance from the wood, as {@code LeavesBlock} counts it: steps through leaves to the nearest
		 * wood, from 1 next to it. Leaves farther than {@value #LEAF_REACH} have none.
		 */
		Long2IntOpenHashMap distances() {
			Long2IntOpenHashMap distance = new Long2IntOpenHashMap(leaves.size());
			LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
			for (long pos : wood) {
				queue.enqueue(pos);
			}
			// Breadth first from all the wood at once, a ring of leaves at a time.
			for (int ring = 1; ring <= LEAF_REACH && !queue.isEmpty(); ring++) {
				for (int n = queue.size(); n > 0; n--) {
					long pos = queue.dequeueLong();
					for (Direction direction : Direction.values()) {
						long next = BlockPos.offset(pos, direction);
						if (leaves.contains(next) && !distance.containsKey(next)) {
							distance.put(next, ring);
							queue.enqueue(next);
						}
					}
				}
			}
			return distance;
		}
	}
}
