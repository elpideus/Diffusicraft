package io.github.elpideus.diffusicraft.terrain.diffusion;

import io.github.elpideus.diffusicraft.runtime.NativeRuntime;
import io.github.elpideus.diffusicraft.runtime.Resources;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real pipeline and prints per-stage timings. Uses the models and runtimes installed in the shared storage
 * directory (download them in game, or with {@code RuntimeInstallTest}), like the game does:
 * <pre>
 * ./gradlew test -PwithModels --tests '*WorldPipelineBenchmarkTest' [-Pdiffusicraft.device=CPU|CUDA|ROCM] \
 *     [-Pdiffusicraft.modelDir=/path/to/models] [-Ponnxruntime.native.path=/path/to/natives]
 * </pre>
 */
@Tag("models")
class WorldPipelineBenchmarkTest {
	@Test
	void sampleRegions(@TempDir Path game) throws Exception {
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "AUTO").toUpperCase(Locale.ROOT));
		// Lets NativeRuntime pick the installed runtime for the device, exactly as in game.
		Resources resources = Resources.init(game, game.resolve("config"));
		resources.updateConfig(config -> {
			config.device = device;
			config.gpuMemoryLimitMb = Integer.getInteger("diffusicraft.gpuMemoryLimitMb", 0);
		});

		String dir = System.getProperty("diffusicraft.modelDir");
		ModelAssets assets = dir == null || dir.isBlank() ? resources.models() : new ModelAssets(ModelAssets.Manifest.builtin(), Path.of(dir));
		assertTrue(assets.invalidFiles().isEmpty(), "model files missing or corrupt in " + assets.directory());

		OnnxModel.Settings settings = resources.modelSettings();

		long t0 = System.nanoTime();
		System.out.println("runtime: " + NativeRuntime.activate());
		try (ModelBundle models = ModelBundle.load(assets, settings)) {
			System.out.printf("load: %.1f s  providers: coarse=%s base=%s decoder=%s  batchable: %b/%b/%b%n",
				(System.nanoTime() - t0) / 1e9, models.coarse().provider(), models.base().provider(), models.decoder().provider(),
				models.coarse().batchable(), models.base().batchable(), models.decoder().batchable());

			WorldPipeline pipeline = new WorldPipeline(42L, models, null, WorldPipeline.CacheLimits.defaults());
			int size = Integer.getInteger("diffusicraft.benchSize", 512);
			for (int k = 0; k < 3; k++) {
				long start = System.nanoTime();
				long w0 = pipeline.computedWindows();
				TerrainSample sample = pipeline.sample(0, k * size, size, (k + 1) * size, true);
				double seconds = (System.nanoTime() - start) / 1e9;
				float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
				for (float v : sample.elevation()) {
					assertTrue(Float.isFinite(v), "non-finite elevation");
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
				System.out.printf("region %d (%dx%d px = %.1f km²): %.2f s, %d windows, elevation %.0f..%.0f m, temp(0,0)=%.1f°C%n",
					k, size, size, size * size * 0.0009, seconds, pipeline.computedWindows() - w0, min, max,
					sample.climate(TerrainSample.TEMPERATURE, 0, k * size));
				assertTrue(min > -12000 && max < 10000, "implausible elevation range");
			}
			for (OnnxModel model : new OnnxModel[]{models.coarse(), models.base(), models.decoder()}) {
				System.out.printf("%-8s %5d runs  %8.1f ms total  %7.2f ms/run%n", model.name(), model.runs(),
					model.runNanos() / 1e6, model.runs() == 0 ? 0 : model.runNanos() / 1e6 / model.runs());
			}
		}
	}
}
