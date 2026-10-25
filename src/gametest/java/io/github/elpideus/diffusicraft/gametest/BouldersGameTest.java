package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Creates a Diffusicraft world of the default size (1:1), looks for cold, level land and for steep land near the spawn,
 * and checks that boulders lie there once its chunks are generated: erratics of granite, gneiss and diorite on the
 * cold land, and rocks at the feet of the cliffs. Logs how many there are and how big, and saves views of the biggest
 * of each to {@code build/run/clientGameTest/screenshots}.
 *
 * <p>Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class BouldersGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft boulders test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final WorldScale SIZE = WorldScale.ULTRA;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 12;
	/** How far from the spawn the land is looked for, and the blocks between the samples. */
	private static final int SEARCH = 4096;
	private static final int STRIDE = 128;
	/** How far out from a sample its ground is compared, and how far apart the areas visited lie. */
	private static final int REACH = 24;
	/** The relief from which steep land suits rockfall no better, so that the nearest of the cliffs is visited. */
	private static final int HIGH = 120;
	private static final int APART = 768;
	private static final int AREAS = 3;
	/** The year's mean temperature up to which land is cold enough for erratics, in °C. */
	private static final float COLD = 6f;
	/** Chunks to each side of an area's middle that are searched for boulders. */
	private static final int SCAN = 8;
	/** The most columns a boulder covers; a larger patch of rock is a face or an outcrop. */
	private static final int MAX_COLUMNS = 700;
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/**
	 * A boulder found in the world: its highest column, the rock of it, how many columns it covers and how high it
	 * stands over the ground.
	 */
	record Found(BlockPos top, String rock, int columns, int height) {
	}

	/** A sample of land, and how well it suits what is looked for there. */
	private record Area(BlockPos at, double score, float temperature, int relief) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the boulders test");
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
			int erratics = visit(context, world, probe, "erratics", findAreas(probe, true), BouldersGameTest::isErratic);
			int fallen = visit(context, world, probe, "rockfall", findAreas(probe, false), BouldersGameTest::isRock);
			if (erratics == 0) {
				throw new AssertionError("No erratic on the cold, level land near the spawn");
			}
			if (fallen == 0) {
				throw new AssertionError("No boulder at the feet of the cliffs near the spawn");
			}
		}
	}

	/** Visits the areas, logs the boulders of {@code rock} found in each and views the biggest. How many there were. */
	private static int visit(ClientGameTestContext context, TestSingleplayerContext world,
							 WorldGenerationGameTest.Probe probe, String name, List<Area> areas,
							 Predicate<BlockState> rock) {
		if (areas.isEmpty()) {
			LOGGER.warn("No land for {} within {} blocks of the spawn {}", name, SEARCH, probe.spawn());
			return 0;
		}
		TerrainSurface surface = probe.field().surface(SIZE);
		LodSampler sampler = world.getServer().computeOnServer(server -> LodSampler.of(server.overworld()));
		if (sampler == null) {
			throw new AssertionError("No LOD sampler for the Diffusicraft overworld");
		}
		int total = 0;
		for (int k = 0; k < areas.size(); k++) {
			Area area = areas.get(k);
			BlockPos at = area.at.atY((int) surface.y(area.at.getX(), area.at.getZ()));
			LOGGER.info("{} area {}: {} at {} °C with {} blocks of relief", name, k, at, area.temperature, area.relief);
			WorldGenerationGameTest.view(context, world, at.above(100), 0f, 60f, name + "-area-" + k);
			ChunkPos chunk = ChunkPos.containing(at);
			int[] ground = ground(sampler, chunk, SCAN);
			List<Found> found = world.getServer().computeOnServer(server ->
				find(server.overworld(), ground, chunk, SCAN, rock));
			LOGGER.info("{} area {}: {} boulders, the biggest {}", name, k, found.size(),
				found.stream().limit(8).toList());
			total += found.size();
			if (!found.isEmpty()) {
				show(context, world, found.getFirst(), name + "-" + k);
			}
		}
		return total;
	}

	/**
	 * Samples of land within {@value #SEARCH} blocks of the spawn, at least {@value #APART} blocks apart, with no sea
	 * within {@value #REACH} blocks: for erratics, the nearest level land at {@value #COLD} °C or colder; otherwise
	 * the land with the greatest fall within {@value #REACH} blocks, up to {@value #HIGH}, and the nearest of those.
	 */
	private static List<Area> findAreas(WorldGenerationGameTest.Probe probe, boolean cold) {
		TerrainSurface surface = probe.field().surface(SIZE);
		List<Area> samples = new ArrayList<>();
		for (int dz = -SEARCH; dz <= SEARCH; dz += STRIDE) {
			for (int dx = -SEARCH; dx <= SEARCH; dx += STRIDE) {
				int x = probe.spawn().getX() + dx, z = probe.spawn().getZ() + dz;
				float temperature = ClimateColumn.at(probe.field(), SIZE, x, z).column.temperature;
				if (cold && temperature > COLD) {
					continue;
				}
				float y = surface.y(x, z), low = y;
				int relief = 0;
				for (int d = 0; d < 4; d++) {
					float around = surface.y(x + REACH * DX[d], z + REACH * DZ[d]);
					relief = Math.max(relief, (int) Math.abs(y - around));
					low = Math.min(low, around);
				}
				// The foot of a cliff on land: the rocks that fall into the sea are not set down.
				if (low > SIZE.seaLevel() + 2 && (cold ? relief <= 6 : relief >= REACH)) {
					samples.add(new Area(new BlockPos(x, 0, z),
						cold ? -Math.hypot(dx, dz) : Math.min(relief, HIGH) - Math.hypot(dx, dz) / SEARCH, temperature,
						relief));
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
	 * The ground that the terrain lays in the chunks within {@code radius} of {@code center}, before any feature, as
	 * {@link LodSampler} has it: the height of the top block of each column, by {@code x * side + z}.
	 */
	private static int[] ground(LodSampler sampler, ChunkPos center, int radius) {
		int x1 = (center.x() - radius) * 16, z1 = (center.z() - radius) * 16, side = (2 * radius + 1) * 16;
		int[] ground = new int[side * side];
		sampler.sample(x1, z1, side, 1, (x, z, column) -> ground[x * side + z] = column.top(column.ground()) - 1);
		return ground;
	}

	/**
	 * The boulders in the loaded chunks within {@code radius} of {@code center}, biggest first: patches of two to
	 * {@value #MAX_COLUMNS} columns topped with {@code rock} that stand two blocks or more over the {@code ground}. A
	 * boulder that leans on a rock face is then told apart from it, as the face stands no higher than the ground.
	 */
	static List<Found> find(ServerLevel level, int[] ground, ChunkPos center, int radius, Predicate<BlockState> rock) {
		int x1 = (center.x() - radius) * 16, z1 = (center.z() - radius) * 16, side = (2 * radius + 1) * 16;
		int[] tops = new int[side * side];
		BlockState[] blocks = new BlockState[side * side];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < side; i++) {
			for (int j = 0; j < side; j++) {
				int x = x1 + i, z = z1 + j;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					tops[i * side + j] = Integer.MIN_VALUE;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockState state = level.getBlockState(pos.set(x, y, z));
				while (state.is(Blocks.MOSS_CARPET) || state.is(Blocks.SNOW)) {
					state = level.getBlockState(pos.set(x, --y, z));
				}
				tops[i * side + j] = y;
				blocks[i * side + j] = state;
			}
		}
		List<Found> found = new ArrayList<>();
		boolean[] seen = new boolean[side * side];
		boolean[] standing = new boolean[side * side];
		for (int k = 0; k < tops.length; k++) {
			standing[k] = blocks[k] != null && rock.test(blocks[k]) && tops[k] >= ground[k] + 2;
		}
		for (int start = 0; start < tops.length; start++) {
			if (seen[start] || !standing[start]) {
				continue;
			}
			LongOpenHashSet patch = new LongOpenHashSet();
			LongArrayFIFOQueue next = new LongArrayFIFOQueue();
			next.enqueue(start);
			seen[start] = true;
			int highest = start, height = 0;
			boolean edge = false;
			while (!next.isEmpty()) {
				int at = (int) next.dequeueLong();
				patch.add(at);
				if (tops[at] > tops[highest]) {
					highest = at;
				}
				height = Math.max(height, tops[at] - ground[at]);
				int i = at / side, j = at % side;
				for (int d = 0; d < 4; d++) {
					int ni = i + DX[d], nj = j + DZ[d], n = ni * side + nj;
					if (ni < 0 || nj < 0 || ni >= side || nj >= side || blocks[n] == null) {
						edge = true;
						continue;
					}
					if (standing[n] && !seen[n]) {
						seen[n] = true;
						next.enqueue(n);
					}
				}
			}
			if (!edge && patch.size() >= 2 && patch.size() <= MAX_COLUMNS) {
				found.add(new Found(new BlockPos(x1 + highest / side, tops[highest], z1 + highest % side),
					BuiltInRegistries.BLOCK.getKey(blocks[highest].getBlock()).getPath(), patch.size(), height));
			}
		}
		found.sort(Comparator.comparingInt(Found::columns).reversed());
		return found;
	}

	private static boolean isErratic(BlockState state) {
		return state.is(Blocks.GRANITE) || state.is(DiffusicraftBlocks.GNEISS) || state.is(Blocks.DIORITE);
	}

	private static boolean isRock(BlockState state) {
		return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.SANDSTONE)
			|| state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE);
	}

	/** Views a boulder from the side, a little above it, as {@code name}. */
	private static void show(ClientGameTestContext context, TestSingleplayerContext world, Found boulder, String name) {
		int out = Math.max(10, (int) (2.5 * Math.sqrt(boulder.columns)));
		BlockPos camera = boulder.top.offset(-out, 0, -out);
		int ground = world.getServer().computeOnServer(server ->
			server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, camera.getX(), camera.getZ()));
		BlockPos clear = camera.atY(Math.max(boulder.top.getY() + 3, ground + 3));
		double vx = boulder.top.getX() - clear.getX(), vy = boulder.top.getY() - 1 - clear.getY();
		double vz = boulder.top.getZ() - clear.getZ();
		float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
		float pitch = (float) Math.toDegrees(Math.atan2(-vy, Math.hypot(vx, vz)));
		WorldGenerationGameTest.view(context, world, clear, yaw, pitch, name);
	}
}
