package io.github.elpideus.diffusicraft.worldgen.lod;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The structures a far view shows: those that stand on the surface, the villages, temples, outposts, shipwrecks and
 * the like, block by block where its columns are a block apart and in outline further out. The chunk generator's own
 * choice picks them, from the same seeds,
 * and their pieces are placed as it places them, in a {@link StructureSketch} of the far view's ground, so that they
 * stand where they will once their chunks generate and look from afar as they will then. The giant trees, which grow
 * as structures too, are {@link GreatTrees}'s.
 *
 * <p>Only grids whose columns are at most {@value #MAX_STEP} blocks apart show them: further out a house is narrower
 * than a column. Thread-safe.
 */
final class FarStructures {
	/** Widest step between a grid's columns at which it shows the structures. */
	static final int MAX_STEP = 8;
	/** Chunks a structure reaches beyond the one it starts in, at most, as the chunk generator has it. */
	private static final int REACH = 8;
	/** Starts kept in memory, those that came to nothing included, before they are all dropped. */
	private static final int CACHED_STARTS = 4096;
	private static final GenerationStep.Decoration STEP = GenerationStep.Decoration.SURFACE_STRUCTURES;
	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * What the structures put in a cell of a grid: the blocks they stack over the ground, {@code states[i]} at block Y
	 * {@code ys[i]}, lowest first, with air between them; and {@code paving}, which takes the place of the ground's
	 * top block, or {@code null}.
	 */
	record Cell(int[] ys, BlockState[] states, @Nullable BlockState paving) {
	}

	/** A set of structures spread over the world, one start at most in each cell of its grid. */
	private record Spread(StructureSet set, RandomSpreadStructurePlacement placement) {
	}

	private record Key(int spread, long chunk) {
	}

	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final ChunkGeneratorStructureState state;
	private final RandomState randomState;
	private final List<Spread> spreads;
	/** The index of each structure among those placed in the same step, which seeds its placement. */
	private final Reference2IntOpenHashMap<Structure> order;
	private final ConcurrentHashMap<Key, StructureStart> starts = new ConcurrentHashMap<>();
	private final Set<Structure> failed = ConcurrentHashMap.newKeySet();

	private FarStructures(ServerLevel level, List<Spread> spreads, Reference2IntOpenHashMap<Structure> order) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.state = level.getChunkSource().getGeneratorState();
		this.randomState = state.randomState();
		this.spreads = spreads;
		this.order = order;
	}

	/** The structures of a world, or {@code null} if it generates none that stand on the surface. */
	static @Nullable FarStructures of(ServerLevel level) {
		if (!level.structureManager().shouldGenerateStructures()) {
			return null;
		}
		// As the chunk generator counts them when it places them.
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Reference2IntOpenHashMap<Structure> order = new Reference2IntOpenHashMap<>();
		registry.stream().filter(structure -> structure.step() == STEP)
			.forEach(structure -> order.put(structure, order.size()));
		List<Spread> spreads = new ArrayList<>();
		for (Holder<StructureSet> holder : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
			StructureSet set = holder.value();
			if (set.placement() instanceof RandomSpreadStructurePlacement placement
				&& set.structures().stream().anyMatch(entry -> entry.structure().value().step() == STEP)) {
				spreads.add(new Spread(set, placement));
			}
		}
		return spreads.isEmpty() ? null : new FarStructures(level, spreads, order);
	}

	/**
	 * What the structures put in the cells of the grid of {@code width}² columns {@code step} blocks apart whose
	 * corner block is {@code (minX, minZ)}, row by row, standing on {@code ground} made of {@code rock}; or
	 * {@code null} if they put nothing in it.
	 */
	@Nullable Cell[] sketch(int minX, int minZ, int width, int step, StructureSketch.Ground ground, BiomeResolver biomes,
							BlockState rock) {
		int maxX = minX + width * step - 1;
		int maxZ = minZ + width * step - 1;
		List<StructureStart> found = starts(minX, minZ, maxX, maxZ);
		if (found.isEmpty()) {
			return null;
		}
		StructureSketch sketch = new StructureSketch(level, ground, rock, biomes);
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		for (StructureStart start : found) {
			place(start, sketch, random, minX, minZ, maxX, maxZ);
		}
		return cells(sketch.columns(), sketch::land, minX, minZ, width, step);
	}

	/** The starts of the structures that reach into the blocks from {@code (minX, minZ)} to {@code (maxX, maxZ)}. */
	private List<StructureStart> starts(int minX, int minZ, int maxX, int maxZ) {
		int fromX = SectionPos.blockToSectionCoord(minX) - REACH;
		int fromZ = SectionPos.blockToSectionCoord(minZ) - REACH;
		int toX = SectionPos.blockToSectionCoord(maxX) + REACH;
		int toZ = SectionPos.blockToSectionCoord(maxZ) + REACH;
		List<StructureStart> found = new ArrayList<>();
		for (int s = 0; s < spreads.size(); s++) {
			RandomSpreadStructurePlacement placement = spreads.get(s).placement();
			int spacing = placement.spacing();
			for (int gz = Math.floorDiv(fromZ, spacing); gz <= Math.floorDiv(toZ, spacing); gz++) {
				for (int gx = Math.floorDiv(fromX, spacing); gx <= Math.floorDiv(toX, spacing); gx++) {
					ChunkPos chunk = placement.getPotentialStructureChunk(state.getLevelSeed(), gx * spacing,
						gz * spacing);
					if (chunk.x() < fromX || chunk.x() > toX || chunk.z() < fromZ || chunk.z() > toZ) {
						continue;
					}
					StructureStart start = start(s, chunk);
					if (!start.isValid() || start.getStructure().step() != STEP) {
						continue;
					}
					BoundingBox box = start.getBoundingBox();
					if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) {
						found.add(start);
					}
				}
			}
		}
		return found;
	}

	/** The start of spread {@code s} in a chunk, or {@link StructureStart#INVALID_START} if none starts there. */
	private StructureStart start(int s, ChunkPos chunk) {
		Key key = new Key(s, chunk.pack());
		StructureStart start = starts.get(key);
		if (start != null) {
			return start;
		}
		// Made outside the map, which would block every other thread meanwhile.
		start = generate(spreads.get(s), chunk);
		if (starts.size() >= CACHED_STARTS) {
			starts.clear();
		}
		StructureStart had = starts.putIfAbsent(key, start);
		return had != null ? had : start;
	}

	/** Picks the structure of a spread that starts in a chunk as the chunk generator does ({@code createStructures}). */
	private StructureStart generate(Spread spread, ChunkPos chunk) {
		if (!spread.placement().isStructureChunk(state, chunk.x(), chunk.z())) {
			return StructureStart.INVALID_START;
		}
		Climate.Sampler climate = randomState.createClimateSampler(SamplerContext.builder().enableCaches().build());
		List<StructureSet.StructureSelectionEntry> entries = spread.set().structures();
		if (entries.size() == 1) {
			return generate(entries.getFirst(), chunk, climate);
		}
		List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(state.getLevelSeed(), chunk.x(), chunk.z());
		int total = 0;
		for (StructureSet.StructureSelectionEntry option : options) {
			total += option.weight();
		}
		while (!options.isEmpty()) {
			int choice = random.nextInt(total);
			int index = 0;
			for (StructureSet.StructureSelectionEntry option : options) {
				choice -= option.weight();
				if (choice < 0) {
					break;
				}
				index++;
			}
			StructureSet.StructureSelectionEntry selected = options.get(index);
			StructureStart start = generate(selected, chunk, climate);
			if (start.isValid()) {
				return start;
			}
			options.remove(index);
			total -= selected.weight();
		}
		return StructureStart.INVALID_START;
	}

	private StructureStart generate(StructureSet.StructureSelectionEntry entry, ChunkPos chunk,
									Climate.Sampler climate) {
		Structure structure = entry.structure().value();
		try {
			return structure.generate(entry.structure(), level.dimension(), level.registryAccess(), generator,
				generator.getBiomeSource(), climate, randomState, level.getStructureTemplateManager(),
				state.getLevelSeed(), chunk, 0, level, structure.biomes()::contains);
		} catch (RuntimeException e) {
			warn(structure, e);
			return StructureStart.INVALID_START;
		}
	}

	/**
	 * Places the pieces of a start that lie in the blocks from {@code (minX, minZ)} to {@code (maxX, maxZ)}, chunk by
	 * chunk, as the chunk generator places them ({@code applyBiomeDecoration}).
	 */
	private void place(StructureStart start, StructureSketch sketch, WorldgenRandom random, int minX, int minZ,
					   int maxX, int maxZ) {
		BoundingBox box = start.getBoundingBox();
		int index = order.getInt(start.getStructure());
		try {
			for (int cz = SectionPos.blockToSectionCoord(Math.max(minZ, box.minZ()));
				 cz <= SectionPos.blockToSectionCoord(Math.min(maxZ, box.maxZ())); cz++) {
				for (int cx = SectionPos.blockToSectionCoord(Math.max(minX, box.minX()));
					 cx <= SectionPos.blockToSectionCoord(Math.min(maxX, box.maxX())); cx++) {
					int x = SectionPos.sectionToBlockCoord(cx);
					int z = SectionPos.sectionToBlockCoord(cz);
					random.setFeatureSeed(random.setDecorationSeed(level.getSeed(), x, z), index, STEP.ordinal());
					BoundingBox chunkBox = new BoundingBox(x, level.getMinY() + 1, z, x + 15, level.getMaxY(), z + 15);
					// Some pieces settle their height as they are first placed: one sketch at a time.
					synchronized (start) {
						start.placeInChunk(sketch, level.structureManager(), generator, random, chunkBox,
							new ChunkPos(cx, cz));
					}
				}
			}
		} catch (RuntimeException e) {
			warn(start.getStructure(), e);
		}
	}

	private void warn(Structure structure, RuntimeException e) {
		// The world closed under the grid, and its terrain with it: the far terrain drops the grid.
		if (!level.getServer().isRunning()) {
			throw e;
		}
		if (failed.add(structure)) {
			LOGGER.warn("The far terrain leaves out {}, which failed to generate",
				level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure), e);
		}
	}

	/**
	 * Sums up the blocks the structures put in each column that show from afar, by {@link ChunkPos#pack} of the column,
	 * cell by cell of the grid of {@code width}² columns {@code step} blocks apart whose corner block is
	 * {@code (minX, minZ)}, standing on {@code ground}. A cell keeps a block at each height where the structures put
	 * one over the ground in a quarter of its columns at least, the one they put there most: a house keeps its walls,
	 * its roof and its eaves, block by block where each cell is a column and in outline where cells are wider, rather
	 * than standing as a solid block. A cell is paved where they lie on the ground in a quarter of its columns, or
	 * rise in some and lie in others, as a village's paths do.
	 */
	static @Nullable Cell[] cells(Long2ObjectMap<StructureSketch.Column> columns, StructureSketch.Ground ground,
								  int minX, int minZ, int width, int step) {
		int count = width * width;
		int[] risen = new int[count];
		int[] paved = new int[count];
		BlockState[] paving = new BlockState[count];
		// For each cell, by block Y, how many of its columns have each block there.
		@SuppressWarnings("unchecked")
		Int2ObjectSortedMap<Reference2IntOpenHashMap<BlockState>>[] layers = new Int2ObjectSortedMap[count];
		for (Long2ObjectMap.Entry<StructureSketch.Column> entry : columns.long2ObjectEntrySet()) {
			int x = ChunkPos.getX(entry.getLongKey());
			int z = ChunkPos.getZ(entry.getLongKey());
			int a = Math.floorDiv(x - minX, step);
			int b = Math.floorDiv(z - minZ, step);
			if (a < 0 || a >= width || b < 0 || b >= width) {
				continue;
			}
			int cell = b * width + a;
			StructureSketch.Column column = entry.getValue();
			int groundY = ground.at(x, z).ground();
			if (column.topY == groundY) {
				paved[cell]++;
				paving[cell] = column.top;
			} else if (column.topY > groundY) {
				risen[cell]++;
				if (layers[cell] == null) {
					layers[cell] = new Int2ObjectAVLTreeMap<>();
				}
				for (int i = 0; i < column.count; i++) {
					if (column.ys[i] > groundY) {
						layers[cell].computeIfAbsent(column.ys[i], y -> new Reference2IntOpenHashMap<>())
							.addTo(column.states[i], 1);
					}
				}
			}
		}
		Cell[] cells = null;
		int area = step * step;
		IntArrayList ys = new IntArrayList();
		List<BlockState> states = new ArrayList<>();
		for (int cell = 0; cell < count; cell++) {
			ys.clear();
			states.clear();
			if (layers[cell] != null) {
				for (Int2ObjectMap.Entry<Reference2IntOpenHashMap<BlockState>> layer : layers[cell].int2ObjectEntrySet()) {
					int shown = 0;
					int most = 0;
					BlockState state = null;
					for (Reference2IntMap.Entry<BlockState> kind : layer.getValue().reference2IntEntrySet()) {
						shown += kind.getIntValue();
						if (kind.getIntValue() > most) {
							most = kind.getIntValue();
							state = kind.getKey();
						}
					}
					if (shown * 4 >= area) {
						ys.add(layer.getIntKey());
						states.add(state);
					}
				}
			}
			BlockState paves = paving[cell] != null && (risen[cell] + paved[cell]) * 4 >= area ? paving[cell] : null;
			if (!ys.isEmpty() || paves != null) {
				if (cells == null) {
					cells = new Cell[count];
				}
				cells[cell] = new Cell(ys.toIntArray(), states.toArray(BlockState[]::new), paves);
			}
		}
		return cells;
	}
}
