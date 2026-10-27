package io.github.elpideus.diffusicraft.worldgen.lod;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The columns of a chunk as it stands, for the levels of detail of the places a player has been: what was built and
 * dug there shows from afar as the chunk is, not as {@link LodSampler} would grow it.
 *
 * <p>A column keeps what a far view shows: the blocks that stop a player, and water, lava and snow over them. Plants,
 * torches, rails and the other blocks one walks through count as air. A run that a neighbouring column does not hide
 * shows its side, so a column keeps its blocks one by one down to {@value #SURFACE_DEPTH} blocks under the lowest
 * ground around it, and below that a single run of the block there reaches the bottom of the world. The ground is the
 * highest block that is neither leaves nor ice: the roof of a house, and the sea floor under the ice of a frozen sea,
 * as {@link LodSampler} has it. Where the sky lights what lies under that floor, as it lights the air under a bridge,
 * a pier, an overhang or a floating build and the water under them, the column keeps it down to the next ground below.
 *
 * <p>The caves under that, which the sky does not reach, the column keeps as {@link Blocks#CAVE_AIR}, which the far
 * terrain keeps dark, with the water and lava in them and the blocks of their floors and ceilings, as
 * {@link LodSampler} keeps the caverns of the Hollows, and so it keeps the air the sky leaves dark over the floor, such
 * as a closed room under its roof. The rock between them comes in bands of {@value #BAND} blocks, each the block in
 * its middle, around the heights the chunk's caves open at, so that their walls show the strata; below and above that
 * it is a single run. A chunk with no caves keeps a single run of rock under its floors.
 *
 * <p>It reads the copy of the chunk's sections that vanilla takes when it saves the chunk, or that a client takes of
 * the chunks it lets go of, so it can run on any thread. Not thread-safe: each thread needs its own.
 */
public final class ChunkColumns {
	/** Blocks under the lowest ground around a column that it keeps one by one. */
	static final int SURFACE_DEPTH = 4;
	/** How far under its floor a column looks for the block its deepest run is made of. */
	private static final int ROCK_SEARCH = 16;
	/** Height of the bands of rock around the caves, each one block. */
	private static final int BAND = 16;
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();

	private final Predicate<BlockState> solid;
	private final Predicate<BlockState> leaves;
	private final int[] tops = new int[256];
	private final int[] grounds = new int[256];
	/** The block Y each column keeps its blocks one by one from. */
	private final int[] floors = new int[256];
	/** Whether each section may hold anything but solid blocks. */
	private boolean[] porous = new boolean[0];
	/** The heights the bands of rock around the chunk's caves take in, or an empty range in a chunk without caves. */
	private int bandLow;
	private int bandHigh;
	private final LodColumn column = new LodColumn();
	private LevelChunkSection[] sections = new LevelChunkSection[0];
	private @Nullable DataLayer[] sky = new DataLayer[0];
	/** For each section, the nearest at or above it whose sky light was kept, or {@code sky.length} where none is. */
	private int[] layers = new int[0];
	private int minSectionY;
	/** Block Y the deepest run of each column reaches down to. */
	private int bottom;

	/** Columns as the world's tags have them: the blocks that block motion, and the leaves among them. */
	public ChunkColumns() {
		this(state -> state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP), state -> state.is(BlockTags.LEAVES));
	}

	ChunkColumns(Predicate<BlockState> solid, Predicate<BlockState> leaves) {
		this.solid = solid;
		this.leaves = leaves;
	}

	/**
	 * Reads the columns of a chunk from the copy vanilla saves. Returns {@code false}, and reads nothing, for a chunk
	 * without sections.
	 */
	public boolean read(SerializableChunkData data, LodSampler.Sink sink) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			if (section.chunkSection() != null) {
				min = Math.min(min, section.y());
				max = Math.max(max, section.y());
			}
		}
		if (min > max) {
			return false;
		}
		LevelChunkSection[] sections = new LevelChunkSection[max - min + 1];
		DataLayer[] sky = new DataLayer[sections.length];
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			if (section.chunkSection() != null) {
				sections[section.y() - min] = section.chunkSection();
				sky[section.y() - min] = section.skyLight();
			}
		}
		for (int i = 0; i < sections.length; i++) {
			if (sections[i] == null) {
				sections[i] = new LevelChunkSection(data.containerFactory());
			}
		}
		read(sections, sky, min, sink);
		return true;
	}

	/**
	 * Reads the columns of a chunk from its sections, bottom first from section Y {@code minSectionY}, none of them
	 * {@code null}, and their sky light, {@code null} where vanilla keeps none (see {@link #light}). The sink gets each
	 * column at its place in the chunk, {@code (0, 0)} to {@code (15, 15)}.
	 */
	void read(LevelChunkSection[] sections, @Nullable DataLayer[] sky, int minSectionY, LodSampler.Sink sink) {
		read(sections, sky, minSectionY, SectionPos.sectionToBlockCoord(minSectionY), sink);
	}

	/**
	 * Reads the columns of a chunk from some of its sections, as {@link #read(LevelChunkSection[], DataLayer[], int,
	 * LodSampler.Sink)} does, for a chunk whose sections below {@code minSectionY} were left out: the deepest run of
	 * each column reaches on down to block Y {@code bottom}, the bottom of the world, made of the block it ends on.
	 */
	public void read(LevelChunkSection[] sections, @Nullable DataLayer[] sky, int minSectionY, int bottom,
					 LodSampler.Sink sink) {
		this.sections = sections;
		this.sky = sky;
		if (layers.length < sky.length) {
			layers = new int[sky.length];
		}
		for (int i = sky.length - 1, layer = sky.length; i >= 0; i--) {
			layer = sky[i] != null ? i : layer;
			layers[i] = layer;
		}
		this.minSectionY = minSectionY;
		this.bottom = Math.min(bottom, SectionPos.sectionToBlockCoord(minSectionY));
		int minY = SectionPos.sectionToBlockCoord(minSectionY);
		int highest = sections.length - 1;
		while (highest >= 0 && sections[highest].hasOnlyAir()) {
			highest--;
		}
		int start = SectionPos.sectionToBlockCoord(minSectionY + highest + 1) - 1;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = z * 16 + x;
				tops[k] = Integer.MIN_VALUE;
				grounds[k] = Integer.MIN_VALUE;
				for (int y = start; y >= minY; y--) {
					BlockState state = kept(get(x, y, z));
					if (state == null) {
						continue;
					}
					if (tops[k] == Integer.MIN_VALUE) {
						tops[k] = y + 1;
					}
					if (ground(state)) {
						grounds[k] = y + 1;
						break;
					}
				}
			}
		}
		if (porous.length < sections.length) {
			porous = new boolean[sections.length];
		}
		for (int i = 0; i < sections.length; i++) {
			porous[i] = sections[i].maybeHas(state -> !solid.test(state));
		}
		int lowestCave = Integer.MAX_VALUE;
		int highestCave = Integer.MIN_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = z * 16 + x;
				if (tops[k] == Integer.MIN_VALUE) {
					continue;
				}
				floors[k] = floor(x, z, minY);
				if (grounds[k] == Integer.MIN_VALUE) {
					continue;
				}
				for (int y = floors[k] - 1; y >= minY; y--) {
					int index = SectionPos.blockToSectionCoord(y) - minSectionY;
					if (!porous[index]) {
						y = SectionPos.sectionToBlockCoord(minSectionY + index);
					} else if (!solid.test(get(x, y, z))) {
						lowestCave = Math.min(lowestCave, y);
						highestCave = Math.max(highestCave, y);
					}
				}
			}
		}
		bandLow = lowestCave > highestCave ? 0 : Math.floorDiv(lowestCave, BAND) * BAND - BAND;
		bandHigh = lowestCave > highestCave ? 0 : Math.floorDiv(highestCave, BAND) * BAND + 2 * BAND;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				column(x, z, minY);
				sink.accept(x, z, column);
			}
		}
	}

	private void column(int x, int z, int minY) {
		int k = z * 16 + x;
		int top = tops[k];
		int ground = grounds[k];
		int surface = ground != Integer.MIN_VALUE ? ground : top;
		column.reset(biome(x, surface == Integer.MIN_VALUE ? minY : surface - 1, z));
		if (top == Integer.MIN_VALUE) {
			return;
		}
		int floor = floors[k];
		// A column that stands on nothing, all leaves or water, hangs in the air.
		if (floor > bottom && ground != Integer.MIN_VALUE) {
			BlockState rock = rock(x, z, floor, minY, ground);
			if (bandLow < bandHigh) {
				underground(x, z, floor, minY, rock);
			} else {
				column.add(rock, bottom, floor);
			}
		}
		for (int y = floor; y < top; y++) {
			BlockState state = kept(get(x, y, z));
			if (state != null || column.size() > 0) {
				column.add(state != null ? state : dark(x, y, z) ? CAVE_AIR : AIR, y, y + 1);
			}
			if (y + 1 == ground) {
				column.markGround();
			}
		}
		if (ground != Integer.MIN_VALUE) {
			int depth = 0;
			for (int run = column.ground() + 1; run < column.size() && isWater(column.state(run)); run++) {
				depth += column.top(run) - column.bottom(run);
			}
			column.waterDepth(depth);
		}
	}

	/**
	 * The block Y a column keeps its blocks one by one from: {@value #SURFACE_DEPTH} blocks under the lowest ground
	 * around, or the lowest block of a column that stands on nothing, and further down under what the sky lights.
	 */
	private int floor(int x, int z, int minY) {
		int k = z * 16 + x;
		int top = tops[k];
		int ground = grounds[k];
		int low = ground != Integer.MIN_VALUE ? ground : top;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int n = Math.clamp(z + dz, 0, 15) * 16 + Math.clamp(x + dx, 0, 15);
				int around = grounds[n] != Integer.MIN_VALUE ? grounds[n] : tops[n];
				if (around != Integer.MIN_VALUE) {
					low = Math.min(low, around);
				}
			}
		}
		int floor = Math.max(minY, low - SURFACE_DEPTH);
		if (ground != Integer.MIN_VALUE) {
			while (floor > minY && open(x, floor - 1, z)) {
				int y = floor - 1;
				while (y >= minY && !isGround(get(x, y, z))) {
					y--;
				}
				floor = Math.max(minY, y + 1 - SURFACE_DEPTH);
			}
		}
		return floor;
	}

	/**
	 * Adds what lies under a column's floor, from the bottom of the world up: the caves' air, dark as the far terrain
	 * draws it unless the sky lights it, the water and lava in them, and the rock around them (see {@link #stretch}).
	 * {@code upper} is the rock right under the floor.
	 */
	private void underground(int x, int z, int floor, int minY, BlockState upper) {
		// The stretch of solid blocks the walk is in, and whether a cave lies under it.
		int solidFrom = bottom;
		boolean ceiling = false;
		for (int y = minY; y < floor; y++) {
			int index = SectionPos.blockToSectionCoord(y) - minSectionY;
			boolean section = !porous[index];
			if (section || solid.test(get(x, y, z))) {
				if (solidFrom == Integer.MIN_VALUE) {
					solidFrom = y;
					ceiling = true;
				}
				if (section) {
					// Nothing but solid blocks to the top of the section.
					y = Math.min(floor, SectionPos.sectionToBlockCoord(minSectionY + index + 1)) - 1;
				}
				continue;
			}
			if (solidFrom != Integer.MIN_VALUE) {
				stretch(x, z, solidFrom, y, ceiling, true, upper);
				solidFrom = Integer.MIN_VALUE;
			}
			BlockState state = kept(get(x, y, z));
			column.add(state != null ? state : lit(x, y, z) ? AIR : CAVE_AIR, y, y + 1);
		}
		if (solidFrom != Integer.MIN_VALUE) {
			stretch(x, z, solidFrom, floor, ceiling, false, upper);
		}
	}

	/**
	 * Adds the stretch {@code [from, to)} of solid blocks under a column's floor: the ceiling of the cave under it and
	 * the floor of the one over it as they are, and between them, around the heights the chunk's caves open at, bands
	 * of rock, each the block in its middle. Below the bands the rock is one run, and above them {@code upper}.
	 */
	private void stretch(int x, int z, int from, int to, boolean ceiling, boolean floor, BlockState upper) {
		if (to <= from) {
			return;
		}
		if (ceiling) {
			column.add(face(x, from, z, from, to, upper), from, from + 1);
			from++;
		}
		BlockState top = null;
		if (floor && to > from) {
			top = face(x, to - 1, z, from, to, upper);
			to--;
		}
		for (int y = from; y < to; ) {
			int end = Math.min(to, y >= bandHigh ? to : y < bandLow ? bandLow : Math.floorDiv(y, BAND) * BAND + BAND);
			column.add(y >= bandHigh ? upper : band(x, z, y, end, upper), y, end);
			y = end;
		}
		if (top != null) {
			column.add(top, to, to + 1);
		}
	}

	/**
	 * The block of a cave's floor or ceiling at {@code (x, y, z)}, in the stretch of rock {@code [from, to)}. An ore
	 * there would stand for the whole floor or ceiling far off: the rock of its band does instead.
	 */
	private BlockState face(int x, int y, int z, int from, int to, BlockState upper) {
		BlockState state = get(x, y, z);
		if (!state.is(ConventionalBlockTags.ORES)) {
			return state;
		}
		if (y >= bandHigh) {
			return upper;
		}
		int low = y < bandLow ? from : Math.floorDiv(y, BAND) * BAND;
		int high = y < bandLow ? bandLow : low + BAND;
		return band(x, z, Math.max(from, low), Math.min(to, high), upper);
	}

	/**
	 * The rock of the band {@code [from, to)} of a column: the block in its middle, or the nearest that is no ore. The
	 * sections left out under the lowest that was read take the rock at the bottom of that one.
	 */
	private BlockState band(int x, int z, int from, int to, BlockState upper) {
		from = Math.max(from, SectionPos.sectionToBlockCoord(minSectionY));
		to = Math.max(to, from + 1);
		int middle = from + (to - from) / 2;
		BlockState state = get(x, middle, z);
		for (int d = 1; state.is(ConventionalBlockTags.ORES) && (middle - d >= from || middle + d < to); d++) {
			for (int y : new int[]{middle + d, middle - d}) {
				if (y >= from && y < to && !get(x, y, z).is(ConventionalBlockTags.ORES)) {
					return get(x, y, z);
				}
			}
		}
		return solid.test(state) ? state : upper;
	}

	/**
	 * The block the deepest run is made of: the first one under the floor that the ground could be made of, or else
	 * the water there, as in a sea too deep for the sky to light its floor.
	 */
	private BlockState rock(int x, int z, int floor, int minY, int ground) {
		for (int y = floor - 1; y >= Math.max(minY, floor - ROCK_SEARCH); y--) {
			BlockState state = get(x, y, z);
			if (isGround(state)) {
				return kept(state);
			}
		}
		BlockState under = kept(get(x, floor - 1, z));
		return under != null ? under : get(x, ground - 1, z);
	}

	/** Whether the sky lights a block that a column leaves out, or the water there. */
	private boolean open(int x, int y, int z) {
		BlockState state = kept(get(x, y, z));
		return (state == null || isWater(state)) && lit(x, y, z);
	}

	/** Whether the sky lights block {@code (x, y, z)}, as far as is known. */
	private boolean lit(int x, int y, int z) {
		return light(x, y, z) > 0;
	}

	/** Whether the sky leaves block {@code (x, y, z)} in the dark, as a closed room under a roof. */
	private boolean dark(int x, int y, int z) {
		return light(x, y, z) == 0;
	}

	/**
	 * The sky light at block {@code (x, y, z)}, or -1 where it is not known. Vanilla keeps no light for a section
	 * that holds what the bottom of the nearest section above it holds, and reads full light above them all; but it
	 * keeps none for a section all dark either. So a section without light is dark under a dark block, and not known
	 * under a lit one, nor anywhere in a chunk with no light kept at all.
	 */
	private int light(int x, int y, int z) {
		int index = SectionPos.blockToSectionCoord(y) - minSectionY;
		if (index < 0 || sky.length == 0 || layers[0] == sky.length) {
			return -1;
		}
		int layer = index < sky.length ? layers[index] : sky.length;
		if (layer == sky.length) {
			return 15;
		}
		int light = sky[layer].get(x, layer == index ? y & 15 : 0, z);
		return layer == index || light == 0 ? light : -1;
	}

	private boolean isGround(BlockState state) {
		BlockState kept = kept(state);
		return kept != null && ground(kept);
	}

	/** The block a far view shows for {@code state}, or {@code null} for one it leaves out. */
	private @Nullable BlockState kept(BlockState state) {
		if (state.isAir()) {
			return null;
		}
		if (solid.test(state)) {
			return state;
		}
		FluidState fluid = state.getFluidState();
		if (fluid.getType().isSame(Fluids.WATER)) {
			return WATER;
		}
		if (fluid.getType().isSame(Fluids.LAVA)) {
			return LAVA;
		}
		return state.is(Blocks.SNOW) ? SNOW : null;
	}

	private boolean ground(BlockState kept) {
		return solid.test(kept) && !leaves.test(kept) && !kept.is(Blocks.ICE);
	}

	private static boolean isWater(BlockState state) {
		return state == WATER || state.is(Blocks.ICE);
	}

	private BlockState get(int x, int y, int z) {
		int index = SectionPos.blockToSectionCoord(y) - minSectionY;
		if (index < 0 || index >= sections.length) {
			return AIR;
		}
		return sections[index].getBlockState(x, y & 15, z);
	}

	private Holder<Biome> biome(int x, int y, int z) {
		int index = Math.clamp(SectionPos.blockToSectionCoord(y) - minSectionY, 0, sections.length - 1);
		return sections[index].getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y) & 3, QuartPos.fromBlock(z));
	}
}
