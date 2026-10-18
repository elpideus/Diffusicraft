package io.github.elpideus.diffusicraft.worldgen.geology;

import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;
import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.DiskWindowStore;
import io.github.elpideus.diffusicraft.worldgen.PipelineSources;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures how much of a real mountain range {@link Erosion} turns into cliffs and scree at every scale:
 * <pre>
 * ./gradlew test -PwithModels --tests '*ErosionSurveyTest' -Pdiffusicraft.device=CUDA \
 *     [-Pdiffusicraft.geologySeed=42] [-Pdiffusicraft.geologySearch=8] \
 *     [-Pdiffusicraft.geologyStore=build/geology/windows] [-Pdiffusicraft.cpuThreads=4]
 * </pre>
 * For every scale it looks on the coarse map, within {@code geologySearch} tiles of the origin, for the tile whose
 * ground rises the most in blocks, and samples a grid of columns over it. That is not always the highest tile: the
 * Standard scale squeezes the land above 1,000 m, so its steepest ground lies on the flanks of the mountains. It prints
 * the share of the land above the sea that each kind of ground covers, and the slopes under them, and the same for the
 * tile with the highest ground.
 */
@Tag("models")
class ErosionSurveyTest {
	/** Columns sampled along each side of the tile. */
	private static final int SIDE = 256;

	@Test
	void survey(@TempDir Path game) throws Exception {
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "AUTO")
			.toUpperCase(Locale.ROOT));
		Resources resources = Resources.init(game, game.resolve("config"));
		resources.updateConfig(config -> {
			config.device = device;
			config.cpuThreads = Integer.getInteger("diffusicraft.cpuThreads", 0);
		});
		long seed = Long.getLong("diffusicraft.geologySeed", 42L);
		String storeDir = System.getProperty("diffusicraft.geologyStore");
		DiskWindowStore store = storeDir == null ? null : new DiskWindowStore(Path.of(storeDir));

		try (ModelBundle models = ModelBundle.load(resources.models(), resources.modelSettings())) {
			WorldPipeline pipeline = new WorldPipeline(seed, models, store, WorldPipeline.CacheLimits.defaults());
			TerrainField field = new TerrainField(seed, PipelineSources.of(seed, pipeline));
			int radius = Integer.getInteger("diffusicraft.geologySearch", 8);
			Erosion erosion = new Erosion(seed);
			int[] highest = highest(field, radius);
			Map<WorldScale, double[]> shares = new EnumMap<>(WorldScale.class);
			for (WorldScale scale : WorldScale.values()) {
				shares.put(scale, measure(field, erosion, scale, steepest(field, scale, radius), "steepest"));
				measure(field, erosion, scale, highest, "highest");
			}
			for (WorldScale scale : WorldScale.values()) {
				double[] share = shares.get(scale);
				assertTrue(share[Erosion.Kind.SOIL.ordinal()] > 0.5, scale + ": most of the land keeps its soil");
				assertTrue(share[Erosion.Kind.CLIFF.ordinal()] > 0, scale + ": cliffs in the mountains");
				assertTrue(share[Erosion.Kind.SCREE.ordinal()] > 0, scale + ": scree in the mountains");
			}
		}
	}

	/** Prints and returns the share of each kind of ground over the land of the tile centred on {@code tile}. */
	private static double[] measure(TerrainField field, Erosion erosion, WorldScale scale, int[] tile, String name) {
		double perBlock = scale.pixelsPerBlock();
		Erosion.Surface ground = (x, z) -> scale.surfaceY(field.elevation(z * perBlock, x * perBlock));
		double blocks = TerrainField.TILE / perBlock;
		double x0 = (tile[1] - TerrainField.TILE / 2.0) / perBlock;
		double z0 = (tile[0] - TerrainField.TILE / 2.0) / perBlock;
		int[] count = new int[Erosion.Kind.values().length];
		double[] slope = new double[count.length];
		int land = 0;
		long start = System.nanoTime();
		for (int a = 0; a < SIDE; a++) {
			for (int b = 0; b < SIDE; b++) {
				int x = (int) Math.floor(x0 + (b + 0.5) * blocks / SIDE);
				int z = (int) Math.floor(z0 + (a + 0.5) * blocks / SIDE);
				double cx = x + 0.5, cz = z + 0.5;
				if (ground.y(cx, cz) <= scale.seaLevel()) {
					continue;
				}
				Erosion.Kind kind = erosion.kind(ground, x, z);
				double gx = (ground.y(cx + 1, cz) - ground.y(cx - 1, cz)) / 2;
				double gz = (ground.y(cx, cz + 1) - ground.y(cx, cz - 1)) / 2;
				count[kind.ordinal()]++;
				slope[kind.ordinal()] += Math.sqrt(gx * gx + gz * gz);
				land++;
			}
		}
		double[] share = new double[count.length];
		StringBuilder line = new StringBuilder();
		for (Erosion.Kind kind : Erosion.Kind.values()) {
			int k = kind.ordinal();
			share[k] = count[k] / (double) Math.max(1, land);
			line.append(String.format(" %s %.1f%% (mean slope %.2f)", kind, 100 * share[k],
				slope[k] / Math.max(1, count[k])));
		}
		System.out.printf("%-8s %-8s tile at pixel (%d, %d), %.0f blocks across, %d land columns in %.1f s:%s%n",
			scale.getSerializedName(), name, tile[0], tile[1], blocks, land, (System.nanoTime() - start) / 1e9, line);
		return share;
	}

	/** The centre, in native pixels, of the tile within {@code radius} tiles of the origin with the highest ground. */
	private static int[] highest(TerrainField field, int radius) {
		int perTile = 4;
		double step = TerrainField.TILE / (double) perTile;
		float best = Float.NEGATIVE_INFINITY;
		int[] centre = new int[2];
		for (int ti = -radius; ti <= radius; ti++) {
			for (int tj = -radius; tj <= radius; tj++) {
				for (int a = 0; a < perTile; a++) {
					for (int b = 0; b < perTile; b++) {
						float e = field.coarseElevation((ti - 0.5) * TerrainField.TILE + (a + 0.5) * step,
							(tj - 0.5) * TerrainField.TILE + (b + 0.5) * step);
						if (e > best) {
							best = e;
							centre[0] = ti * TerrainField.TILE;
							centre[1] = tj * TerrainField.TILE;
						}
					}
				}
			}
		}
		return centre;
	}

	/**
	 * The centre, in native pixels, of the tile within {@code radius} tiles of the origin whose ground rises the most
	 * at {@code scale}, in blocks.
	 */
	private static int[] steepest(TerrainField field, WorldScale scale, int radius) {
		int perTile = 4;
		double step = TerrainField.TILE / (double) perTile;
		double best = Double.NEGATIVE_INFINITY;
		int[] centre = new int[2];
		for (int ti = -radius; ti <= radius; ti++) {
			for (int tj = -radius; tj <= radius; tj++) {
				double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
				for (int a = 0; a < perTile; a++) {
					for (int b = 0; b < perTile; b++) {
						double y = scale.surfaceY(field.coarseElevation(
							(ti - 0.5) * TerrainField.TILE + (a + 0.5) * step,
							(tj - 0.5) * TerrainField.TILE + (b + 0.5) * step));
						low = Math.min(low, y);
						high = Math.max(high, y);
					}
				}
				if (high - low > best) {
					best = high - low;
					centre[0] = ti * TerrainField.TILE;
					centre[1] = tj * TerrainField.TILE;
				}
			}
		}
		return centre;
	}
}
