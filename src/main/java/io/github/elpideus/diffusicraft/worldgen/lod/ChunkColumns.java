package io.github.elpideus.diffusicraft.worldgen.lod;

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
 * Caves and closed rooms, which the sky does not reach, are filled in, as Distant Horizons fills them.
 *
 * <p>It reads the copy of the chunk's sections that vanilla takes when it saves the chunk, so it can run on any
 * thread. Not thread-safe: each thread needs its own.
 */
public final class ChunkColumns {
	/** Blocks under the lowest ground around a column that it keeps one by one. */
	static final int SURFACE_DEPTH = 4;
	/** How far under its floor a column looks for the block its deepest run is made of. */
	private static final int ROCK_SEARCH = 16;
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final Predicate<BlockState> solid;
	private final Predicate<BlockState> leaves;
	private final int[] tops = new int[256];
	private final int[] grounds = new int[256];
	private final LodColumn column = new LodColumn();
	private LevelChunkSection[] sections = new LevelChunkSection[0];
	private @Nullable DataLayer[] sky = new DataLayer[0];
	private int minSectionY;

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
	 * {@code null}, and their sky light, {@code null} where it is dark. The sink gets each column at its place in the
	 * chunk, {@code (0, 0)} to {@code (15, 15)}.
	 */
	void read(LevelChunkSection[] sections, @Nullable DataLayer[] sky, int minSectionY, LodSampler.Sink sink) {
		this.sections = sections;
		this.sky = sky;
		this.minSectionY = minSectionY;
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
		// Down to under the lowest ground around, or the lowest block of a column that stands on nothing.
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
		// A column that stands on nothing, all leaves or water, hangs in the air.
		if (floor > minY && ground != Integer.MIN_VALUE) {
			column.add(rock(x, z, floor, minY, ground), minY, floor);
		}
		for (int y = floor; y < top; y++) {
			BlockState state = kept(get(x, y, z));
			if (state != null || column.size() > 0) {
				column.add(state == null ? AIR : state, y, y + 1);
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
		if (state != null && !isWater(state)) {
			return false;
		}
		int index = SectionPos.blockToSectionCoord(y) - minSectionY;
		DataLayer light = index >= 0 && index < sky.length ? sky[index] : null;
		return light != null && light.get(x, y & 15, z) > 0;
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
