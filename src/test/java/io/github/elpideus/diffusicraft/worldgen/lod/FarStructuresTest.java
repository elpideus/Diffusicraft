package io.github.elpideus.diffusicraft.worldgen.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FarStructuresTest {
	private static final int GROUND = 64;
	private static final StructureSketch.Ground FLAT = (x, z) -> StructureSketch.Land.dry(GROUND);
	private static BlockState planks;
	private static BlockState glass;
	private static BlockState roof;
	private static BlockState floor;
	private static BlockState path;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		planks = Blocks.OAK_PLANKS.defaultBlockState();
		glass = Blocks.GLASS.defaultBlockState();
		roof = Blocks.OAK_STAIRS.defaultBlockState();
		floor = Blocks.COBBLESTONE.defaultBlockState();
		path = Blocks.DIRT_PATH.defaultBlockState();
	}

	/**
	 * A house of 3 × 3 columns from {@code (0, 0)}: walls of planks three blocks high round a room, with a window of
	 * glass in the wall at {@code (1, 0)}, a floor on the ground and a roof over all of it; and a path at
	 * {@code (3, 3)}.
	 */
	private static Long2ObjectOpenHashMap<StructureSketch.Column> house() {
		Long2ObjectOpenHashMap<StructureSketch.Column> columns = new Long2ObjectOpenHashMap<>();
		for (int z = 0; z < 3; z++) {
			for (int x = 0; x < 3; x++) {
				StructureSketch.Column column = new StructureSketch.Column();
				column.add(GROUND, floor);
				if (x != 1 || z != 1) {
					for (int y = GROUND + 1; y <= GROUND + 3; y++) {
						column.add(y, x == 1 && z == 0 && y == GROUND + 2 ? glass : planks);
					}
				}
				column.add(GROUND + 4, roof);
				columns.put(ChunkPos.pack(x, z), column);
			}
		}
		StructureSketch.Column walk = new StructureSketch.Column();
		walk.add(GROUND, path);
		columns.put(ChunkPos.pack(3, 3), walk);
		return columns;
	}

	@Test
	void cellsOfOneColumnKeepEveryBlock() {
		FarStructures.Cell[] cells = FarStructures.cells(house(), FLAT, 0, 0, 4, 1);
		assertNotNull(cells);
		FarStructures.Cell wall = cells[0];
		assertArrayEquals(new int[]{65, 66, 67, 68}, wall.ys());
		assertArrayEquals(new BlockState[]{planks, planks, planks, roof}, wall.states());
		assertNull(wall.paving());
		FarStructures.Cell window = cells[1];
		assertArrayEquals(new BlockState[]{planks, glass, planks, roof}, window.states());
		// The room stays open under the roof, and the floor under it is the ground's.
		FarStructures.Cell room = cells[4 + 1];
		assertArrayEquals(new int[]{68}, room.ys());
		assertNull(room.paving());
		FarStructures.Cell walk = cells[3 * 4 + 3];
		assertArrayEquals(new int[0], walk.ys());
		assertNotNull(walk.paving());
		assertNull(cells[3]);
	}

	@Test
	void widerCellsKeepTheOutline() {
		FarStructures.Cell[] cells = FarStructures.cells(house(), FLAT, 0, 0, 2, 2);
		assertNotNull(cells);
		// Three of its four columns are walls, with planks more often than glass.
		assertArrayEquals(new int[]{65, 66, 67, 68}, cells[0].ys());
		assertArrayEquals(new BlockState[]{planks, planks, planks, roof}, cells[0].states());
		// One wall column of four, and a path, still show.
		assertArrayEquals(new int[]{65, 66, 67, 68}, cells[3].ys());
		assertNotNull(cells[3].paving());
	}

	@Test
	void aPostTooThinForTheCellLeavesNothing() {
		Long2ObjectOpenHashMap<StructureSketch.Column> columns = new Long2ObjectOpenHashMap<>();
		StructureSketch.Column post = new StructureSketch.Column();
		for (int y = GROUND + 1; y <= GROUND + 3; y++) {
			post.add(y, planks);
		}
		columns.put(ChunkPos.pack(5, 5), post);
		assertNull(FarStructures.cells(columns, FLAT, 0, 0, 2, 4));
		// The house's eight walls and nine roofs fill a quarter of a cell of 4 × 4 and more.
		FarStructures.Cell[] cells = FarStructures.cells(house(), FLAT, 0, 0, 1, 4);
		assertNotNull(cells);
		assertArrayEquals(new int[]{65, 66, 67, 68}, cells[0].ys());
	}
}
