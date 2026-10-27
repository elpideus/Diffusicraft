package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.biome.ModdedBiomes;
import io.github.elpideus.diffusicraft.worldgen.material.OtherModsRule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Checks that other mods' biomes go only into the regions {@link ModdedBiomes} opens to them, and that Diffusicraft's
 * own biomes keep the rest, in a world of {@link WorldGenerationGameTest#SIZE the tested size}, whose dimension type is
 * Diffusicraft's. It runs only when named, with biome mods installed and the models downloaded:
 * {@code -PdevMods=biomes-o-plenty,glitchcore,terrablender,terralith,lithostitched,tectonic,wilder-wild,frozenlib,terrestria,promenade -Pdiffusicraft.gametests=ModdedBiomes}.
 *
 * <p>It writes {@code diffusicraft-map-modded-biomes.png} to {@code build/run/clientGameTest/screenshots}, the biomes
 * around the spawn with the borders of the open regions in black, and a view of the nearest biome of another mod. And
 * {@code diffusicraft-terrain-heights.txt}, the height of the ground on a grid around the origin, which must be the
 * same as in a run without the mods: they may add biomes, never change the terrain. Any two runs differ by a block in
 * a dozen or so of its 4,096 points, with the mods or without.
 */
public class ModdedBiomesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft modded biomes test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	/** Map size in pixels, and blocks per pixel: about eight regions across. */
	private static final int MAP_SIZE = 256;
	private static final int MAP_STRIDE = 32;
	/** The grid of ground heights: 8 km across. */
	private static final int HEIGHTS_SIZE = 64;
	private static final int HEIGHTS_STRIDE = 128;
	private static final int TICKS_PER_MINUTE = 20 * 60;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!GameTests.named(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the modded biomes test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, WorldGenerationGameTest.SIZE);
			})
			.create()) {
			Survey survey = world.getServer().computeOnServer(server -> survey(server.overworld()));
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("weather clear");
			if (survey.theirs != null) {
				WorldGenerationGameTest.view(context, world, survey.theirs, 0, 30, "modded-biome");
			}
			if (survey.problem != null) {
				throw new AssertionError(survey.problem);
			}
		}
	}

	/**
	 * What the survey found: a point above the biome of another mod nearest the spawn, if any, and the first problem.
	 */
	private record Survey(@Nullable BlockPos theirs, @Nullable String problem) {
	}

	private static Survey survey(ServerLevel level) {
		WorldGenerationGameTest.Probe probe = WorldGenerationGameTest.Probe.of(level);
		saveHeights(level);
		long modded = probe.biomes().possibleBiomes().stream().filter(OtherModsRule::foreign).count();
		LOGGER.info("The biome source holds {} biomes of other mods; {}% of the world is open to them", modded,
			Math.round(ModdedBiomes.share() * 100));
		if (modded == 0) {
			return new Survey(null, "The biome source holds no biome of another mod: run with biome mods installed");
		}
		BiomeWorld biomeWorld = BiomeWorld.of(probe.state().createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		long seed = biomeWorld.field().seed();
		float share = ModdedBiomes.share();
		int half = MAP_SIZE * MAP_STRIDE / 2;
		int x0 = probe.spawn().getX() - half, z0 = probe.spawn().getZ() - half;
		String[] biome = new String[MAP_SIZE * MAP_SIZE];
		boolean[] open = new boolean[MAP_SIZE * MAP_SIZE];
		int[] surface = new int[MAP_SIZE * MAP_SIZE];
		Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
		IntStream.range(0, MAP_SIZE).parallel().forEach(row -> {
			BiomeResolver resolver = probe.biomes().createResolver(
				probe.state().createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			for (int col = 0; col < MAP_SIZE; col++) {
				int x = x0 + col * MAP_STRIDE, z = z0 + row * MAP_STRIDE;
				int px = row * MAP_SIZE + col;
				surface[px] = Math.max(WorldGenerationGameTest.surface(probe, new BlockPos(x, 0, z)), 63);
				Holder<Biome> b = resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface[px]),
					QuartPos.fromBlock(z));
				biome[px] = b.unwrapKey().map(ResourceKey::identifier).map(Object::toString).orElse("?");
				// The biome source asks at the middle of each quart.
				open[px] = ModdedBiomes.open(seed, QuartPos.toBlock(QuartPos.fromBlock(x)) + 2,
					QuartPos.toBlock(QuartPos.fromBlock(z)) + 2, share);
				counts.computeIfAbsent(biome[px], k -> new AtomicInteger()).incrementAndGet();
			}
		});

		int openColumns = 0, theirsOpen = 0, theirsClosed = 0, own = 0;
		String stray = null;
		BlockPos nearest = null;
		long nearestDistance = Long.MAX_VALUE;
		for (int px = 0; px < biome.length; px++) {
			boolean theirs = foreign(biome[px]);
			if (open[px]) {
				openColumns++;
			}
			if (theirs && open[px]) {
				theirsOpen++;
				int dx = px % MAP_SIZE - MAP_SIZE / 2, dz = px / MAP_SIZE - MAP_SIZE / 2;
				long distance = (long) dx * dx + (long) dz * dz;
				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearest = new BlockPos(x0 + px % MAP_SIZE * MAP_STRIDE, surface[px] + 40,
						z0 + px / MAP_SIZE * MAP_STRIDE - 60);
				}
			} else if (theirs) {
				theirsClosed++;
				if (stray == null) {
					stray = biome[px] + " at " + (x0 + px % MAP_SIZE * MAP_STRIDE) + ", " + (z0 + px / MAP_SIZE * MAP_STRIDE);
				}
			} else if (biome[px].startsWith("diffusicraft:")) {
				own++;
			}
		}
		Map<String, Integer> byNamespace = new TreeMap<>();
		counts.forEach((id, n) -> byNamespace.merge(id.substring(0, id.indexOf(':') + 1), n.get(), Integer::sum));
		LOGGER.info("Columns: {} open, {} of other mods' biomes in them; by namespace {}", openColumns, theirsOpen,
			byNamespace);
		Map<String, Integer> theirsById = new TreeMap<>();
		counts.forEach((id, n) -> {
			if (foreign(id)) {
				theirsById.put(id, n.get());
			}
		});
		LOGGER.info("Other mods' biomes: {}", theirsById);
		saveMap(biome, open);

		String problem = null;
		if (theirsClosed > 0) {
			problem = theirsClosed + " columns outside the open regions have other mods' biomes, such as " + stray;
		} else if (theirsOpen == 0) {
			problem = "No biome of another mod in the " + openColumns + " open columns";
		} else if (own == 0) {
			problem = "No Diffusicraft biome on the map";
		}
		return new Survey(nearest, problem);
	}

	/**
	 * Writes the height of the ground, as the generator's noise makes it, on a grid around the origin, which stays put
	 * when the mods' biomes move the spawn.
	 */
	private static void saveHeights(ServerLevel level) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState state = level.getChunkSource().randomState();
		int[] heights = IntStream.range(0, HEIGHTS_SIZE * HEIGHTS_SIZE).parallel()
			.map(i -> generator.getBaseHeight((i % HEIGHTS_SIZE - HEIGHTS_SIZE / 2) * HEIGHTS_STRIDE,
				(i / HEIGHTS_SIZE - HEIGHTS_SIZE / 2) * HEIGHTS_STRIDE, Heightmap.Types.OCEAN_FLOOR_WG, level, state))
			.toArray();
		String text = IntStream.range(0, HEIGHTS_SIZE)
			.mapToObj(row -> Arrays.stream(heights, row * HEIGHTS_SIZE, (row + 1) * HEIGHTS_SIZE)
				.mapToObj(Integer::toString).collect(Collectors.joining(" ")))
			.collect(Collectors.joining("\n", "", "\n"));
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("diffusicraft-terrain-heights.txt"), text);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LOGGER.info("Ground heights around the origin: {} to {}", Arrays.stream(heights).min().orElseThrow(),
			Arrays.stream(heights).max().orElseThrow());
	}

	private static boolean foreign(String id) {
		return !id.startsWith("minecraft:") && !id.startsWith("diffusicraft:");
	}

	/** The biome map, with the borders of the open regions in black and the biomes of other mods brightened. */
	private static void saveMap(String[] biome, boolean[] open) {
		BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
		for (int row = 0; row < MAP_SIZE; row++) {
			for (int col = 0; col < MAP_SIZE; col++) {
				int px = row * MAP_SIZE + col;
				boolean border = col > 0 && open[px] != open[px - 1] || row > 0 && open[px] != open[px - MAP_SIZE];
				int color = BiomeColors.of(biome[px]);
				image.setRGB(col, row, border ? 0 : foreign(biome[px]) ? color | 0x808080 : color);
			}
		}
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			Files.createDirectories(dir);
			ImageIO.write(image, "png", dir.resolve("diffusicraft-map-modded-biomes.png").toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
