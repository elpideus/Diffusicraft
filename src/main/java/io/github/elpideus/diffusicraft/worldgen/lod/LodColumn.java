package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * One column of a level of detail: runs of blocks from the bottom of the world up to the highest block that is not
 * air, and the column's biome. The runs above the ground hold what stands on it: water, ice, snow, and the trees'
 * trunks and leaves, with air between the ground and a crown. {@link LodSampler} reuses one instance for every
 * column, so read it before the next.
 */
public final class LodColumn {
	private BlockState[] states = new BlockState[8];
	private int[] bottoms = new int[8];
	private int[] tops = new int[8];
	private int count;
	private int ground;
	private Holder<Biome> biome;
	private int waterDepth;

	void reset(Holder<Biome> biome) {
		this.biome = biome;
		count = 0;
		ground = -1;
		waterDepth = 0;
	}

	/**
	 * Adds the run {@code [bottom, top)} of {@code state} above the runs added so far. A run of the same block right
	 * below is extended instead.
	 */
	void add(BlockState state, int bottom, int top) {
		if (top <= bottom) {
			return;
		}
		if (count > 0 && states[count - 1] == state && tops[count - 1] == bottom) {
			tops[count - 1] = top;
			return;
		}
		if (count == states.length) {
			states = Arrays.copyOf(states, count * 2);
			bottoms = Arrays.copyOf(bottoms, count * 2);
			tops = Arrays.copyOf(tops, count * 2);
		}
		states[count] = state;
		bottoms[count] = bottom;
		tops[count] = top;
		count++;
	}

	/** Marks the highest run so far as the top of the ground. */
	void markGround() {
		ground = count - 1;
	}

	void waterDepth(int depth) {
		waterDepth = depth;
	}

	/**
	 * A copy that stays as it is when this column is reused, without the caves under the ground fewer than
	 * {@code thinnest} blocks high: the stretches of cave air and the water and lava in it, which take the block under
	 * them instead.
	 */
	LodColumn closed(int thinnest) {
		LodColumn closed = new LodColumn();
		closed.reset(biome);
		closed.waterDepth = waterDepth;
		for (int run = 0; run < count; ) {
			int end = run;
			boolean cave = false;
			while (run > 0 && end < ground && (states[end].is(Blocks.CAVE_AIR)
				|| !states[end].getFluidState().isEmpty())) {
				cave |= states[end].is(Blocks.CAVE_AIR);
				end++;
			}
			if (cave && tops[end - 1] - bottoms[run] < thinnest) {
				closed.add(closed.states[closed.count - 1], bottoms[run], tops[end - 1]);
				run = end;
				continue;
			}
			for (end = Math.max(end, run + 1); run < end; run++) {
				closed.add(states[run], bottoms[run], tops[run]);
				if (run == ground) {
					closed.ground = closed.count - 1;
				}
			}
		}
		return closed;
	}

	/** Number of runs, bottom first. */
	public int size() {
		return count;
	}

	public BlockState state(int run) {
		return states[run];
	}

	/** Lowest block Y of a run. */
	public int bottom(int run) {
		return bottoms[run];
	}

	/** Block Y right above a run: the run covers {@code [bottom, top)}. */
	public int top(int run) {
		return tops[run];
	}

	/** The run at the top of the ground, under whatever stands on it. */
	public int ground() {
		return ground;
	}

	/** Block Y right above the highest run: where the open sky begins. */
	public int top() {
		return count == 0 ? Integer.MIN_VALUE : tops[count - 1];
	}

	/** Blocks of water (or ice) over the ground, which the sky light loses on the way down. */
	public int waterDepth() {
		return waterDepth;
	}

	public Holder<Biome> biome() {
		return biome;
	}
}
