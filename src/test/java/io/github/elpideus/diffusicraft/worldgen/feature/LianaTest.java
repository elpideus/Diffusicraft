package io.github.elpideus.diffusicraft.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LianaTest {
	private static final int GROUND = 80;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A column of air over stone, whose floor is at {@link #GROUND}. */
	private static final class Column implements LianaFeature.Column {
		final BlockState[] blocks = new BlockState[160];

		Column() {
			Arrays.fill(blocks, 0, GROUND, Blocks.STONE.defaultBlockState());
			Arrays.fill(blocks, GROUND, blocks.length, Blocks.AIR.defaultBlockState());
		}

		@Override
		public BlockState get(int y) {
			return blocks[y];
		}

		@Override
		public void set(int y, BlockState state) {
			assertTrue(blocks[y].isAir(), "replaced " + blocks[y] + " at y = " + y);
			blocks[y] = state;
		}

		/** Vines from {@code from} down to {@code to}, as a leaf's vines hang. */
		Column vines(int from, int to, BlockState vine) {
			Arrays.fill(blocks, to, from + 1, vine);
			return this;
		}

		/** The lowest of the vines that hang from {@code from}. */
		int lowest(int from) {
			int y = from;
			while (blocks[y - 1].is(Blocks.VINE)) {
				y--;
			}
			return y;
		}

		boolean grow(float chance, long seed) {
			return grow(chance, RandomSource.create(seed));
		}

		boolean grow(float chance, RandomSource random) {
			return LianaFeature.grow(this, blocks.length - 1, GROUND, chance, random);
		}
	}

	private static BlockState vine(boolean north, boolean east, boolean up) {
		return Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, north).setValue(VineBlock.EAST, east)
			.setValue(VineBlock.UP, up);
	}

	@Test
	void aHangingVineGrowsAtLeastHalfwayToTheFloor() {
		int toFloor = 0;
		for (long seed = 0; seed < 64; seed++) {
			Column column = new Column().vines(100, 97, vine(true, false, false));
			assertTrue(column.grow(1f, seed));
			// 17 blocks of air under the vine at 97, from 96 down to the floor at 80.
			int lowest = column.lowest(100);
			assertTrue(lowest >= GROUND && lowest <= 88, "grew down to " + lowest);
			assertTrue(column.get(lowest - 1).isAir() || lowest == GROUND);
			if (lowest == GROUND) {
				toFloor++;
			}
		}
		assertTrue(toFloor > 0 && toFloor < 64, toFloor + " of 64 down to the floor");
	}

	@Test
	void theVinesBelowKeepTheFacesOnTheSidesThatHoldThem() {
		Column column = new Column().vines(100, 100, vine(true, true, true));
		assertTrue(column.grow(1f, 1));
		BlockState below = vine(true, true, false);
		for (int y = 99; y >= column.lowest(100); y--) {
			assertEquals(below, column.get(y), "at y = " + y);
		}
	}

	@Test
	void aVineUnderALeafHasNothingToHangFrom() {
		Column column = new Column().vines(100, 100, vine(false, false, true));
		assertFalse(column.grow(1f, 1));
		assertTrue(column.get(99).isAir());
	}

	@Test
	void aVineStopsAboveWhatIsBelowIt() {
		for (long seed = 0; seed < 32; seed++) {
			Column column = new Column().vines(100, 97, vine(true, false, false));
			column.blocks[90] = Blocks.FERN.defaultBlockState();
			assertTrue(column.grow(1f, seed));
			// Six blocks of air, from 96 down to 91: the vine grows into three of them or more.
			int lowest = column.lowest(100);
			assertTrue(lowest >= 91 && lowest <= 94, "grew down to " + lowest);
			assertTrue(column.get(90).is(Blocks.FERN));
		}
	}

	@Test
	void eachVineGrowsByItself() {
		// One random for all the vines, as for the columns of a chunk: the first draws of nearby seeds are alike.
		RandomSource random = RandomSource.create(7);
		int grew = 0;
		for (int vine = 0; vine < 200; vine++) {
			if (new Column().vines(100, 97, vine(true, false, false)).grow(0.5f, random)) {
				grew++;
			}
			assertFalse(new Column().vines(100, 97, vine(true, false, false)).grow(0f, random));
		}
		assertTrue(grew > 70 && grew < 130, grew + " of 200 grew");
	}

	@Test
	void aVineOverTheFloorStaysAsItIs() {
		Column column = new Column().vines(GROUND + 1, GROUND + 1, vine(true, false, false));
		assertFalse(column.grow(1f, 1));
	}
}
