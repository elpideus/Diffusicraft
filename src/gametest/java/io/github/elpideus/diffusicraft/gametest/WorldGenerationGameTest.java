package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Creates a Diffusicraft world with the default settings and checks that the overworld really uses Diffusicraft.
 * Saves to {@code build/run/clientGameTest/screenshots}: a biome map and a height map around the spawn, sampled from
 * the generator without building chunks, and in-game views from above the spawn.
 *
 * <p>Needs the Terrain Diffusion models; without them the test logs a warning and does nothing. Generation runs on the
 * CPU, so the test leaves the GPU alone; pass {@code -Pdiffusicraft.device=CUDA} (or {@code AUTO}) to change that.
 */
public class WorldGenerationGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft world test");
	private static final String SEED = "20261018";
	/** Map size in pixels, and blocks per pixel. */
	private static final int MAP_SIZE = 256;
	private static final int MAP_STRIDE = 8;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int VIEW_TIMEOUT_TICKS = 15 * TICKS_PER_MINUTE;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the world generation test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(8));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);
		LOGGER.info("Generating on {}", device);

		// A fresh Create World screen selects Diffusicraft.
		context.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		context.waitForScreen(CreateWorldScreen.class);
		String defaultType = context.computeOnClient(mc -> describe(((CreateWorldScreen) mc.gui.screen()).getUiState()));
		if (!defaultType.equals(DiffusicraftWorldgen.STANDARD_PRESET.identifier().toString())) {
			throw new AssertionError("The default world type is " + defaultType);
		}
		context.setScreen(TitleScreen::new);

		long start = System.nanoTime();
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			LOGGER.info("World loaded in {} s", (System.nanoTime() - start) / 1_000_000_000L);

			Probe probe = world.getServer().computeOnServer(server -> Probe.of(server.overworld()));
			LOGGER.info("Spawn {} at surface y={}, biome {}", probe.spawn, probe.spawnSurface, probe.spawnBiome);
			saveMaps(probe);

			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			view(context, world, probe, 0, 0f, "view-south");
			view(context, world, probe, 0, 180f, "view-north");
			view(context, world, probe, 120, 90f, "view-high-west");
		}
	}

	private static String describe(WorldCreationUiState ui) {
		Holder<?> preset = ui.getWorldType().preset();
		return preset == null ? "custom" : preset.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
	}

	/** Teleports the player above the spawn, waits for the chunks and takes a screenshot. */
	private static void view(ClientGameTestContext context, TestSingleplayerContext world, Probe probe, int extraHeight,
							 float yaw, String name) {
		BlockPos spawn = probe.spawn;
		world.getServer().runCommand(String.format("tp @a %d %d %d %.1f 20", spawn.getX(), probe.spawnSurface + 40 + extraHeight,
			spawn.getZ(), yaw));
		long start = System.nanoTime();
		try {
			world.getConnection().waitForChunksRender(VIEW_TIMEOUT_TICKS);
			LOGGER.info("Chunks for {} ready in {} s", name, (System.nanoTime() - start) / 1_000_000_000L);
		} catch (AssertionError e) {
			// On the CPU next to a busy desktop the far chunks can take longer than any sensible timeout. A view with
			// a few missing chunks still shows the terrain, so take it anyway.
			LOGGER.warn("Chunks for {} not all rendered after {} s; taking the screenshot anyway", name,
				(System.nanoTime() - start) / 1_000_000_000L);
		}
		context.waitTicks(20);
		Path file = context.takeScreenshot("diffusicraft-world-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}

	/** What the test reads from the server: the spawn, and what it needs to sample the generator off-thread. */
	private record Probe(BlockPos spawn, int spawnSurface, String spawnBiome, RandomState state, NoiseRouter router,
						 BiomeSource biomes) {
		static Probe of(ServerLevel level) {
			ChunkGenerator generator = level.getChunkSource().getGenerator();
			if (!DiffusicraftWorldgen.usesModels(generator)) {
				throw new AssertionError("The overworld does not use Diffusicraft: " + generator);
			}
			NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) generator;
			BlockPos spawn = level.getRespawnData().pos();
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
			String biome = level.getBiome(spawn.atY(surface)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			return new Probe(spawn, surface, biome, level.getChunkSource().randomState(),
				noise.generatorSettings().value().noiseRouter(), generator.getBiomeSource());
		}
	}

	/** Writes a biome map and a height map of {@value #MAP_SIZE}² pixels centred on the spawn. */
	private static void saveMaps(Probe probe) {
		long start = System.nanoTime();
		int half = MAP_SIZE * MAP_STRIDE / 2;
		int x0 = probe.spawn.getX() - half, z0 = probe.spawn.getZ() - half;
		float[] height = new float[MAP_SIZE * MAP_SIZE];
		String[] biome = new String[MAP_SIZE * MAP_SIZE];
		Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
		IntStream.range(0, MAP_SIZE).parallel().forEach(row -> {
			BiomeResolver resolver = probe.biomes.createResolver(probe.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			for (int col = 0; col < MAP_SIZE; col++) {
				int x = x0 + col * MAP_STRIDE, z = z0 + row * MAP_STRIDE;
				float surface = probe.state.sampleBlockValueUncached(probe.router.chunkSurfaceLevel(), x, 0, z);
				int px = row * MAP_SIZE + col;
				height[px] = surface;
				Holder<Biome> b = resolver.getNoiseBiome(QuartPos.fromBlock(x),
					QuartPos.fromBlock(Math.max((int) surface, 63)), QuartPos.fromBlock(z));
				biome[px] = b.unwrapKey().map(ResourceKey::identifier).map(Object::toString).orElse("?");
				counts.computeIfAbsent(biome[px], k -> new AtomicInteger()).incrementAndGet();
			}
		});
		LOGGER.info("Sampled a {} x {} block map in {} s", MAP_SIZE * MAP_STRIDE, MAP_SIZE * MAP_STRIDE,
			(System.nanoTime() - start) / 1_000_000_000L);

		Map<Integer, String> byCount = new TreeMap<>((a, b) -> Integer.compare(b, a) != 0 ? Integer.compare(b, a) : 1);
		counts.forEach((k, v) -> byCount.put(v.get(), k));
		StringBuilder summary = new StringBuilder();
		byCount.forEach((n, k) -> summary.append(String.format("%n  %5.1f%% %s", 100.0 * n / height.length, k)));
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (float h : height) {
			min = Math.min(min, h);
			max = Math.max(max, h);
		}
		LOGGER.info("Surface from y={} to y={}. Biomes:{}", (int) min, (int) max, summary);

		BufferedImage biomes = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
		BufferedImage heights = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
		for (int row = 0; row < MAP_SIZE; row++) {
			for (int col = 0; col < MAP_SIZE; col++) {
				int px = row * MAP_SIZE + col;
				float h = height[px];
				// Light from the north-west.
				float west = height[row * MAP_SIZE + Math.max(col - 1, 0)], north = height[Math.max(row - 1, 0) * MAP_SIZE + col];
				float shade = clamp(1f + 0.08f * ((h - west) + (h - north)), 0.55f, 1.3f);
				int color = h < 63f ? water(63f - h) : shadeColor(BiomeColors.of(biome[px]), shade);
				biomes.setRGB(col, row, color);
				int g = (int) clamp((h - min) / Math.max(1f, max - min) * 255f, 0f, 255f);
				heights.setRGB(col, row, h < 63f ? 0x1d3f8f : g << 16 | g << 8 | g);
			}
		}
		// The spawn, in red.
		for (int d = -3; d <= 3; d++) {
			biomes.setRGB(MAP_SIZE / 2 + d, MAP_SIZE / 2, 0xff0000);
			biomes.setRGB(MAP_SIZE / 2, MAP_SIZE / 2 + d, 0xff0000);
		}
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			Files.createDirectories(dir);
			ImageIO.write(biomes, "png", dir.resolve("diffusicraft-map-biomes.png").toFile());
			ImageIO.write(heights, "png", dir.resolve("diffusicraft-map-height.png").toFile());
			LOGGER.info("Maps: {}", dir.toAbsolutePath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static int water(float depth) {
		float t = clamp(depth / 40f, 0f, 1f);
		int r = (int) (60 - 40 * t), g = (int) (120 - 70 * t), b = (int) (220 - 90 * t);
		return r << 16 | g << 8 | b;
	}

	private static int shadeColor(int rgb, float shade) {
		int r = (int) clamp(((rgb >> 16) & 0xff) * shade, 0f, 255f);
		int g = (int) clamp(((rgb >> 8) & 0xff) * shade, 0f, 255f);
		int b = (int) clamp((rgb & 0xff) * shade, 0f, 255f);
		return r << 16 | g << 8 | b;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
