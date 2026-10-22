package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates a Diffusicraft world of the default size (1:1), looks for the wettest steep land near the spawn, and checks
 * that waterfalls stand there once its chunks are generated: falls of 8 blocks and more, spilling from still water
 * beside them, that keep their water while the chunks tick. Logs how wide they are, and saves views of the area and of
 * the tallest and the widest falls, from the front and from the side, to {@code build/run/clientGameTest/screenshots}.
 *
 * <p>Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class WaterfallsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft waterfalls test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final WorldScale SIZE = WorldScale.ULTRA;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 12;
	/** How far from the spawn steep, wet land is looked for, and the blocks between the samples. */
	private static final int SEARCH = 4096;
	private static final int STRIDE = 128;
	/** How far out from a sample its ground is compared, and how far apart the areas visited lie. */
	private static final int REACH = 24;
	private static final int APART = 768;
	private static final int AREAS = 3;
	/** Chunks to each side of an area's middle that are searched for falls. */
	private static final int SCAN = 8;
	private static final int MIN_FALL = 8;
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/** A waterfall found in the world: where it spills, the way it falls, how far, and whether into a pool. */
	record Falls(BlockPos spill, int dx, int dz, int height, boolean pool) {
	}

	/** A sample of steep, wet land. */
	private record Area(BlockPos at, double score, float rain, int relief) {
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

		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, SIZE);
			})
			.create()) {
			WorldGenerationGameTest.Probe probe = world.getServer().computeOnServer(server ->
				WorldGenerationGameTest.Probe.of(server.overworld()));
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});

			List<Area> areas = findAreas(probe);
			if (areas.isEmpty()) {
				LOGGER.warn("No steep, wet land within {} blocks of the spawn {}; nothing to check", SEARCH, probe.spawn());
				return;
			}
			TerrainSurface surface = probe.field().surface(SIZE);
			for (int k = 0; k < areas.size(); k++) {
				Area area = areas.get(k);
				LOGGER.info("Area {}: {} with {} mm of rain a year and {} blocks of relief", k, area.at, area.rain,
					area.relief);
				BlockPos at = area.at.atY((int) surface.y(area.at.getX(), area.at.getZ()));
				WorldGenerationGameTest.view(context, world, at.above(120), 0f, 60f, "waterfalls-area-" + k);
				List<Falls> found = world.getServer().computeOnServer(server ->
					findFalls(server.overworld(), ChunkPos.containing(at), SCAN));
				LOGGER.info("Area {}: {} falls of {} blocks or more, {} into pools: {}", k, found.size(), MIN_FALL,
					found.stream().filter(Falls::pool).count(), found.stream().limit(12).toList());
				if (found.isEmpty()) {
					continue;
				}
				List<Curtain> curtains = curtains(found);
				LOGGER.info("Area {}: {} waterfalls, blocks wide: {}", k, curtains.size(),
					curtains.stream().map(Curtain::width).toList());
				Falls tallest = found.getFirst();
				showFalls(context, world, tallest, "waterfall");
				Curtain widest = curtains.getFirst();
				if (widest.width > 1) {
					LOGGER.info("The widest falls, {} blocks wide, spill at {} and drop {}", widest.width,
						widest.middle.spill, widest.middle.height);
					showFalls(context, world, widest.middle, "waterfall-wide");
				}
				String problem = world.getServer().computeOnServer(server -> checkHolds(server.overworld(), tallest));
				if (problem != null) {
					throw new AssertionError(problem);
				}
				LOGGER.info("The falls at {} hold their water after the chunks have ticked", tallest.spill);
				return;
			}
			throw new AssertionError("No waterfall in the " + areas.size() + " steepest, wettest areas near the spawn");
		}
	}

	/**
	 * The steepest, wettest points within {@value #SEARCH} blocks of the spawn, at least {@value #APART} blocks apart:
	 * the ground's greatest fall within {@value #REACH} blocks, weighted by how much of the waterfalls' full chance the
	 * rain gives.
	 */
	private static List<Area> findAreas(WorldGenerationGameTest.Probe probe) {
		TerrainSurface surface = probe.field().surface(SIZE);
		List<Area> samples = new ArrayList<>();
		for (int dz = -SEARCH; dz <= SEARCH; dz += STRIDE) {
			for (int dx = -SEARCH; dx <= SEARCH; dx += STRIDE) {
				int x = probe.spawn().getX() + dx, z = probe.spawn().getZ() + dz;
				float rain = ClimateColumn.at(probe.field(), SIZE, x, z).column.precipitation;
				double wet = Math.clamp((rain - 600f) / 1400f, 0f, 1f);
				if (wet <= 0) {
					continue;
				}
				float y = surface.y(x, z);
				int relief = 0;
				for (int d = 0; d < 4; d++) {
					relief = Math.max(relief, (int) (y - surface.y(x + REACH * DX[d], z + REACH * DZ[d])));
				}
				if (y > SIZE.seaLevel() && relief >= REACH) {
					samples.add(new Area(new BlockPos(x, 0, z), relief * wet, rain, relief));
				}
			}
		}
		samples.sort(Comparator.comparingDouble(Area::score).reversed());
		List<Area> areas = new ArrayList<>();
		for (Area sample : samples) {
			if (areas.size() < AREAS && areas.stream().allMatch(a -> a.at.distManhattan(sample.at) >= APART)) {
				areas.add(sample);
			}
		}
		return areas;
	}

	/**
	 * The falls in the loaded chunks within {@code radius} of {@code center}, tallest first: water spilling from still
	 * water beside it at the top of its column and falling at least {@value #MIN_FALL} blocks. One per spill, so falls
	 * three blocks wide count three times.
	 */
	static List<Falls> findFalls(ServerLevel level, ChunkPos center, int radius) {
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
						Falls falls = fallsAt(level, at.set(x, top, z));
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

	/** The falls spilling at {@code spill}, or {@code null} if no water spills there. */
	private static @Nullable Falls fallsAt(ServerLevel level, BlockPos spill) {
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
		if (height < MIN_FALL) {
			return null;
		}
		boolean pool = level.getFluidState(below).isSource() && level.getFluidState(below.below()).isSource();
		return new Falls(spill.immutable(), DX[dir], DZ[dir], height, pool);
	}

	private static boolean isFalling(FluidState fluid) {
		return fluid.is(FluidTags.WATER) && !fluid.isSource() && fluid.getValue(FlowingFluid.FALLING);
	}

	/** Falls side by side over the same edge: the spill in their middle, and how many spills wide they are. */
	record Curtain(Falls middle, int width) {
	}

	/** The falls of {@code found} grouped side by side over their edges, widest first, then tallest. */
	static List<Curtain> curtains(List<Falls> found) {
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

	/**
	 * Whether the falls still stand after the chunks have ticked: as tall as they were, with little of their water run
	 * off over the ground around their foot.
	 */
	private static @Nullable String checkHolds(ServerLevel level, Falls falls) {
		Falls now = fallsAt(level, falls.spill);
		if (now == null || now.height < falls.height - 1) {
			return "The falls at " + falls.spill + " fell " + falls.height + " blocks and now " + (now == null ? "none"
				: now.height);
		}
		int foot = falls.spill.getY() - falls.height, running = 0;
		for (BlockPos pos : BlockPos.betweenClosed(falls.spill.offset(-12, -falls.height - 2, -12),
			falls.spill.offset(12, -falls.height + 2, 12))) {
			FluidState fluid = level.getFluidState(pos);
			running += fluid.is(FluidTags.WATER) && !fluid.isSource() && !fluid.getValue(FlowingFluid.FALLING) ? 1 : 0;
		}
		LOGGER.info("The falls at {} land at y={}, in a pool: {}; {} blocks of running water around their foot",
			falls.spill, foot, falls.pool, running);
		return falls.pool && running > 24 ? "The pool under the falls at " + falls.spill + " lets " + running
			+ " blocks of water run off" : null;
	}
}
