package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.runtime.NativeRuntime;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelAssets;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flies east over fresh terrain in real time and prints how long chunk generation waited for inference, without and
 * with a {@link TerrainPrefetcher}. Uses the installed models, like {@code WorldPipelineBenchmarkTest}:
 * <pre>
 * ./gradlew test -PwithModels --tests '*TerrainPrefetchBenchmarkTest' [-Pdiffusicraft.device=CPU|CUDA|ROCM] \
 *     [-Pdiffusicraft.flightTiles=3] [-Pdiffusicraft.flightSpeed=33] [-Pdiffusicraft.viewDistance=12]
 * </pre>
 */
@Tag("models")
class TerrainPrefetchBenchmarkTest {
	private static final long SEED = 42L;
	private static final WorldScale SCALE = WorldScale.STANDARD;

	/**
	 * @param waitSeconds    time chunk generation spent waiting for tiles
	 * @param longestStall   the longest that chunks stopped appearing, in seconds
	 * @param computedTiles  tiles computed during the flight, by either thread
	 * @param prefetchedTiles of those, the tiles the prefetcher computed
	 */
	private record Flight(double seconds, double waitSeconds, double longestStall, long computedTiles,
						  long prefetchedTiles) {
	}

	@Test
	void flyOverFreshTerrain(@TempDir Path game) throws Exception {
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "AUTO").toUpperCase(Locale.ROOT));
		Resources resources = Resources.init(game, game.resolve("config"));
		resources.updateConfig(config -> {
			config.device = device;
			config.gpuMemoryLimitMb = Integer.getInteger("diffusicraft.gpuMemoryLimitMb", 0);
		});
		ModelAssets assets = resources.models();
		assertTrue(assets.invalidFiles().isEmpty(), "model files missing or corrupt in " + assets.directory());
		int tiles = Integer.getInteger("diffusicraft.flightTiles", 3);
		double speed = Double.parseDouble(System.getProperty("diffusicraft.flightSpeed", "33"));
		int view = Integer.getInteger("diffusicraft.viewDistance", 12);

		System.out.println("runtime: " + NativeRuntime.activate());
		try (ModelBundle models = ModelBundle.load(assets, resources.modelSettings())) {
			System.out.printf("flight: %d tiles of %.0f blocks east at %.0f blocks/s, view distance %d%n", tiles,
				TerrainField.TILE / SCALE.pixelsPerBlock(), speed, view);
			for (boolean prefetch : new boolean[]{false, true}) {
				// A fresh pipeline each time, so that the second flight computes everything again.
				WorldPipeline pipeline = new WorldPipeline(SEED, models, null, WorldPipeline.CacheLimits.defaults());
				TerrainField field = new TerrainField(SEED, new PipelineSource(SEED, pipeline));
				Flight flight = fly(field, prefetch, tiles, speed, view);
				System.out.printf("%-14s %.1f s flight, waited %.1f s (%.0f%%), longest stall %.1f s, "
						+ "%d tiles computed, %d of them ahead%n", prefetch ? "prefetching:" : "on demand:",
					flight.seconds(), flight.waitSeconds(), 100 * flight.waitSeconds() / flight.seconds(),
					flight.longestStall(), flight.computedTiles(), flight.prefetchedTiles());
			}
		}
	}

	private static Flight fly(TerrainField field, boolean prefetch, int tiles, double speed, int view)
		throws InterruptedException {
		double tileBlocks = TerrainField.TILE / SCALE.pixelsPerBlock();
		// What chunk generation covers around the player, as PlayerPrefetch reckons it.
		double reach = (view + 1) * 16 + 64;
		double z = tileBlocks / 2;
		long start = System.nanoTime();
		// The spawn area is generated before the player takes off.
		for (int ti = TerrainField.tileOf(pixel(z - reach)); ti <= TerrainField.tileOf(pixel(z + reach)); ti++) {
			for (int tj = TerrainField.tileOf(pixel(-reach)); tj <= TerrainField.tileOf(pixel(reach)); tj++) {
				field.prefetch(ti, tj);
			}
		}
		long spawnTiles = field.computedTiles();
		System.out.printf("spawn: %d tiles in %.1f s%n", spawnTiles, (System.nanoTime() - start) / 1e9);

		ScheduledExecutorService server = Executors.newSingleThreadScheduledExecutor();
		try (TerrainPrefetcher prefetcher = new TerrainPrefetcher()) {
			long takeoff = System.nanoTime();
			if (prefetch) {
				// The server thread plans every 10 ticks, also while chunk generation waits.
				server.scheduleAtFixedRate(() -> {
					double x = speed * (System.nanoTime() - takeoff) / 1e9;
					prefetcher.update(List.of(new TerrainPrefetcher.Traveller(field, SCALE, x, z, speed, 0, reach)));
				}, 0, 500, TimeUnit.MILLISECONDS);
			}
			long longest = 0;
			while (true) {
				double x = speed * (System.nanoTime() - takeoff) / 1e9;
				if (x > tiles * tileBlocks) {
					break;
				}
				// Chunk generation: the chunks coming into view along the leading edge, across its whole width.
				long t0 = System.nanoTime();
				for (double dz = -reach; dz <= reach; dz += reach) {
					field.elevation(pixel(z + dz), pixel(x + reach));
				}
				longest = Math.max(longest, System.nanoTime() - t0);
				Thread.sleep(50);
			}
			server.shutdownNow();
			return new Flight((System.nanoTime() - takeoff) / 1e9, field.tileWaitNanos() / 1e9, longest / 1e9,
				field.computedTiles() - spawnTiles, prefetcher.prefetchedTiles());
		} finally {
			server.shutdownNow();
			server.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private static double pixel(double block) {
		return SCALE.toPixel((int) Math.floor(block));
	}
}
