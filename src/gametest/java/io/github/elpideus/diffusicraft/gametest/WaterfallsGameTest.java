package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates a Diffusicraft world of the default size (1:1) and checks the falls of its rivers, which are its only
 * waterfalls, near the spawn or near {@link #RIVER_FALLS_AT}: the highest step of their water must stand as a curtain
 * from the river's water above it down to the river below, and the steepest reach that runs without a fall higher
 * than {@value #MIN_FALL} blocks must come down with its ground, and both must keep their water within the river's
 * banks while the chunks tick. Logs how far apart the water of the channels' columns lies, and saves views of both from
 * the front and the side to {@code build/run/clientGameTest/screenshots}.
 *
 * <p>Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class WaterfallsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft waterfalls test");
	private static final String DEFAULT_SEED = "20261052";
	private static final String SEED = System.getProperty("diffusicraft.seed", DEFAULT_SEED);
	private static final WorldScale SIZE = WorldScale.ULTRA;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 12;
	/** How far from the spawn the rivers are searched. */
	private static final int SEARCH = 4096;
	/** Blocks a river's water steps down beyond which it falls rather than running down a steep reach. */
	private static final int MIN_FALL = 8;
	/** The least step of the water searched for the highest falls: smaller ones are a river's rapids. */
	private static final int MIN_STEP = 3;
	/** The blocks between the first samples of the rivers searched for a fall or a steep reach. */
	private static final int RIVER_STRIDE = 4;
	/** How many samples long a reach is, in a straight line. */
	private static final int REACH = 8;
	/** Blocks around a fall or a reach whose ground is searched for water run out of the river. */
	private static final int AROUND = 16;
	/** Blocks of water running over the ground around a fall or a reach beyond which the river overflows its banks. */
	private static final int MOST_RUNNING = 48;
	/**
	 * Where else to search the rivers, {@code -Pdiffusicraft.riverFallsAt=x,z}, as far as {@value #NEAR} blocks around:
	 * at 1:1 the rivers lie tens of kilometres apart, and the nearest to the spawn of the test's own seed lie 100 km
	 * away, so for that seed the test goes to falls of theirs.
	 */
	private static final @Nullable String RIVER_FALLS_AT = System.getProperty("diffusicraft.riverFallsAt",
		SEED.equals(DEFAULT_SEED) ? "-102393,42310" : null);
	private static final int NEAR = 256;
	private static final int NONE = Integer.MIN_VALUE;
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/** A waterfall found in the world: where it spills, the way it falls, how far, and whether into a pool. */
	record Falls(BlockPos spill, int dx, int dz, int height, boolean pool) {
	}

	/** A straight reach of a river, from the highest water block of its top to that of its bottom. */
	record Reach(BlockPos top, BlockPos bottom) {
		int drop() {
			return top.getY() - bottom.getY();
		}

		double length() {
			return Math.hypot(bottom.getX() - top.getX(), bottom.getZ() - top.getZ());
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the waterfalls test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);

		try (TestSingleplayerContext world = GameTests.createAnyway(context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, SIZE);
			}))) {
			WorldGenerationGameTest.Probe probe = world.getServer().computeOnServer(server ->
				WorldGenerationGameTest.Probe.of(server.overworld()));
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});

			BlockPos center = probe.spawn();
			int reach = SEARCH;
			if (RIVER_FALLS_AT != null) {
				String[] at = RIVER_FALLS_AT.split(",");
				center = new BlockPos(Integer.parseInt(at[0].trim()), 0, Integer.parseInt(at[1].trim()));
				reach = NEAR;
			}
			Rivers rivers = Rivers.of(probe.field());
			int n = 2 * reach / RIVER_STRIDE + 1;
			Grid grid = new Grid(center.getX() - reach, center.getZ() - reach, RIVER_STRIDE, n, n);
			grid.read(rivers);
			Falls highest = findRiverFall(rivers, grid, center);
			Reach steepest = steepestReach(grid);
			if (highest == null && steepest == null) {
				if (RIVER_FALLS_AT != null) {
					throw new AssertionError("No river within " + reach + " blocks of " + center);
				}
				LOGGER.warn("No river within {} blocks of the spawn {}; nothing to check", reach, center);
				return;
			}
			if (highest != null) {
				checkRiverFall(context, world, rivers, highest);
			}
			if (steepest != null) {
				checkReach(context, world, rivers, steepest);
			}
		}
	}

	/**
	 * Views the highest fall and checks that its curtain stands, from the river's water above the step down to the
	 * river below, and that the river keeps its water within its banks around it, once the chunks have ticked.
	 */
	private static void checkRiverFall(ClientGameTestContext context, TestSingleplayerContext world, Rivers rivers,
									   Falls highest) {
		LOGGER.info("The highest river falls: {} blocks at {}", highest.height + 1, highest.spill);
		showFalls(context, world, highest, "river-fall");
		Falls found = world.getServer().computeOnServer(server -> fallsAt(server.overworld(), highest.spill, 1));
		if (found == null || found.height < highest.height - 1) {
			throw new AssertionError("The curtain over the river's step at " + highest.spill + " should fall "
				+ highest.height + " blocks, but falls " + (found == null ? "none" : found.height));
		}
		List<Falls> beside = world.getServer().computeOnServer(server ->
			findFalls(server.overworld(), ChunkPos.containing(highest.spill), 1, Math.max(1, found.height - 1)));
		Curtain curtain = curtains(beside).stream().filter(c -> c.middle.dx == found.dx && c.middle.dz == found.dz)
			.max(Comparator.comparingInt(Curtain::width)).orElseThrow();
		LOGGER.info("The river falls at {} are {} blocks high and {} wide, into a pool: {}", found.spill,
			found.height + 1, curtain.width, found.pool);
		Grid box = new Grid(highest.spill.getX() - AROUND, highest.spill.getZ() - AROUND, 1, 2 * AROUND + 1,
			2 * AROUND + 1);
		box.read(rivers);
		int running = world.getServer().computeOnServer(server -> runningBeside(server.overworld(), box));
		LOGGER.info("{} blocks of water run over the ground beside the river around the falls", running);
		if (running > MOST_RUNNING) {
			throw new AssertionError("The river lets " + running + " blocks of water run over its banks around the "
				+ "falls at " + highest.spill);
		}
	}

	/**
	 * The highest fall of the rivers sampled on {@code grid}, from the rivers' own columns, or {@code null}: the column
	 * of a channel whose water stands most below the water of a channel beside it, {@value #MIN_STEP} blocks at least,
	 * and spills from there. Every block between two samples whose water lies that far apart is searched. Logs how far
	 * the water of the samples next to each other lies apart.
	 */
	private static @Nullable Falls findRiverFall(Rivers rivers, Grid grid, BlockPos center) {
		// Pairs of samples whose water lies 0, 1, 2 to 5, 6 to 8 and 9 or more blocks apart.
		int[] steps = new int[5];
		Falls best = null;
		for (int a = 0; a < grid.h; a++) {
			for (int b = 0; b < grid.w; b++) {
				for (int d = 0; d < 2; d++) {
					int own = grid.at(a, b), next = grid.at(a + DZ[d], b + DX[d]);
					if (own == NONE || next == NONE) {
						continue;
					}
					int apart = Math.abs(own - next);
					steps[apart == 0 ? 0 : apart == 1 ? 1 : apart <= 5 ? 2 : apart <= MIN_FALL ? 3 : 4]++;
					if (apart < MIN_STEP) {
						continue;
					}
					// The blocks between the two samples, and a few around.
					int side = grid.stride + 8;
					Grid fine = new Grid(grid.x(b) - 4, grid.z(a) - 4, 1, side, side);
					fine.read(rivers);
					for (int i = 1; i < side - 1; i++) {
						for (int j = 1; j < side - 1; j++) {
							int low = fine.at(i, j);
							for (int k = 0; k < 4 && low != NONE; k++) {
								int up = fine.at(i + DZ[k], j + DX[k]);
								if (up != NONE && up - low >= MIN_STEP
									&& (best == null || up - low - 1 > best.height)) {
									// It spills at the top of the column below the step, away from the one above.
									best = new Falls(new BlockPos(fine.x(j), up, fine.z(i)), -DX[k], -DZ[k],
										up - low - 1, false);
								}
							}
						}
					}
				}
			}
		}
		LOGGER.info("River channels around {}: samples {} blocks apart whose water lies 0, 1, 2-5, 6-{} and more "
			+ "blocks apart: {}; the highest falls: {}", center, grid.stride, MIN_FALL, Arrays.toString(steps), best);
		return best;
	}

	/**
	 * The steepest straight reach of the rivers sampled on {@code grid}, or {@code null}: {@value #REACH} samples along
	 * a row, a column or a diagonal, all in a river's channel and none more than {@value #MIN_FALL} blocks below the
	 * one before, whose water comes down the most for its length.
	 */
	private static @Nullable Reach steepestReach(Grid grid) {
		int[][] ways = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
		Reach best = null;
		double steepest = 0;
		for (int a = 0; a < grid.h; a++) {
			for (int b = 0; b < grid.w; b++) {
				for (int[] way : ways) {
					int first = grid.at(a, b), last = first;
					for (int s = 1; s <= REACH && last != NONE; s++) {
						int next = grid.at(a + s * way[1], b + s * way[0]);
						last = next == NONE || Math.abs(next - last) > MIN_FALL ? NONE : next;
					}
					if (first == NONE || last == NONE || first == last) {
						continue;
					}
					int a2 = a + REACH * way[1], b2 = b + REACH * way[0];
					BlockPos one = new BlockPos(grid.x(b), first, grid.z(a)), two = new BlockPos(grid.x(b2), last,
						grid.z(a2));
					Reach reach = first > last ? new Reach(one, two) : new Reach(two, one);
					double slope = reach.drop() / reach.length();
					if (slope > steepest) {
						steepest = slope;
						best = reach;
					}
				}
			}
		}
		return best;
	}

	/**
	 * Views the reach from below and from its side, logs how its water steps down, and checks that the water keeps
	 * within its banks once the chunks have ticked: that no more than {@value #MOST_RUNNING} blocks of it run over the
	 * ground beside its channels, as it would where its banks stand lower than its water.
	 */
	private static void checkReach(ClientGameTestContext context, TestSingleplayerContext world, Rivers rivers,
								   Reach reach) {
		LOGGER.info("The steepest reach of the rivers comes down {} blocks over {} from {} to {}", reach.drop(),
			Math.round(reach.length()), reach.top, reach.bottom);
		int x0 = Math.min(reach.top.getX(), reach.bottom.getX()) - AROUND;
		int z0 = Math.min(reach.top.getZ(), reach.bottom.getZ()) - AROUND;
		Grid box = new Grid(x0, z0, 1, Math.abs(reach.top.getX() - reach.bottom.getX()) + 2 * AROUND + 1,
			Math.abs(reach.top.getZ() - reach.bottom.getZ()) + 2 * AROUND + 1);
		box.read(rivers);
		// Neighbouring columns of the river whose water lies 0, 1, 2, 3 to 8 and 9 or more blocks apart.
		int[] steps = new int[5];
		for (int i = 0; i < box.h; i++) {
			for (int j = 0; j < box.w; j++) {
				for (int d = 0; d < 2; d++) {
					int own = box.at(i, j), next = box.at(i + DZ[d], j + DX[d]);
					if (own != NONE && next != NONE) {
						int apart = Math.abs(own - next);
						steps[Math.min(apart, 2) + (apart > 2 ? 1 : 0) + (apart > MIN_FALL ? 1 : 0)]++;
					}
				}
			}
		}
		LOGGER.info("Around the reach, neighbouring columns of the river whose water lies 0, 1, 2, 3-{} and more blocks "
			+ "apart: {}", MIN_FALL, Arrays.toString(steps));

		BlockPos middle = new BlockPos((reach.top.getX() + reach.bottom.getX()) / 2,
			(reach.top.getY() + reach.bottom.getY()) / 2, (reach.top.getZ() + reach.bottom.getZ()) / 2);
		double ax = (reach.bottom.getX() - reach.top.getX()) / reach.length();
		double az = (reach.bottom.getZ() - reach.top.getZ()) / reach.length();
		int out = (int) Math.max(32, reach.length());
		// From beyond its bottom, looking up the reach, and from its side, looking down on it.
		BlockPos below = reach.bottom.offset((int) Math.round(ax * 24), 6, (int) Math.round(az * 24));
		BlockPos side = middle.offset((int) Math.round(-az * out), reach.drop() / 2 + 16, (int) Math.round(ax * out));
		for (BlockPos camera : new BlockPos[]{below, side}) {
			BlockPos clear = world.getServer().computeOnServer(server -> inAir(server.overworld(), camera));
			double vx = middle.getX() - clear.getX(), vy = middle.getY() - clear.getY(), vz = middle.getZ() - clear.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
			float pitch = (float) Math.toDegrees(Math.atan2(-vy, Math.hypot(vx, vz)));
			WorldGenerationGameTest.view(context, world, clear, yaw, pitch,
				"river-reach" + (camera == below ? "-front" : "-side"));
		}

		int running = world.getServer().computeOnServer(server -> runningBeside(server.overworld(), box));
		LOGGER.info("{} blocks of water run over the ground beside the reach's channels", running);
		if (running > MOST_RUNNING) {
			throw new AssertionError("The river's steep reach from " + reach.top + " to " + reach.bottom + " lets "
				+ running + " blocks of water run over its banks");
		}
	}

	/** The columns of {@code box} off the rivers' channels whose top block is running water, in the loaded chunks. */
	private static int runningBeside(ServerLevel level, Grid box) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int running = 0;
		for (int i = 0; i < box.h; i++) {
			for (int j = 0; j < box.w; j++) {
				int x = box.x(j), z = box.z(i);
				if (box.channel[i * box.w + j] || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				FluidState fluid = level.getFluidState(at.set(x, top, z));
				running += fluid.is(FluidTags.WATER) && !fluid.isSource() ? 1 : 0;
			}
		}
		return running;
	}

	/**
	 * The rivers' columns on a grid of {@code w} by {@code h} columns {@code stride} blocks apart from
	 * {@code (x0, z0)}, row by row: whether each is in a channel, a lake's too, and the highest water block of the
	 * rivers' channels, not the lakes', or {@link #NONE} where none runs.
	 */
	private static final class Grid {
		final int x0, z0, stride, w, h;
		final boolean[] channel;
		final int[] water;

		Grid(int x0, int z0, int stride, int w, int h) {
			this.x0 = x0;
			this.z0 = z0;
			this.stride = stride;
			this.w = w;
			this.h = h;
			channel = new boolean[w * h];
			water = new int[w * h];
		}

		void read(Rivers rivers) {
			for (int i = 0; i < h; i++) {
				for (int j = 0; j < w; j++) {
					RiverColumn column = rivers.column(SIZE, x(j), z(i));
					channel[i * w + j] = column.channel;
					water[i * w + j] = column.channel && !column.lake ? column.waterTop : NONE;
				}
			}
		}

		int at(int i, int j) {
			return i < 0 || j < 0 || i >= h || j >= w ? NONE : water[i * w + j];
		}

		int x(int j) {
			return x0 + j * stride;
		}

		int z(int i) {
			return z0 + i * stride;
		}
	}

	/**
	 * The falls in the loaded chunks within {@code radius} of {@code center}, tallest first: water spilling from still
	 * water beside it at the top of its column and falling at least {@code least} blocks. One per spill, so falls three
	 * blocks wide count three times.
	 */
	private static List<Falls> findFalls(ServerLevel level, ChunkPos center, int radius, int least) {
		List<Falls> found = new ArrayList<>();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
			for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (int z = cz * 16; z < cz * 16 + 16; z++) {
					for (int x = cx * 16; x < cx * 16 + 16; x++) {
						int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
						Falls falls = fallsAt(level, at.set(x, top, z), least);
						if (falls != null) {
							found.add(falls);
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingInt(Falls::height).reversed());
		return found;
	}

	/** The falls spilling at {@code spill}, or {@code null} if no water spills there to fall {@code least} blocks. */
	private static @Nullable Falls fallsAt(ServerLevel level, BlockPos spill, int least) {
		FluidState top = level.getFluidState(spill);
		if (!top.is(FluidTags.WATER) || top.isSource() || top.getValue(FlowingFluid.FALLING)) {
			return null;
		}
		int dir = -1;
		for (int d = 0; d < 4 && dir < 0; d++) {
			dir = level.getFluidState(spill.offset(-DX[d], 0, -DZ[d])).isSourceOfType(Fluids.WATER) ? d : -1;
		}
		if (dir < 0) {
			return null;
		}
		int height = 0;
		BlockPos below = spill.below();
		while (isFalling(level.getFluidState(below))) {
			height++;
			below = below.below();
		}
		if (height < least) {
			return null;
		}
		boolean pool = level.getFluidState(below).isSource() && level.getFluidState(below.below()).isSource();
		return new Falls(spill.immutable(), DX[dir], DZ[dir], height, pool);
	}

	private static boolean isFalling(FluidState fluid) {
		return fluid.is(FluidTags.WATER) && !fluid.isSource() && fluid.getValue(FlowingFluid.FALLING);
	}

	/** Falls side by side over the same edge: the spill in their middle, and how many spills wide they are. */
	private record Curtain(Falls middle, int width) {
	}

	/** The falls of {@code found} grouped side by side over their edges, widest first, then tallest. */
	private static List<Curtain> curtains(List<Falls> found) {
		Map<BlockPos, Falls> spills = new HashMap<>();
		found.forEach(falls -> spills.put(falls.spill, falls));
		Set<BlockPos> seen = new HashSet<>();
		List<Curtain> curtains = new ArrayList<>();
		for (Falls falls : found) {
			if (!seen.add(falls.spill)) {
				continue;
			}
			// Along the edge, both ways, while the next spill falls the same way.
			List<Falls> row = new ArrayList<>(List.of(falls));
			for (int side : new int[]{1, -1}) {
				BlockPos next = falls.spill.offset(-falls.dz * side, 0, falls.dx * side);
				Falls beside;
				while ((beside = spills.get(next)) != null && beside.dx == falls.dx && beside.dz == falls.dz
					&& seen.add(next)) {
					if (side > 0) {
						row.add(beside);
					} else {
						row.addFirst(beside);
					}
					next = next.offset(-falls.dz * side, 0, falls.dx * side);
				}
			}
			curtains.add(new Curtain(row.get(row.size() / 2), row.size()));
		}
		curtains.sort(Comparator.comparingInt(Curtain::width).thenComparingInt(c -> c.middle.height).reversed());
		return curtains;
	}

	/**
	 * Views the falls from the front, a little below their middle, and from high to one side, as {@code name-front}
	 * and {@code name-side}.
	 */
	private static void showFalls(ClientGameTestContext context, TestSingleplayerContext world, Falls falls,
								  String name) {
		BlockPos middle = falls.spill.below(falls.height / 2);
		int out = Math.max(24, falls.height);
		BlockPos front = middle.offset(falls.dx * out, -falls.height / 6, falls.dz * out);
		BlockPos side = middle.offset(falls.dx * out / 2 - falls.dz * out, falls.height / 2 + 8,
			falls.dz * out / 2 + falls.dx * out);
		for (BlockPos camera : new BlockPos[]{front, side}) {
			BlockPos clear = world.getServer().computeOnServer(server -> inAir(server.overworld(), camera));
			double vx = middle.getX() - clear.getX(), vy = middle.getY() - clear.getY(), vz = middle.getZ() - clear.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
			float pitch = (float) Math.toDegrees(Math.atan2(-vy, Math.hypot(vx, vz)));
			WorldGenerationGameTest.view(context, world, clear, yaw, pitch, name + (camera == front ? "-front" : "-side"));
		}
	}

	/** {@code camera}, raised out of the ground if it is in it. */
	private static BlockPos inAir(ServerLevel level, BlockPos camera) {
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, camera.getX(), camera.getZ());
		return camera.getY() > ground + 1 ? camera : camera.atY(ground + 3);
	}
}
