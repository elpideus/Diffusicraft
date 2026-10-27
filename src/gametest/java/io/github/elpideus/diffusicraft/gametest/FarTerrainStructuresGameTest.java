package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.worldgen.lod.LodColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The structures in the far terrain. Finds the village nearest the spawn and views it from {@value #VIEW_DISTANCE}
 * blocks north, out of the chunks drawn, before its chunks generate, so that the far terrain draws it as the
 * structures' own placement has it ({@code diffusicraft-far-terrain-village}). Then samples it as the far terrain
 * does, {@value #STEP} blocks a column, generates its chunks and views it again from close by
 * ({@code diffusicraft-far-terrain-village-near}). It fails unless the columns over its buildings, most of them, rise
 * to within {@value #TOLERANCE} blocks of their roofs: without the structures they would stay on the ground.
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn the far
 * terrain off.
 */
public class FarTerrainStructuresGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain structures test");
	/** Chunks around the spawn searched for a village. */
	private static final int SEARCH_CHUNKS = 64;
	/** Blocks between the far columns compared with the chunks, the far terrain's nearest. */
	private static final int STEP = 2;
	/** How far below a roof a far column may stop. */
	private static final int TOLERANCE = 3;
	/** Pieces at least this tall and wide are buildings, not streets, lamps or fields. */
	private static final int BUILDING_HEIGHT = 4;
	private static final int BUILDING_WIDTH = 5;
	private static final int VIEW_DISTANCE = 16 * (FarTerrainGameTest.RENDER_DISTANCE + 8);
	private static final int NEAR_DISTANCE = 48;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FarTerrainGameTest.prepare(this, context)) {
			return;
		}
		try (TestSingleplayerContext world = GameTests.createAnyway(context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(FarTerrainGameTest.SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			}))) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			server.runCommand("gamerule advance_time false");
			Village village = server.computeOnServer(s -> find(s.overworld()));
			if (village == null) {
				throw new AssertionError("No village within " + SEARCH_CHUNKS + " chunks of the spawn");
			}
			LOGGER.info("Village {} at {}, {}", village.name, village.box.getCenter(), village.box);
			BlockPos centre = village.box.getCenter();
			int ground = village.ground;
			view(context, server, connection, centre, ground, VIEW_DISTANCE, "village");

			Comparison result = server.computeOnServer(s -> compare(s.overworld(), village.start));
			LOGGER.info("{} of {} far columns over the village's buildings rise to within {} blocks of their roofs, "
					+ "{} over them; {} blocks off on average over the whole village",
				result.risen, result.buildings, TOLERANCE, result.over, String.format(Locale.ROOT, "%.1f",
					result.error));
			view(context, server, connection, centre, ground, NEAR_DISTANCE, "village-near");
			if (result.buildings == 0) {
				throw new AssertionError("The village at " + centre + " has no buildings to compare");
			}
			if (result.risen * 2 < result.buildings) {
				throw new AssertionError("Only " + result.risen + " of " + result.buildings
					+ " far columns over the village's buildings rise to their roofs");
			}
		}
	}

	private record Village(String name, StructureStart start, BoundingBox box, int ground) {
	}

	private record Comparison(int buildings, int risen, int over, double error) {
	}

	/** The village nearest the spawn, its start made as the chunk it starts in is generated that far. */
	private static Village find(ServerLevel level) {
		HolderSet<Structure> villages = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
			.getOrThrow(StructureTags.VILLAGE);
		var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, villages,
			level.getRespawnData().pos(), SEARCH_CHUNKS, false);
		if (found == null) {
			return null;
		}
		Holder<Structure> structure = found.getSecond();
		BlockPos at = found.getFirst();
		StructureStart start = level.getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS)
			.getStartForStructure(structure.value());
		if (start == null || !start.isValid()) {
			throw new AssertionError("The village found at " + at + " has no start");
		}
		BoundingBox first = start.getPieces().getFirst().getBoundingBox();
		return new Village(structure.getRegisteredName(), start, start.getBoundingBox(), first.minY());
	}

	/**
	 * Samples the far columns over a village, generates its chunks, and compares the far columns over its buildings
	 * with the roofs there.
	 */
	private static Comparison compare(ServerLevel level, StructureStart start) {
		BoundingBox box = start.getBoundingBox();
		int minX = Math.floorDiv(box.minX(), STEP) * STEP;
		int minZ = Math.floorDiv(box.minZ(), STEP) * STEP;
		int width = Math.max(Math.ceilDiv(box.maxX() + 1 - minX, STEP), Math.ceilDiv(box.maxZ() + 1 - minZ, STEP));
		int[] far = new int[width * width];
		LodSampler sampler = LodSampler.of(level);
		if (sampler == null) {
			throw new AssertionError("The overworld has no far terrain sampler");
		}
		sampler.sample(minX, minZ, width, STEP, (a, b, column) -> far[b * width + a] = top(column));

		for (int cz = minZ >> 4; cz <= (minZ + width * STEP - 1) >> 4; cz++) {
			for (int cx = minX >> 4; cx <= (minX + width * STEP - 1) >> 4; cx++) {
				level.getChunk(cx, cz);
			}
		}
		int buildings = 0, risen = 0, over = 0, cells = 0;
		long error = 0;
		for (int b = 0; b < width; b++) {
			for (int a = 0; a < width; a++) {
				int x = minX + a * STEP + STEP / 2;
				int z = minZ + b * STEP + STEP / 2;
				int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				int top = far[b * width + a];
				if (box.isInside(x, box.minY(), z)) {
					cells++;
					error += Math.abs(top - roof);
				}
				if (inBuilding(start, x, z)) {
					buildings++;
					if (top >= roof - TOLERANCE) {
						risen++;
					}
					if (top > roof + TOLERANCE) {
						over++;
					}
				}
			}
		}
		return new Comparison(buildings, risen, over, cells == 0 ? 0 : error / (double) cells);
	}

	/** The highest block of a far column's ground, which is the roof where a structure stands. */
	private static int top(LodColumn column) {
		return column.ground() < 0 ? Integer.MIN_VALUE : column.top(column.ground()) - 1;
	}

	/** Whether column {@code (x, z)} lies well inside one of the start's buildings. */
	private static boolean inBuilding(StructureStart start, int x, int z) {
		for (StructurePiece piece : start.getPieces()) {
			BoundingBox box = piece.getBoundingBox();
			if (box.getYSpan() >= BUILDING_HEIGHT && box.getXSpan() >= BUILDING_WIDTH && box.getZSpan() >= BUILDING_WIDTH
				&& x > box.minX() && x < box.maxX() && z > box.minZ() && z < box.maxZ()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Views the village whose ground is {@code ground} at {@code centre} from {@code distance} blocks north of it, high
	 * enough over the hills between.
	 */
	private static void view(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
							 BlockPos centre, int ground, int distance, String name) {
		BlockPos from = centre.north(distance);
		// The level reads the bottom of the world where the chunk is not loaded: generate the one under the eye.
		int below = server.computeOnServer(s -> s.overworld().getChunk(from).getHeight(Heightmap.Types.WORLD_SURFACE,
			from.getX() & 15, from.getZ() & 15) + 1);
		int eye = Math.max(below + 20, ground + distance / 3);
		float pitch = (float) Math.toDegrees(Math.atan2(eye - ground, distance));
		FarTerrainGameTest.view(context, server, connection, from.atY(eye), 0f, pitch, name);
	}
}
