package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LodStoreTest {
	private static final Map<ResourceKey<Biome>, Holder.Reference<Biome>> BIOMES = new HashMap<>();
	private static final HolderLookup<Biome> LOOKUP = new HolderLookup<>() {
		@Override
		public Stream<Holder.Reference<Biome>> listElements() {
			return BIOMES.values().stream();
		}

		@Override
		public Stream<HolderSet.Named<Biome>> listTags() {
			return Stream.empty();
		}

		@Override
		public Optional<Holder.Reference<Biome>> get(ResourceKey<Biome> id) {
			return Optional.ofNullable(BIOMES.get(id));
		}

		@Override
		public Optional<HolderSet.Named<Biome>> get(TagKey<Biome> id) {
			return Optional.empty();
		}
	};
	private static Holder<Biome> plains;
	private static Holder<Biome> desert;

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		plains = biome("plains");
		desert = biome("desert");
	}

	private static Holder<Biome> biome(String name) {
		ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(name));
		Holder.Reference<Biome> holder = Holder.Reference.createStandAlone(LOOKUP, key);
		BIOMES.put(key, holder);
		return holder;
	}

	private LodStore store() {
		return new LodStore(dir, Level.OVERWORLD, LOOKUP, plains);
	}

	/** Stone up to grass at block Y {@code height - 1}. */
	private static LodColumn column(int height, Holder<Biome> biome) {
		LodColumn column = new LodColumn();
		column.reset(biome);
		column.add(Blocks.STONE.defaultBlockState(), 0, height - 1);
		column.add(Blocks.GRASS_BLOCK.defaultBlockState(), height - 1, height);
		column.markGround();
		return column;
	}

	private static LodColumn[] chunk(int height, Holder<Biome> biome) {
		LodColumn[] columns = new LodColumn[256];
		for (int i = 0; i < columns.length; i++) {
			columns[i] = column(height, biome);
		}
		return columns;
	}

	private static LodGrid flat(int width, int height, Holder<Biome> biome) {
		LodGrid grid = new LodGrid(width);
		for (int i = 0; i < width * width; i++) {
			grid.add(column(height, biome), Integer.MAX_VALUE);
		}
		return grid;
	}

	private static BlockState at(LodGrid grid, int column, int y) {
		BlockState[] states = new BlockState[1];
		grid.voxels(column, y, 1, states, new byte[1]);
		return states[0] == null ? Blocks.AIR.defaultBlockState() : states[0];
	}

	@Test
	void aChunkFillsTheColumnsOfEveryLevelThatFallInIt() throws IOException {
		try (LodStore store = store()) {
			store.saved(new ChunkPos(0, 0), chunk(70, desert));
			store.saved(new ChunkPos(-1, -1), chunk(80, desert));

			LodStore.Page fine = store.page(0, 0, 0).join();
			assertTrue(fine.has(0));
			assertTrue(fine.has(15 * LodStore.PAGE + 15));
			assertFalse(fine.has(16), "the next chunk was never saved");
			assertFalse(fine.full());
			assertEquals(desert, fine.grid().biome(0));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(fine.grid(), 0, 69));
			assertEquals(Blocks.AIR.defaultBlockState(), at(fine.grid(), 16, 69), "a column never saved is empty");

			// Blocks -64 to -1 of the page before; the chunk is its last 16 columns each way.
			LodStore.Page before = store.page(0, -1, -1).join();
			assertTrue(before.has(48 * LodStore.PAGE + 48));
			assertFalse(before.has(47 * LodStore.PAGE + 48));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(before.grid(), 63 * LodStore.PAGE + 63, 79));

			// 16 blocks apart, the column at (8, 8) is the chunk's; at 32 apart the columns are at 16 past a multiple.
			LodStore.Page sixteen = store.page(4, 0, 0).join();
			assertTrue(sixteen.has(0));
			assertFalse(sixteen.has(1));
			assertNull(store.page(5, 0, 0).join());
		}
	}

	@Test
	void aChunkKeepsItsCavesAtTheLevelsAsFineAsTheyAreHigh() throws IOException {
		LodColumn[] columns = new LodColumn[256];
		for (int i = 0; i < columns.length; i++) {
			LodColumn column = new LodColumn();
			column.reset(plains);
			column.add(Blocks.STONE.defaultBlockState(), 0, 30);
			column.add(Blocks.CAVE_AIR.defaultBlockState(), 30, 36);
			column.add(Blocks.STONE.defaultBlockState(), 36, 69);
			column.add(Blocks.GRASS_BLOCK.defaultBlockState(), 69, 70);
			column.markGround();
			columns[i] = column;
		}
		try (LodStore store = store()) {
			store.saved(new ChunkPos(0, 0), columns);
			for (int level = 0; level <= 2; level++) {
				LodGrid grid = store.page(level, 0, 0).join().grid();
				assertEquals(Blocks.AIR.defaultBlockState(), at(grid, 0, 33), "open at level " + level);
				byte[] sky = new byte[1];
				grid.voxels(0, 33, 1, new BlockState[1], sky);
				assertEquals(0, sky[0], "dark at level " + level);
			}
			// Eight blocks apart, a cave six blocks high is closed, as the generated terrain's are.
			assertEquals(Blocks.STONE.defaultBlockState(), at(store.page(3, 0, 0).join().grid(), 0, 33));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(store.page(3, 0, 0).join().grid(), 0, 69));
		}
	}

	@Test
	void aChunkSavedAgainReplacesItsColumnsAndKeepsTheOthers() throws IOException {
		try (LodStore store = store()) {
			store.saved(new ChunkPos(0, 0), chunk(70, desert));
			store.saved(new ChunkPos(1, 0), chunk(80, desert));
			store.page(0, 0, 0).join();
			store.saved(new ChunkPos(0, 0), chunk(60, plains));

			LodGrid grid = store.page(0, 0, 0).join().grid();
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(grid, 0, 59));
			assertTrue(at(grid, 0, 65).isAir());
			assertEquals(plains, grid.biome(0));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(grid, 16, 79));
		}
	}

	@Test
	void aChunkSavedAgainAsItWasChangesNoPage() throws IOException {
		try (LodStore store = store()) {
			AtomicInteger changed = new AtomicInteger();
			store.listen((level, x, z) -> changed.incrementAndGet());
			store.saved(new ChunkPos(0, 0), chunk(70, desert));
			store.page(0, 0, 0).join();
			int first = changed.get();
			assertTrue(first > 0);

			// As vanilla saves it again after a block changed out of sight of the far terrain.
			store.saved(new ChunkPos(0, 0), chunk(70, desert));
			store.page(0, 0, 0).join();
			assertEquals(first, changed.get(), "a page whose columns are as they were changed");

			store.saved(new ChunkPos(0, 0), chunk(71, desert));
			store.page(0, 0, 0).join();
			assertTrue(changed.get() > first);
		}
	}

	@Test
	void pagesOutlastTheStore() throws IOException {
		try (LodStore store = store()) {
			store.saved(new ChunkPos(2, 3), chunk(70, desert));
		}
		try (LodStore store = store()) {
			LodStore.Page page = store.page(0, 0, 0).join();
			int column = 3 * 16 * LodStore.PAGE + 2 * 16;
			assertTrue(page.has(column));
			assertFalse(page.has(0));
			assertEquals(desert, page.grid().biome(column));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(page.grid(), column, 69));
			assertEquals(Blocks.STONE.defaultBlockState(), at(page.grid(), column, 0));
		}
	}

	@Test
	void theStoredColumnsLieOverTheSampledOnes() throws IOException {
		try (LodStore store = store()) {
			store.saved(new ChunkPos(0, 0), chunk(70, desert));
			store.page(0, 0, 0).join();

			AtomicInteger sampled = new AtomicInteger();
			LodGrid grid = store.overlay(0, 0, 32, 1, Integer.MAX_VALUE, () -> {
				sampled.incrementAndGet();
				return flat(32, 50, plains);
			});
			assertEquals(1, sampled.get());
			assertEquals(desert, grid.biome(0));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(grid, 0, 69));
			assertEquals(plains, grid.biome(16));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(grid, 16, 49));

			// A grid of stored columns alone is never sampled; a ceiling sinks them as it sinks sampled ones.
			LodGrid stored = store.overlay(0, 0, 16, 1, 60, () -> fail("sampled a grid the store holds"));
			assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), at(stored, 0, 59));
			assertEquals(60, stored.highest());

			// Columns 2 blocks apart from an odd corner are not those of any level.
			assertEquals(plains, store.overlay(1, 1, 8, 2, Integer.MAX_VALUE, () -> flat(8, 50, plains)).biome(0));
		}
	}
}
