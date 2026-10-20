package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * A square grid of {@link LodSampler}'s columns, kept so that a stack of voxel sections can be cut from it. Voxy draws
 * its far terrain from these (see {@code client.compat.voxy}).
 *
 * <p>A voxel is a cube of blocks, {@code size} on a side, and shows the highest block in it that is not air, as it
 * looks from above: water over the ground in the same voxel hides it. Voxy's own coarser levels keep the most opaque
 * block instead, which at 16 blocks a voxel turns every sea shallower than that into dry ground standing at sea level.
 * The sky lights the air at full strength. Water loses one level of light for each block between it and its surface,
 * as it does in the world. Blocks that let light through, such as leaves and ice, take one level less than the open
 * sky, and the others none.
 *
 * <p>A column that reaches above the consumer's ceiling sinks by the difference into the rock under it, so it keeps
 * its snow and soils and stands flat at that height, as it does for Distant Horizons.
 */
public final class LodGrid {
	/** Sky light of open air. */
	public static final int FULL_SKY = 15;

	static final byte AIR = 0;
	static final byte WATER = 1;
	/** A block that lets light through. */
	static final byte CLEAR = 2;
	static final byte OPAQUE = 3;

	private final int width;
	/** The runs of column {@code i} are {@code first[i]} up to {@code first[i + 1]}, bottom first. */
	private final int[] first;
	private BlockState[] states = new BlockState[0];
	private int[] bottoms = new int[0];
	private int[] tops = new int[0];
	private byte[] kinds = new byte[0];
	/** Block Y right above the water over each column's ground, or above its ground where it is dry. */
	private final int[] surfaces;
	private final Holder<Biome>[] biomes;
	private int runs;
	private int columns;
	private int highest = Integer.MIN_VALUE;

	@SuppressWarnings("unchecked")
	LodGrid(int width) {
		this.width = width;
		this.first = new int[width * width + 1];
		this.surfaces = new int[width * width];
		this.biomes = new Holder[width * width];
	}

	/**
	 * Samples the grid of {@code width}² columns whose corner block is {@code (minX, minZ)}, one column every
	 * {@code step} blocks, sinking those that reach above {@code ceiling}, the block Y right above the highest block
	 * kept.
	 */
	public static LodGrid sample(LodSampler sampler, int minX, int minZ, int width, int step, int ceiling) {
		LodGrid grid = new LodGrid(width);
		sampler.sample(minX, minZ, width, step, (x, z, column) -> grid.add(column, ceiling));
		return grid;
	}

	/** Columns along each side. Column {@code (x, z)} is number {@code z * width + x}. */
	public int width() {
		return width;
	}

	/** Block Y right above the highest block of any column, or {@link Integer#MIN_VALUE} if every column is empty. */
	public int highest() {
		return highest;
	}

	public Holder<Biome> biome(int column) {
		return biomes[column];
	}

	/**
	 * Fills {@code states} and {@code sky} with the voxels of a column, {@code size} blocks tall each, the lowest
	 * starting at block Y {@code bottom}: the block each voxel shows, {@code null} for air, and its sky light.
	 */
	public void voxels(int column, int bottom, int size, @Nullable BlockState[] states, byte[] sky) {
		int[] runs = new int[states.length];
		voxels(bottoms, tops, kinds, first[column], first[column + 1], surfaces[column], bottom, size, runs, sky);
		for (int i = 0; i < runs.length; i++) {
			states[i] = runs[i] < 0 ? null : this.states[runs[i]];
		}
	}

	void add(LodColumn column, int ceiling) {
		int size = column.size();
		int sink = Math.max(0, column.top() - ceiling);
		if (runs + size > states.length) {
			int capacity = Math.max(runs + size, states.length * 2);
			states = Arrays.copyOf(states, capacity);
			bottoms = Arrays.copyOf(bottoms, capacity);
			tops = Arrays.copyOf(tops, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
		for (int run = 0; run < size; run++) {
			BlockState state = column.state(run);
			states[runs] = state;
			// The lowest run keeps its bottom: the rock under a sunken column still reaches the bottom of the world.
			bottoms[runs] = run == 0 ? column.bottom(run) : column.bottom(run) - sink;
			tops[runs] = column.top(run) - sink;
			kinds[runs] = kind(state);
			runs++;
		}
		int ground = column.ground();
		surfaces[columns] = ground < 0 ? Integer.MIN_VALUE : column.top(ground) - sink + column.waterDepth();
		biomes[columns] = column.biome();
		columns++;
		first[columns] = runs;
		if (size > 0) {
			highest = Math.max(highest, column.top() - sink);
		}
	}

	private static byte kind(BlockState state) {
		if (state.isAir()) {
			return AIR;
		}
		if (state.is(Blocks.WATER)) {
			return WATER;
		}
		return state.canOcclude() ? OPAQUE : CLEAR;
	}

	/**
	 * The voxels of one column, from its runs {@code from} up to {@code to}: for each voxel the run whose block it
	 * shows, or −1 for air, and its sky light.
	 */
	static void voxels(int[] bottoms, int[] tops, byte[] kinds, int from, int to, int surface, int bottom, int size,
					   int[] runs, byte[] sky) {
		// Runs are walked down from the top as the voxels are, so each is looked at about once.
		int run = to - 1;
		for (int voxel = runs.length - 1; voxel >= 0; voxel--) {
			int low = bottom + voxel * size;
			int high = low + size;
			while (run >= from && bottoms[run] >= high) {
				run--;
			}
			int shown = run;
			while (shown >= from && tops[shown] > low && kinds[shown] == AIR) {
				shown--;
			}
			if (shown < from || tops[shown] <= low) {
				runs[voxel] = -1;
				sky[voxel] = FULL_SKY;
			} else if (kinds[shown] == WATER) {
				runs[voxel] = shown;
				int highestWater = Math.min(high, tops[shown]) - 1;
				sky[voxel] = (byte) Math.max(0, FULL_SKY - (surface - highestWater));
			} else {
				runs[voxel] = shown;
				sky[voxel] = (byte) (kinds[shown] == CLEAR ? FULL_SKY - 1 : 0);
			}
		}
	}
}
