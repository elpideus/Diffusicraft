package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.elpideus.diffusicraft.worldgen.lod.LodGrid.AIR;
import static io.github.elpideus.diffusicraft.worldgen.lod.LodGrid.CAVE;
import static io.github.elpideus.diffusicraft.worldgen.lod.LodGrid.CLEAR;
import static io.github.elpideus.diffusicraft.worldgen.lod.LodGrid.OPAQUE;
import static io.github.elpideus.diffusicraft.worldgen.lod.LodGrid.WATER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LodGridTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Voxels of a column of runs {@code [bottoms[i], tops[i])}, {@code count} of them from {@code bottom}. */
	private static int[][] voxels(int[] bottoms, int[] tops, byte[] kinds, int surface, int bottom, int size,
								  int count) {
		int[] runs = new int[count];
		byte[] sky = new byte[count];
		LodGrid.voxels(bottoms, tops, kinds, 0, bottoms.length, surface, bottom, size, runs, sky);
		int[] light = new int[count];
		for (int i = 0; i < count; i++) {
			light[i] = sky[i];
		}
		return new int[][]{runs, light};
	}

	@Test
	void dryGroundIsLitFromAbove() {
		int[][] v = voxels(new int[]{-64, 60}, new int[]{60, 64}, new byte[]{OPAQUE, OPAQUE}, 64, 48, 8, 4);
		assertArrayEquals(new int[]{0, 1, -1, -1}, v[0]);
		assertArrayEquals(new int[]{0, 0, 15, 15}, v[1]);
	}

	@Test
	void waterHidesTheGroundUnderItAndDarkensWithDepth() {
		int[][] v = voxels(new int[]{-64, 40, 41}, new int[]{40, 41, 50}, new byte[]{OPAQUE, OPAQUE, WATER}, 50,
			36, 4, 5);
		// The sand shares its voxel with water above it, and is hidden; the water's top block takes 14, as in the world.
		assertArrayEquals(new int[]{0, 2, 2, 2, -1}, v[0]);
		assertArrayEquals(new int[]{0, 8, 12, 14, 15}, v[1]);
	}

	@Test
	void aCoarseVoxelOfShallowSeaIsSea() {
		// Sand two blocks under the sea, in a voxel of 16 blocks: the sea, not dry sand at sea level.
		int[][] v = voxels(new int[]{-64, 60, 61}, new int[]{60, 61, 63}, new byte[]{OPAQUE, OPAQUE, WATER}, 63,
			48, 16, 2);
		assertArrayEquals(new int[]{2, -1}, v[0]);
		assertArrayEquals(new int[]{14, 15}, v[1]);
	}

	@Test
	void crownsStandOverTheAirUnderThem() {
		int[][] v = voxels(new int[]{-64, 64, 67}, new int[]{64, 67, 70}, new byte[]{OPAQUE, AIR, CLEAR}, 64,
			62, 2, 5);
		assertArrayEquals(new int[]{0, -1, 2, 2, -1}, v[0]);
		assertArrayEquals(new int[]{0, 15, 14, 14, 15}, v[1]);
	}

	@Test
	void aCoarseVoxelShowsItsHighestSolidBlock() {
		int[][] v = voxels(new int[]{-64, 64, 67}, new int[]{64, 67, 70}, new byte[]{OPAQUE, AIR, CLEAR}, 64,
			48, 16, 3);
		assertArrayEquals(new int[]{0, 2, -1}, v[0]);
		assertArrayEquals(new int[]{0, 14, 15}, v[1]);
	}

	@Test
	void aCaveIsAirTheSkyDoesNotLight() {
		int[][] v = voxels(new int[]{-64, 20, 26}, new int[]{20, 26, 64}, new byte[]{OPAQUE, CAVE, OPAQUE}, 64,
			18, 2, 5);
		assertArrayEquals(new int[]{0, -1, -1, -1, 2}, v[0]);
		assertArrayEquals(new int[]{0, 0, 0, 0, 0}, v[1]);
	}

	@Test
	void aSunkenColumnKeepsItsCavesOverTheBottomOfTheWorld() {
		LodGrid grid = new LodGrid(1);
		grid.add(cave(), 95);
		assertEquals(3, grid.runs(0));
		assertEquals(-64, grid.bottom(0));
		assertEquals(-55, grid.top(0));
		assertEquals(CAVE, grid.kind(1));
		assertEquals(-45, grid.bottom(2));
		assertEquals(95, grid.top(2));
	}

	@Test
	void aColumnSunkFarUnderTheCeilingFoldsTheCavesThatWouldSinkUnderTheWorld() {
		LodGrid grid = new LodGrid(1);
		grid.add(cave(), 80);
		assertEquals(1, grid.runs(0));
		assertEquals(-64, grid.bottom(0));
		assertEquals(80, grid.top(0));
		assertEquals(Blocks.STONE.defaultBlockState(), grid.state(0));

		// As when a page kept with no ceiling is read back under one.
		LodGrid kept = new LodGrid(1);
		kept.add(cave(), Integer.MAX_VALUE);
		LodGrid under = new LodGrid(1);
		under.add(kept, 0, 80);
		assertEquals(1, under.runs(0));
		assertEquals(-64, under.bottom(0));
		assertEquals(80, under.top(0));
	}

	/** Deepslate at the bottom of the world, a cave over it and stone up to y = 100. */
	private static LodColumn cave() {
		LodColumn column = new LodColumn();
		column.reset(null);
		column.add(Blocks.DEEPSLATE.defaultBlockState(), -64, -50);
		column.add(Blocks.CAVE_AIR.defaultBlockState(), -50, -40);
		column.add(Blocks.STONE.defaultBlockState(), -40, 100);
		column.markGround();
		return column;
	}

	@Test
	void voxelsBelowTheWorldAreAir() {
		int[][] v = voxels(new int[]{-64}, new int[]{70}, new byte[]{OPAQUE}, 70, -96, 16, 3);
		assertArrayEquals(new int[]{-1, -1, 0}, v[0]);
	}
}
