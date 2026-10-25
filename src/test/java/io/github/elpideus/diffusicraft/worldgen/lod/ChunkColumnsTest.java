package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkColumnsTest {
	private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {
	};
	private static final int SECTIONS = 8;
	private static Holder<Biome> plains;
	private static Strategy<Holder<Biome>> biomes;
	/** The blocks of the test that stop a player; the world reads the tags, which a plain test does not bind. */
	private static Set<Block> solid;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		solid = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.OAK_LOG, Blocks.OAK_LEAVES,
			Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.ICE);
		plains = Holder.Reference.createStandAlone(OWNER,
			ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("plains")));
		IdMapper<Holder<Biome>> ids = new IdMapper<>();
		ids.add(plains);
		biomes = Strategy.createForBiomes(ids);
	}

	/** A chunk from y = 0 to 127 and its sky light, which shines down to the first block that stops it. */
	private static final class Chunk {
		final LevelChunkSection[] sections = new LevelChunkSection[SECTIONS];
		final DataLayer[] sky = new DataLayer[SECTIONS];

		Chunk() {
			for (int i = 0; i < SECTIONS; i++) {
				sections[i] = new LevelChunkSection(
					new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
					new PalettedContainer<>(plains, biomes));
				sky[i] = new DataLayer();
			}
		}

		void set(int x, int y, int z, Block block) {
			sections[y >> 4].setBlockState(x, y & 15, z, block.defaultBlockState());
		}

		void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						set(x, y, z, block);
					}
				}
			}
		}

		void light(int x, int y, int z, int level) {
			sky[y >> 4].set(x, y & 15, z, level);
		}

		/** Full sky light down every column to its first block that is not air, water or a plant. */
		void lightFromAbove() {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					for (int y = SECTIONS * 16 - 1; y >= 0; y--) {
						BlockState state = sections[y >> 4].getBlockState(x, y & 15, z);
						if (solid.contains(state.getBlock())) {
							break;
						}
						light(x, y, z, 15);
					}
				}
			}
		}

		LodColumn[] read() {
			LodColumn[] columns = new LodColumn[256];
			new ChunkColumns(state -> solid.contains(state.getBlock()), state -> state.is(Blocks.OAK_LEAVES))
				.read(sections, sky, 0, (x, z, column) -> columns[z * 16 + x] = copy(column));
			return columns;
		}

		/** The columns read from the sections from {@code from} up, as a client keeps the chunks it lets go of. */
		LodColumn[] read(int from) {
			LodColumn[] columns = new LodColumn[256];
			new ChunkColumns(state -> solid.contains(state.getBlock()), state -> state.is(Blocks.OAK_LEAVES))
				.read(Arrays.copyOfRange(sections, from, SECTIONS), Arrays.copyOfRange(sky, from, SECTIONS), from, 0,
					(x, z, column) -> columns[z * 16 + x] = copy(column));
			return columns;
		}
	}

	private static LodColumn copy(LodColumn column) {
		LodColumn copy = new LodColumn();
		copy.reset(column.biome());
		for (int run = 0; run < column.size(); run++) {
			copy.add(column.state(run), column.bottom(run), column.top(run));
			if (run == column.ground()) {
				copy.markGround();
			}
		}
		copy.waterDepth(column.waterDepth());
		return copy;
	}

	/** Meadow: stone, three blocks of dirt and grass at y = 63. */
	private static Chunk meadow() {
		Chunk chunk = new Chunk();
		chunk.fill(0, 0, 0, 15, 59, 15, Blocks.STONE);
		chunk.fill(0, 60, 0, 15, 62, 15, Blocks.DIRT);
		chunk.fill(0, 63, 0, 15, 63, 15, Blocks.GRASS_BLOCK);
		return chunk;
	}

	private static BlockState at(LodColumn column, int y) {
		for (int run = 0; run < column.size(); run++) {
			if (column.bottom(run) <= y && y < column.top(run)) {
				return column.state(run);
			}
		}
		return Blocks.AIR.defaultBlockState();
	}

	@Test
	void plantsAreAirAndSnowLiesOnTop() {
		Chunk chunk = meadow();
		chunk.set(1, 64, 1, Blocks.POPPY);
		chunk.set(14, 64, 1, Blocks.SNOW);
		chunk.lightFromAbove();
		LodColumn[] columns = chunk.read();

		LodColumn flower = columns[16 + 1];
		assertEquals(64, flower.top());
		assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), flower.state(flower.ground()));
		// Stone from the bottom of the world, then the soils one by one.
		assertEquals(0, flower.bottom(0));
		assertEquals(Blocks.STONE.defaultBlockState(), flower.state(0));
		assertEquals(3, flower.size());

		LodColumn snow = columns[16 + 14];
		assertEquals(65, snow.top());
		assertEquals(Blocks.SNOW.defaultBlockState(), snow.state(snow.size() - 1));
		assertEquals(64, snow.top(snow.ground()));
	}

	@Test
	void aTreeKeepsItsTrunkAndCrown() {
		Chunk chunk = meadow();
		chunk.fill(3, 67, 3, 5, 70, 5, Blocks.OAK_LEAVES);
		chunk.fill(4, 64, 4, 4, 68, 4, Blocks.OAK_LOG);
		chunk.lightFromAbove();
		LodColumn[] columns = chunk.read();

		LodColumn crown = columns[3 * 16 + 3];
		assertEquals(71, crown.top());
		assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), at(crown, 68));
		assertTrue(at(crown, 65).isAir(), "the air under the crown");
		assertEquals(64, crown.top(crown.ground()), "the ground is under the leaves");

		LodColumn trunk = columns[4 * 16 + 4];
		assertEquals(Blocks.OAK_LOG.defaultBlockState(), at(trunk, 64));
		assertEquals(Blocks.OAK_LOG.defaultBlockState(), at(trunk, 68));
		assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), at(trunk, 70));
		assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(trunk, 63));
	}

	@Test
	void aClosedHouseIsFilledUnderItsRoofButShowsItsWalls() {
		Chunk chunk = meadow();
		chunk.fill(8, 64, 8, 12, 64, 12, Blocks.OAK_PLANKS);
		chunk.fill(8, 65, 8, 12, 68, 12, Blocks.COBBLESTONE);
		chunk.fill(9, 65, 9, 11, 68, 11, Blocks.AIR);
		chunk.fill(8, 69, 8, 12, 69, 12, Blocks.OAK_PLANKS);
		chunk.lightFromAbove();
		LodColumn[] columns = chunk.read();

		LodColumn middle = columns[10 * 16 + 10];
		assertEquals(70, middle.top());
		assertEquals(70, middle.top(middle.ground()), "the roof is the ground");
		assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), middle.state(middle.ground()));
		// The dark room under the roof sinks into one run with the floor and all below it.
		assertEquals(0, middle.bottom(0));
		assertTrue(middle.top(0) >= 65, "filled up to " + middle.top(0));
		assertTrue(middle.size() <= 3, middle.size() + " runs");

		LodColumn wall = columns[10 * 16 + 8];
		assertEquals(Blocks.COBBLESTONE.defaultBlockState(), at(wall, 66));
		assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), at(wall, 64));
		assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(wall, 63), "the wall keeps the soil beside it");
	}

	@Test
	void aBridgeKeepsTheWaterAndTheAirUnderIt() {
		Chunk chunk = meadow();
		chunk.fill(1, 61, 9, 6, 63, 14, Blocks.WATER);
		chunk.set(2, 61, 10, Blocks.KELP);
		chunk.fill(1, 66, 11, 6, 66, 13, Blocks.OAK_PLANKS);
		chunk.lightFromAbove();
		// The sky reaches under the deck from its sides.
		for (int y = 61; y <= 65; y++) {
			for (int x = 1; x <= 6; x++) {
				chunk.light(x, y, 12, 13);
			}
		}
		LodColumn[] columns = chunk.read();

		LodColumn under = columns[12 * 16 + 3];
		assertEquals(67, under.top());
		assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), at(under, 66));
		assertTrue(at(under, 64).isAir(), "the air under the deck");
		assertEquals(Blocks.WATER.defaultBlockState(), at(under, 62));
		assertEquals(Blocks.DIRT.defaultBlockState(), at(under, 60));

		LodColumn kelp = columns[10 * 16 + 2];
		assertEquals(Blocks.WATER.defaultBlockState(), at(kelp, 61), "kelp is water from afar");
		assertEquals(Blocks.DIRT.defaultBlockState(), kelp.state(kelp.ground()));
		assertEquals(3, kelp.waterDepth());
	}

	@Test
	void theSectionsUnderTheGroundCanBeLeftOut() {
		Chunk chunk = meadow();
		chunk.fill(8, 64, 8, 12, 66, 12, Blocks.COBBLESTONE);
		chunk.lightFromAbove();
		LodColumn[] whole = chunk.read();
		LodColumn[] upper = chunk.read(3);
		for (int i = 0; i < 256; i++) {
			assertEquals(whole[i].size(), upper[i].size(), "column " + i);
			for (int run = 0; run < whole[i].size(); run++) {
				assertEquals(whole[i].state(run), upper[i].state(run), "column " + i + ", run " + run);
				assertEquals(whole[i].bottom(run), upper[i].bottom(run), "column " + i + ", run " + run);
				assertEquals(whole[i].top(run), upper[i].top(run), "column " + i + ", run " + run);
			}
			assertEquals(whole[i].ground(), upper[i].ground(), "column " + i);
		}
		assertEquals(0, upper[0].bottom(0), "the stone reaches on down to the bottom of the world");
	}

	@Test
	void anEmptyChunkHasEmptyColumns() {
		Chunk chunk = new Chunk();
		LodColumn[] columns = chunk.read();
		for (LodColumn column : columns) {
			assertEquals(0, column.size());
			assertEquals(plains, column.biome());
		}
		assertFalse(columns[0].ground() >= 0);
	}
}
