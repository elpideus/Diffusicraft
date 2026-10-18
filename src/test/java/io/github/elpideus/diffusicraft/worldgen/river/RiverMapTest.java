package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelBundle;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.terrain.diffusion.WorldPipeline;
import io.github.elpideus.diffusicraft.worldgen.PipelineSources;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surveys the river network of a real world and draws maps of its rivers, to look at them without starting the game:
 * <pre>
 * ./gradlew test -PwithModels --tests '*RiverMapTest' -Pdiffusicraft.device=CUDA \
 *     [-Pdiffusicraft.riverSeed=42] [-Pdiffusicraft.riverTiles=8] [-Pdiffusicraft.riverMapDir=build/river-map]
 * </pre>
 * Each map covers {@code riverTiles}² cells at one pixel per native pixel (30 m). Rivers are blue and lakes teal,
 * carved banks are brown, and ground built up under a river that runs above the terrain is red.
 */
@Tag("models")
class RiverMapTest {
	private static final WorldScale SCALE = WorldScale.STANDARD;

	@Test
	void survey(@TempDir Path game) throws Exception {
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "AUTO")
			.toUpperCase(Locale.ROOT));
		Resources resources = Resources.init(game, game.resolve("config"));
		resources.updateConfig(config -> config.device = device);
		Path out = Path.of(System.getProperty("diffusicraft.riverMapDir", "build/river-map"));
		Files.createDirectories(out);
		long seed = Long.getLong("diffusicraft.riverSeed", 42L);
		int tiles = Integer.getInteger("diffusicraft.riverTiles", 8);
		int radius = Integer.getInteger("diffusicraft.riverSurvey", 48);

		try (ModelBundle models = ModelBundle.load(resources.models(), resources.modelSettings())) {
			WorldPipeline pipeline = new WorldPipeline(seed, models, null, WorldPipeline.CacheLimits.defaults());
			TerrainField field = new TerrainField(seed, PipelineSources.of(seed, pipeline));
			RiverNetwork network = new RiverNetwork(field);

			long start = System.nanoTime();
			int land = 0, rivers = 0, pits = 0;
			int bigI = 0, bigJ = 0, midI = 0, midJ = 0;
			float big = 0f, mid = Float.POSITIVE_INFINITY;
			for (int ci = -radius; ci < radius; ci++) {
				for (int cj = -radius; cj < radius; cj++) {
					int direction = network.direction(ci, cj);
					if (direction == RiverNetwork.OCEAN) {
						continue;
					}
					land++;
					pits += direction == RiverNetwork.PIT ? 1 : 0;
					float q = network.discharge(ci, cj);
					if (q >= RiverNetwork.MIN_DISCHARGE) {
						rivers++;
					}
					// Keep away from the edge so the maps stay inside the survey.
					boolean inside = Math.abs(ci) < radius - tiles && Math.abs(cj) < radius - tiles;
					if (inside && q > big) {
						big = q;
						bigI = ci;
						bigJ = cj;
					}
					if (inside && Math.abs(q - 60f) < Math.abs(mid - 60f)) {
						mid = q;
						midI = ci;
						midJ = cj;
					}
				}
			}
			System.out.printf("survey of %d² cells: %.1f s, %d land, %d with rivers, %d pits%n", 2 * radius,
				(System.nanoTime() - start) / 1e9, land, rivers, pits);
			System.out.printf("largest river %.0f m³/s at cell (%d, %d); one of %.0f m³/s at (%d, %d)%n", big, bigI,
				bigJ, mid, midI, midJ);
			assertTrue(rivers > 0, "a world has rivers");

			Rivers riverQuery = Rivers.of(field);
			render(field, riverQuery, bigI, bigJ, tiles, out.resolve("rivers-large.png"));
			render(field, riverQuery, midI, midJ, tiles, out.resolve("rivers-medium.png"));
		}
	}

	private static void render(TerrainField field, Rivers rivers, int centreI, int centreJ, int tiles, Path file)
		throws Exception {
		int ti0 = centreI - tiles / 2, tj0 = centreJ - tiles / 2, size = tiles * TerrainField.TILE;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		long start = System.nanoTime();
		int channel = 0, perched = 0, perchedBlocks = 0, lake = 0;
		int blocksPerPixel = (int) Math.round(1.0 / SCALE.pixelsPerBlock());
		for (int a = 0; a < size; a++) {
			for (int b = 0; b < size; b++) {
				int i = ti0 * TerrainField.TILE + a, j = tj0 * TerrainField.TILE + b;
				float e = field.elevation(i + 0.5, j + 0.5);
				float east = field.elevation(i + 0.5, j + 1.5), south = field.elevation(i + 1.5, j + 0.5);
				int rgb = ground(e, east, south);
				RiverColumn c = rivers.column(SCALE, j * blocksPerPixel, i * blocksPerPixel);
				if (c.river) {
					float terrain = SCALE.surfaceY(e);
					if (c.lake) {
						lake++;
						rgb = c.waterTop - c.bed >= 3 ? 0x17766c : 0x2aa198;
					} else if (c.channel) {
						channel++;
						float above = c.waterTop + 1 - terrain;
						if (above > 1f) {
							perched++;
							perchedBlocks += Math.round(above);
						}
						rgb = above > 1f ? 0x6a3cc8 : c.waterTop - c.bed >= 3 ? 0x1e4fbe : 0x3b82e6;
					} else if (c.surface > terrain + 0.5f) {
						rgb = mix(rgb, 0xc83232, 0.6f);
					} else if (c.surface < terrain - 0.5f) {
						rgb = mix(rgb, 0x8b6a3e, 0.5f);
					}
				}
				image.setRGB(b, a, rgb);
			}
		}
		for (int k = 0; k < size; k += TerrainField.TILE) {
			for (int t = 0; t < size; t += 2) {
				image.setRGB(t, k, mix(image.getRGB(t, k), 0xffffff, 0.35f));
				image.setRGB(k, t, mix(image.getRGB(k, t), 0xffffff, 0.35f));
			}
		}
		ImageIO.write(image, "png", file.toFile());
		System.out.printf("%s: cells (%d, %d) to (%d, %d) in %.1f s; %d channel pixels, %d above the ground by %.1f "
				+ "blocks on average; %d lake pixels%n", file.getFileName(), ti0, tj0, ti0 + tiles - 1, tj0 + tiles - 1,
			(System.nanoTime() - start) / 1e9, channel, perched, perched == 0 ? 0f : perchedBlocks / (float) perched,
			lake);
	}

	/** Hypsometric tint with a hillshade from the north-west. */
	private static int ground(float e, float east, float south) {
		if (e < 0f) {
			float depth = Math.min(1f, -e / 2000f);
			return rgb(30 - 15 * depth, 70 - 40 * depth, 140 - 50 * depth);
		}
		float shade = Math.clamp(0.75f + ((e - east) + (e - south)) / 120f, 0.35f, 1.15f);
		float t = Math.min(1f, e / 3000f);
		float r = 110 + 110 * t, g = 150 + 40 * t - 60 * t * t, bl = 90 + 90 * t;
		return rgb(r * shade, g * shade, bl * shade);
	}

	private static int rgb(float r, float g, float b) {
		return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(float v) {
		return Math.clamp(Math.round(v), 0, 255);
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
