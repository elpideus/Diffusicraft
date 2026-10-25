package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodMesherTest {
	private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {
	};
	private static final int MIN_Y = -64;
	private static Holder<Biome> plains;
	private static BlockState stone;
	private static BlockState gold;
	private static BlockState planks;
	private static BlockState water;
	private static BlockState air;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		plains = Holder.Reference.createStandAlone(OWNER,
			ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("plains")));
		stone = Blocks.STONE.defaultBlockState();
		gold = Blocks.GOLD_BLOCK.defaultBlockState();
		planks = Blocks.OAK_PLANKS.defaultBlockState();
		water = Blocks.WATER.defaultBlockState();
		air = Blocks.AIR.defaultBlockState();
	}

	private record Face(Direction side, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state, int sky) {
		/** Whether the face lies on the border of a tile {@code size} blocks wide. */
		boolean onBorder(int size) {
			return switch (side.getAxis()) {
				case X -> x0 == 0 || x0 == size;
				case Z -> z0 == 0 || z0 == size;
				case Y -> false;
			};
		}

		int area() {
			return Math.max(1, x1 - x0) * Math.max(1, y1 - y0) * Math.max(1, z1 - z0);
		}
	}

	/** The faces of a mesh, solid and water apart. */
	private static final class Mesh {
		final List<Face> solid = new ArrayList<>();
		final List<Face> water = new ArrayList<>();

		Mesh(LodGrid grid, int step) {
			LodMesher.mesh(grid, step,
				(side, x0, y0, z0, x1, y1, z1, state, biome, sky) ->
					solid.add(new Face(side, x0, y0, z0, x1, y1, z1, state, sky)),
				(side, x0, y0, z0, x1, y1, z1, state, biome, sky) ->
					water.add(new Face(side, x0, y0, z0, x1, y1, z1, state, sky)));
		}

		List<Face> solid(Direction side) {
			return solid.stream().filter(face -> face.side() == side).toList();
		}

		List<Face> solidSides() {
			return solid.stream().filter(face -> face.side().getAxis() != Direction.Axis.Y).toList();
		}
	}

	private static LodGrid grid(int width, IntFunction<LodColumn> columns) {
		LodGrid grid = new LodGrid(width);
		for (int column = 0; column < width * width; column++) {
			grid.add(columns.apply(column), Integer.MAX_VALUE);
		}
		return grid;
	}

	/** A column of stone from the bottom of the world up to {@code ground}. */
	private static LodColumn stone(int ground) {
		LodColumn column = new LodColumn();
		column.reset(plains);
		column.add(stone, MIN_Y, ground);
		column.markGround();
		return column;
	}

	@Test
	void flatGroundIsOneTopWithSkirtsAroundIt() {
		Mesh mesh = new Mesh(grid(4, column -> stone(64)), 2);
		assertEquals(List.of(new Face(Direction.UP, 0, 64, 0, 8, 64, 8, stone, 15)), mesh.solid(Direction.UP));
		List<Face> sides = mesh.solidSides();
		assertEquals(16, sides.size(), sides.toString());
		for (Face face : sides) {
			assertTrue(face.onBorder(8), face.toString());
			// Two steps under the ground, down to where a coarser tile beside it may lie.
			assertEquals(60, face.y0(), face.toString());
			assertEquals(64, face.y1(), face.toString());
		}
		assertEquals(List.of(), mesh.solid(Direction.DOWN));
		assertEquals(List.of(), mesh.water);
	}

	@Test
	void aPillarShowsItsFourSidesAndItsTop() {
		Mesh mesh = new Mesh(grid(3, column -> {
			if (column != 4) {
				return stone(64);
			}
			LodColumn pillar = new LodColumn();
			pillar.reset(plains);
			pillar.add(stone, MIN_Y, 64);
			pillar.add(gold, 64, 74);
			pillar.markGround();
			return pillar;
		}), 1);
		List<Face> tops = mesh.solid(Direction.UP);
		assertTrue(tops.contains(new Face(Direction.UP, 1, 74, 1, 2, 74, 2, gold, 15)), tops.toString());
		assertEquals(8, tops.stream().filter(face -> face.state() == stone && face.y0() == 64)
			.mapToInt(Face::area).sum(), tops.toString());
		assertEquals(List.of(
			new Face(Direction.NORTH, 1, 64, 1, 2, 74, 1, gold, 15),
			new Face(Direction.SOUTH, 1, 64, 2, 2, 74, 2, gold, 15),
			new Face(Direction.WEST, 1, 64, 1, 1, 74, 2, gold, 15),
			new Face(Direction.EAST, 2, 64, 1, 2, 74, 2, gold, 15)
		), mesh.solidSides().stream().filter(face -> !face.onBorder(3)).toList());
		// Corners skirt two sides, the others one.
		assertEquals(12, mesh.solidSides().stream().filter(face -> face.onBorder(3)).count());
	}

	@Test
	void aCliffShowsWhatItsNeighbourLeavesOpenAndItsSkirtsReachUnderItsFoot() {
		Mesh mesh = new Mesh(grid(2, column -> stone(column % 2 == 0 ? 64 : 60)), 1);
		assertEquals(List.of(
			new Face(Direction.EAST, 1, 60, 0, 1, 64, 1, stone, 15),
			new Face(Direction.EAST, 1, 60, 1, 1, 64, 2, stone, 15)
		), mesh.solidSides().stream().filter(face -> !face.onBorder(2)).toList());
		// The top of the cliff, beside its foot along the northern edge, skirts down to under the foot.
		assertTrue(mesh.solidSides().contains(new Face(Direction.NORTH, 0, 58, 0, 1, 64, 0, stone, 15)),
			mesh.solidSides().toString());
	}

	@Test
	void aSkirtFollowsTheSlopeDownPastTheEdge() {
		// Ground falling 5 blocks a step towards the west: a coarser tile there may meet the edge 3 steps further down.
		Mesh mesh = new Mesh(grid(3, column -> stone(70 + 5 * (column % 3))), 2);
		List<Face> west = mesh.solid(Direction.WEST).stream().filter(face -> face.onBorder(6)).toList();
		assertEquals(3, west.size(), west.toString());
		for (Face face : west) {
			assertEquals(70 - 3 * 5 - 2 * 2, face.y0(), face.toString());
			assertEquals(70, face.y1(), face.toString());
		}
		// Uphill, towards the east, the skirt stays two steps under the ground.
		List<Face> east = mesh.solid(Direction.EAST).stream().filter(face -> face.onBorder(6)).toList();
		assertEquals(3, east.size(), east.toString());
		for (Face face : east) {
			assertEquals(80 - 2 * 2, face.y0(), face.toString());
		}
	}

	@Test
	void theSeaIsOneSurfaceOverAFloorItDarkens() {
		Mesh mesh = new Mesh(grid(4, column -> {
			LodColumn sea = stone(50);
			sea.add(water, 50, 63);
			sea.waterDepth(13);
			return sea;
		}), 4);
		assertEquals(List.of(new Face(Direction.UP, 0, 63, 0, 16, 63, 16, water, 15)), mesh.water);
		assertEquals(List.of(new Face(Direction.UP, 0, 50, 0, 16, 50, 16, stone, 2)), mesh.solid(Direction.UP));
		List<Face> sides = mesh.solidSides();
		assertEquals(16, sides.size(), sides.toString());
		for (Face face : sides) {
			// Skirts from two steps under the sea floor, as dark as it is.
			assertEquals(42, face.y0(), face.toString());
			assertEquals(50, face.y1(), face.toString());
			assertEquals(2, face.sky(), face.toString());
		}
	}

	@Test
	void aBridgeShowsItsUndersideAndShadesTheGroundUnderIt() {
		Mesh mesh = new Mesh(grid(1, column -> {
			LodColumn bridge = stone(64);
			bridge.add(air, 64, 70);
			bridge.add(planks, 70, 71);
			return bridge;
		}), 1);
		assertEquals(List.of(
			new Face(Direction.UP, 0, 64, 0, 1, 64, 1, stone, LodMesher.SHADED),
			new Face(Direction.UP, 0, 71, 0, 1, 71, 1, planks, 15)
		), mesh.solid(Direction.UP));
		assertEquals(List.of(new Face(Direction.DOWN, 0, 70, 0, 1, 70, 1, planks, LodMesher.SHADED)),
			mesh.solid(Direction.DOWN));
		// The skirts follow the ground, not the bridge over it.
		List<Face> sides = mesh.solidSides();
		assertEquals(4, sides.stream().filter(face -> face.state() == stone && face.y0() == 62 && face.y1() == 64)
			.count(), sides.toString());
		assertEquals(4, sides.stream().filter(face -> face.state() == planks && face.y0() == 70 && face.y1() == 71)
			.count(), sides.toString());
		assertEquals(8, sides.size(), sides.toString());
	}
}
