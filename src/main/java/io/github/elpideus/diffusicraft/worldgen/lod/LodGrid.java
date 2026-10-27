package io.github.elpideus.diffusicraft.worldgen.lod;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A square grid of {@link LodSampler}'s columns, kept so that a stack of voxel sections can be cut from it. Voxy draws
 * its far terrain from these (see {@code client.compat.voxy}).
 *
 * <p>A voxel is a cube of blocks, {@code size} on a side, and shows the highest block in it that is not air, as it
 * looks from above: water over the ground in the same voxel hides it. Voxy's own coarser levels keep the most opaque
 * block instead, which at 16 blocks a voxel turns every sea shallower than that into dry ground standing at sea level.
 * The sky lights the air at full strength, but not the caves: their air ({@link #CAVE}) is dark. Water loses one level
 * of light for each block between it and its surface, as it does in the world, and a lake in a cave has none. Blocks
 * that let light through, such as leaves and ice, take one level less than the open sky, or none under the ground, and
 * the others none.
 *
 * <p>A column that reaches above the consumer's ceiling sinks by the difference into the rock under it, so it keeps
 * its snow and soils and stands flat at that height, as it does for Distant Horizons.
 *
 * <p>A server sends grids to the clients that draw its far terrain through {@link #STREAM_CODEC}. A grid names its
 * blocks and biomes once, and each column its runs by their place in that list and by how far each starts and ends
 * from the one under it, so that most take a byte each.
 */
public final class LodGrid {
	/** Sky light of open air. */
	public static final int FULL_SKY = 15;
	/** Widest grid {@link #STREAM_CODEC} reads. */
	public static final int MAX_WIDTH = 64;
	/** Grids as a server sends them, with the ids of the blocks and biomes it shares with the client. */
	public static final StreamCodec<RegistryFriendlyByteBuf, LodGrid> STREAM_CODEC =
		codec(ByteBufCodecs.holderRegistry(Registries.BIOME));

	static final byte AIR = 0;
	static final byte WATER = 1;
	/** A block that lets light through. */
	static final byte CLEAR = 2;
	static final byte OPAQUE = 3;
	/** Air the sky does not reach: the caves, which hold {@link Blocks#CAVE_AIR}. */
	static final byte CAVE = 4;

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
	private boolean rough;

	/** How a grid is sampled where the terrain it needs has not been computed yet. */
	public enum Mode {
		/** In full, running the models over the terrain however long that takes. */
		FULL,
		/** At once, as finely as the terrain computed allows ({@link LodSampler#available}), and marked rough. */
		ROUGH,
		/**
		 * In full once the terrain is computed, and not at all until then: the models run over one more tile of it, if
		 * they are idle ({@link LodSampler#prepare}).
		 */
		REFINE
	}

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
	 * kept. Where players have been, the columns are those of the chunks as they were last saved ({@link LodStore}).
	 */
	public static LodGrid sample(LodSampler sampler, int minX, int minZ, int width, int step, int ceiling) {
		return Objects.requireNonNull(sample(sampler, minX, minZ, width, step, ceiling, Mode.FULL));
	}

	/** The grid as {@link #sample} samples it, or null if {@code mode} is {@link Mode#REFINE} and it is not ready. */
	public static @Nullable LodGrid sample(LodSampler sampler, int minX, int minZ, int width, int step, int ceiling,
										   Mode mode) {
		LodStore store = sampler.store();
		return store == null ? generate(sampler, minX, minZ, width, step, ceiling, mode) : store.overlay(minX, minZ,
			width, step, ceiling, () -> generate(sampler, minX, minZ, width, step, ceiling, mode));
	}

	/** The grid as {@link #sample} samples it, but as the terrain is generated, whatever players did to it. */
	public static LodGrid generate(LodSampler sampler, int minX, int minZ, int width, int step, int ceiling) {
		return Objects.requireNonNull(generate(sampler, minX, minZ, width, step, ceiling, Mode.FULL));
	}

	/**
	 * The grid as {@link #generate} samples it, or null if {@code mode} is {@link Mode#REFINE} and it is not ready. A
	 * grid that is a page of a level of detail of the store, generated in full before, is read back from it in full
	 * whatever the mode, and one generated in full is kept there.
	 */
	public static @Nullable LodGrid generate(LodSampler sampler, int minX, int minZ, int width, int step, int ceiling,
											 Mode mode) {
		LodStore store = sampler.store();
		int level = Integer.numberOfTrailingZeros(step);
		int span = LodStore.PAGE * step;
		boolean page = store != null && width == LodStore.PAGE && Integer.bitCount(step) == 1 && level < LodStore.LEVELS
			&& Math.floorMod(minX, span) == 0 && Math.floorMod(minZ, span) == 0;
		int pageX = Math.floorDiv(minX, span);
		int pageZ = Math.floorDiv(minZ, span);
		if (page) {
			LodGrid kept = store.generated(level, pageX, pageZ);
			if (kept != null) {
				return kept.under(ceiling);
			}
		}
		LodSampler.Detail detail = switch (mode) {
			case FULL -> LodSampler.Detail.FULL;
			case ROUGH -> sampler.available(minX, minZ, width, step);
			case REFINE -> sampler.prepare(minX, minZ, width, step) ? LodSampler.Detail.FULL : null;
		};
		if (detail == null) {
			return null;
		}
		// A page to keep is sampled with no ceiling, and sunk under the caller's as it is read back.
		boolean keep = page && detail == LodSampler.Detail.FULL;
		int sampled = keep ? Integer.MAX_VALUE : ceiling;
		LodGrid grid = new LodGrid(width);
		sampler.sample(minX, minZ, width, step, detail, (x, z, column) -> grid.add(column, sampled));
		grid.rough = detail != LodSampler.Detail.FULL;
		if (keep) {
			store.generated(level, pageX, pageZ, grid);
			return grid.under(ceiling);
		}
		return grid;
	}

	/** A copy of the grid with the columns that reach above {@code ceiling} sunk, as {@link #add(LodColumn, int)} does. */
	private LodGrid under(int ceiling) {
		LodGrid copy = new LodGrid(width);
		for (int column = 0; column < columns; column++) {
			copy.add(this, column, ceiling);
		}
		copy.rough = rough;
		return copy;
	}

	/**
	 * Whether the grid was sampled coarser than it can be, as {@link Mode#ROUGH} samples it where the terrain is not
	 * computed yet: without the rivers, or from the coarse map.
	 */
	public boolean rough() {
		return rough;
	}

	void rough(boolean rough) {
		this.rough = rough;
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
		// The runs that would sink under the bottom of the world, and the caves over them, give way to the rock above.
		int lowest = 0;
		while (sink > 0 && lowest + 1 < size && (column.top(lowest) - sink <= column.bottom(0)
			|| kind(column.state(lowest)) != OPAQUE)) {
			lowest++;
		}
		reserve(size - lowest);
		for (int run = lowest; run < size; run++) {
			BlockState state = column.state(run);
			states[runs] = state;
			// The lowest run keeps its bottom: the rock under a sunken column still reaches the bottom of the world.
			bottoms[runs] = run == lowest ? column.bottom(0) : column.bottom(run) - sink;
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

	/**
	 * Adds column {@code column} of {@code from}, sinking it as {@link #add(LodColumn, int)} does if it reaches above
	 * {@code ceiling}.
	 */
	void add(LodGrid from, int column, int ceiling) {
		int start = from.first[column];
		int end = from.first[column + 1];
		int sink = end == start ? 0 : Math.max(0, from.tops[end - 1] - ceiling);
		int lowest = start;
		while (sink > 0 && lowest + 1 < end && (from.tops[lowest] - sink <= from.bottoms[start]
			|| from.kinds[lowest] != OPAQUE)) {
			lowest++;
		}
		reserve(end - lowest);
		for (int run = lowest; run < end; run++) {
			states[runs] = from.states[run];
			bottoms[runs] = run == lowest ? from.bottoms[start] : from.bottoms[run] - sink;
			tops[runs] = from.tops[run] - sink;
			kinds[runs] = from.kinds[run];
			runs++;
		}
		int surface = from.surfaces[column];
		surfaces[columns] = surface == Integer.MIN_VALUE ? surface : surface - sink;
		biomes[columns] = from.biomes[column];
		columns++;
		first[columns] = runs;
		if (end > start) {
			highest = Math.max(highest, from.tops[end - 1] - sink);
		}
	}

	/** Whether column {@code column} holds what {@link #add(LodColumn, int)} would add of {@code other} with no ceiling. */
	boolean same(int column, LodColumn other) {
		int start = first[column];
		int size = other.size();
		if (first[column + 1] - start != size || !biomes[column].equals(other.biome())) {
			return false;
		}
		for (int run = 0; run < size; run++) {
			if (states[start + run] != other.state(run) || bottoms[start + run] != other.bottom(run)
				|| tops[start + run] != other.top(run)) {
				return false;
			}
		}
		int ground = other.ground();
		return surfaces[column] == (ground < 0 ? Integer.MIN_VALUE : other.top(ground) + other.waterDepth());
	}

	/** Adds a column with nothing in it. */
	void addEmpty(Holder<Biome> biome) {
		surfaces[columns] = Integer.MIN_VALUE;
		biomes[columns] = biome;
		columns++;
		first[columns] = runs;
	}

	/** Number of runs in a column. */
	int runs(int column) {
		return first[column + 1] - first[column];
	}

	/**
	 * Block Y right above the water over a column's ground, or above its ground where it is dry;
	 * {@link Integer#MIN_VALUE} for a column without ground.
	 */
	int surface(int column) {
		return surfaces[column];
	}

	/** The number of a column's lowest run; its others follow it, bottom first. */
	int firstRun(int column) {
		return first[column];
	}

	BlockState state(int run) {
		return states[run];
	}

	int bottom(int run) {
		return bottoms[run];
	}

	int top(int run) {
		return tops[run];
	}

	/** {@link #AIR}, {@link #WATER}, {@link #CLEAR}, {@link #OPAQUE} or {@link #CAVE}. */
	byte kind(int run) {
		return kinds[run];
	}

	/** Makes room for {@code more} runs. */
	private void reserve(int more) {
		if (runs + more > states.length) {
			int capacity = Math.max(runs + more, states.length * 2);
			states = Arrays.copyOf(states, capacity);
			bottoms = Arrays.copyOf(bottoms, capacity);
			tops = Arrays.copyOf(tops, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
		}
	}

	/** {@link #STREAM_CODEC}, with the biomes written by {@code biomes}. */
	static <B extends FriendlyByteBuf> StreamCodec<B, LodGrid> codec(StreamCodec<? super B, Holder<Biome>> biomes) {
		return codec(ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), biomes);
	}

	/** {@link #STREAM_CODEC}, with the blocks written by {@code states} and the biomes by {@code biomes}. */
	static <B extends FriendlyByteBuf> StreamCodec<B, LodGrid> codec(StreamCodec<? super B, BlockState> states,
																	  StreamCodec<? super B, Holder<Biome>> biomes) {
		return StreamCodec.of((buf, grid) -> grid.write(buf, states, biomes), buf -> read(buf, states, biomes));
	}

	private <B extends FriendlyByteBuf> void write(B buf, StreamCodec<? super B, BlockState> stateCodec,
												   StreamCodec<? super B, Holder<Biome>> biomeCodec) {
		Map<BlockState, Integer> stateIndices = new IdentityHashMap<>();
		List<BlockState> statePalette = new ArrayList<>();
		int[] runStates = new int[runs];
		for (int run = 0; run < runs; run++) {
			runStates[run] = stateIndices.computeIfAbsent(states[run], state -> {
				statePalette.add(state);
				return statePalette.size() - 1;
			});
		}
		Map<Holder<Biome>, Integer> biomeIndices = new IdentityHashMap<>();
		List<Holder<Biome>> biomePalette = new ArrayList<>();
		int[] columnBiomes = new int[columns];
		for (int column = 0; column < columns; column++) {
			columnBiomes[column] = biomeIndices.computeIfAbsent(biomes[column], biome -> {
				biomePalette.add(biome);
				return biomePalette.size() - 1;
			});
		}
		buf.writeVarInt(width);
		buf.writeVarInt(statePalette.size());
		for (BlockState state : statePalette) {
			stateCodec.encode(buf, state);
		}
		buf.writeVarInt(biomePalette.size());
		for (Holder<Biome> biome : biomePalette) {
			biomeCodec.encode(buf, biome);
		}
		for (int column = 0; column < columns; column++) {
			buf.writeVarInt(columnBiomes[column]);
			buf.writeVarInt(first[column + 1] - first[column]);
			int below = 0;
			for (int run = first[column]; run < first[column + 1]; run++) {
				buf.writeVarInt(runStates[run]);
				buf.writeVarInt(zigzag(bottoms[run] - below));
				buf.writeVarInt(zigzag(tops[run] - bottoms[run]));
				below = tops[run];
			}
			// 0 for a column with no ground; otherwise how far the surface is from the column's top, plus one.
			buf.writeVarInt(surfaces[column] == Integer.MIN_VALUE ? 0 : zigzag(surfaces[column] - below) + 1);
		}
	}

	@SuppressWarnings("unchecked")
	private static <B extends FriendlyByteBuf> LodGrid read(B buf, StreamCodec<? super B, BlockState> stateCodec,
															StreamCodec<? super B, Holder<Biome>> biomeCodec) {
		int width = buf.readVarInt();
		if (width < 1 || width > MAX_WIDTH) {
			throw new DecoderException("A grid of far terrain " + width + " columns wide");
		}
		// Every entry and run takes at least a byte, so no count can be larger than what is left to read.
		BlockState[] statePalette = new BlockState[count(buf, 1)];
		for (int i = 0; i < statePalette.length; i++) {
			statePalette[i] = stateCodec.decode(buf);
		}
		Holder<Biome>[] biomePalette = new Holder[count(buf, 1)];
		for (int i = 0; i < biomePalette.length; i++) {
			biomePalette[i] = biomeCodec.decode(buf);
		}
		LodGrid grid = new LodGrid(width);
		for (int column = 0; column < width * width; column++) {
			Holder<Biome> biome = biomePalette[index(buf, biomePalette.length)];
			int size = count(buf, 3);
			grid.reserve(size);
			int below = 0;
			for (int i = 0; i < size; i++) {
				BlockState state = statePalette[index(buf, statePalette.length)];
				int bottom = below + unzigzag(buf.readVarInt());
				int top = bottom + unzigzag(buf.readVarInt());
				grid.states[grid.runs] = state;
				grid.bottoms[grid.runs] = bottom;
				grid.tops[grid.runs] = top;
				grid.kinds[grid.runs] = kind(state);
				grid.runs++;
				below = top;
			}
			int surface = buf.readVarInt();
			grid.surfaces[column] = surface == 0 ? Integer.MIN_VALUE : below + unzigzag(surface - 1);
			grid.biomes[column] = biome;
			grid.columns++;
			grid.first[grid.columns] = grid.runs;
			if (size > 0) {
				grid.highest = Math.max(grid.highest, below);
			}
		}
		return grid;
	}

	/** A count read from the buffer, of entries that take at least {@code bytes} each. */
	private static int count(FriendlyByteBuf buf, int bytes) {
		int count = buf.readVarInt();
		if (count < 0 || count > buf.readableBytes() / bytes) {
			throw new DecoderException("A grid of far terrain cut short: " + count + " entries");
		}
		return count;
	}

	private static int index(FriendlyByteBuf buf, int size) {
		int index = buf.readVarInt();
		if (index < 0 || index >= size) {
			throw new DecoderException("A grid of far terrain names entry " + index + " of " + size);
		}
		return index;
	}

	/** Small numbers of either sign as small unsigned ones, which take a byte as a VarInt. */
	private static int zigzag(int value) {
		return value << 1 ^ value >> 31;
	}

	private static int unzigzag(int value) {
		return value >>> 1 ^ -(value & 1);
	}

	static byte kind(BlockState state) {
		if (state.isAir()) {
			return state.is(Blocks.CAVE_AIR) ? CAVE : AIR;
		}
		if (state.is(Blocks.WATER)) {
			return WATER;
		}
		return state.canOcclude() ? OPAQUE : CLEAR;
	}

	/**
	 * The voxels of one column, from its runs {@code from} up to {@code to}: for each voxel the run whose block it
	 * shows, or −1 for air, and its sky light: none in a cave, nor in water or a block that lets light through under
	 * the ground.
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
			boolean cave = false;
			while (shown >= from && tops[shown] > low && (kinds[shown] == AIR || kinds[shown] == CAVE)) {
				cave |= kinds[shown] == CAVE;
				shown--;
			}
			if (shown < from || tops[shown] <= low) {
				runs[voxel] = -1;
				sky[voxel] = (byte) (cave ? 0 : FULL_SKY);
			} else if (kinds[shown] == WATER) {
				runs[voxel] = shown;
				int highestWater = Math.min(high, tops[shown]) - 1;
				sky[voxel] = (byte) Math.max(0, FULL_SKY - (surface - highestWater));
			} else {
				runs[voxel] = shown;
				sky[voxel] = (byte) (kinds[shown] == CLEAR && tops[shown] >= surface ? FULL_SKY - 1 : 0);
			}
		}
	}
}
