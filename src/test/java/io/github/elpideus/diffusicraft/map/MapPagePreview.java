package io.github.elpideus.diffusicraft.map;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Serves the relief map of a synthetic level, the size of an Ultra world, for working on the page in a browser
 * without the game. It writes the link to the file that {@code diffusicraft.mapPreview} names and serves until a file
 * of the same name with {@code .stop} appended appears, or for 25 minutes. See docs/MAP.md.
 */
@EnabledIfSystemProperty(named = "diffusicraft.mapPreview", matches = ".+")
class MapPagePreview {
	/** Value noise for heights, with plains, peaks, sea and patches of cloud forest; fine tiles take 350 ms. */
	static final class NoiseLevel implements MapLevel {
		@Override
		public String id() {
			return "minecraft:overworld";
		}

		@Override
		public int fineZoom() {
			return 5;
		}

		@Override
		public int maxZoom() {
			return 11;
		}

		@Override
		public int minY() {
			return -64;
		}

		@Override
		public int maxY() {
			return 9087;
		}

		@Override
		public int seaLevel() {
			return 63;
		}

		@Override
		public double metersPerBlock() {
			return 1;
		}

		@Override
		public int border() {
			return 8_000_000 - 16;
		}

		@Override
		public List<String> biomes() {
			return List.of("minecraft:plains", "minecraft:jagged_peaks", "minecraft:ocean", "diffusicraft:cloud_forest");
		}

		@Override
		public List<String> biomeNames() {
			return List.of("Plains", "Jagged Peaks", "Ocean", "Cloud Forest");
		}

		private static double hash(long x, long z) {
			long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
			h ^= h >>> 31;
			h *= 0xBF58476D1CE4E5B9L;
			h ^= h >>> 29;
			return (h & 0xffffff) / (double) 0xffffff;
		}

		private static double noise(double x, double z) {
			long x0 = (long) Math.floor(x), z0 = (long) Math.floor(z);
			double fx = x - x0, fz = z - z0;
			fx = fx * fx * (3 - 2 * fx);
			fz = fz * fz * (3 - 2 * fz);
			double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
			return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
		}

		static double height(double x, double z, boolean coarse) {
			double h = -900 + 2600 * noise(x / 60000, z / 60000) + 1400 * noise(x / 15000 + 7, z / 15000)
				+ 500 * noise(x / 4000 + 3, z / 4000);
			if (!coarse) {
				h += 140 * noise(x / 700, z / 700 + 5) + 30 * noise(x / 90, z / 90) + 6 * noise(x / 16, z / 16);
			}
			return 63 + h * 0.6;
		}

		@Override
		public MapRaster render(int zoom, int tx, int ty, boolean coarse) {
			try {
				Thread.sleep(coarse ? 15 : 350);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			MapRaster tile = new MapRaster();
			double step = Math.pow(2, zoom);
			for (int py = 0; py < MapRaster.SIZE; py++) {
				for (int px = 0; px < MapRaster.SIZE; px++) {
					double bx = (tx * 256.0 + px + 0.5) * step, bz = (ty * 256.0 + py + 0.5) * step;
					double h = height(bx, bz, coarse);
					double e = height(bx + step, bz, coarse), s = height(bx, bz + step, coarse);
					int k = py * 256 + px;
					int ground = (int) Math.floor(h);
					int water = Math.max(0, Math.min(255, 63 - ground));
					tile.ground[k] = (short) Math.max(-64, ground);
					tile.water[k] = (byte) water;
					int rgb;
					if (water > 0) {
						tile.biome[k] = 2;
						rgb = water > 30 ? 0x1D3F74 : 0x4F86C0;
					} else if (h > 1600) {
						tile.biome[k] = 1;
						rgb = h > 2400 ? 0xF4F6F8 : 0x8A8A8A;
					} else {
						tile.biome[k] = (byte) (noise(bx / 3000, bz / 3000) > 0.7 ? 3 : 0);
						rgb = tile.biome[k] == 3 ? 0x2F6B3A : 0x7DB04B;
					}
					double light = 1 + Math.max(-0.6, Math.min(0.4, ((h - e) + (h - s)) / (2 * step) * 0.8));
					int r = (int) Math.min(255, (rgb >> 16 & 255) * light), g = (int) Math.min(255, (rgb >> 8 & 255) * light),
						b = (int) Math.min(255, (rgb & 255) * light);
					tile.rgb[k] = r << 16 | g << 8 | b;
				}
			}
			tile.flags = coarse ? 0 : MapRaster.DETAIL;
			return tile;
		}

		@Override
		public @Nullable Point point(int x, int z) {
			if (Math.abs(x) > border() || Math.abs(z) > border()) {
				return null;
			}
			int ground = (int) Math.floor(height(x + 0.5, z + 0.5, false));
			return new Point(x, z, ground, Math.max(0, 63 - ground), ground < 63 ? 2 : ground > 1600 ? 1 : 0);
		}

		@Override
		public List<Point> search(int biome, int x, int z, long deadline) {
			List<Point> found = new ArrayList<>();
			int step = 2048;
			for (int ring = 0; ring < 200 && found.size() < 5 && System.nanoTime() < deadline; ring++) {
				for (int i = -ring; i <= ring; i++) {
					for (int j = -ring; j <= ring; j++) {
						if (Math.max(Math.abs(i), Math.abs(j)) != ring) {
							continue;
						}
						Point p = point(x + i * step, z + j * step);
						if (p != null && p.biome() == biome && found.size() < 5) {
							found.add(p);
						}
					}
				}
			}
			return found;
		}
	}

	@Test
	void serve() throws Exception {
		LifoExecutor fine = new LifoExecutor("preview fine", 2), fast = new LifoExecutor("preview fast", 2);
		MapTiles tiles = new MapTiles(new NoiseLevel(), null, fine, fast);
		UUID player = new UUID(7, 7);
		long start = System.currentTimeMillis();
		MapService service = new MapService(List.of(tiles), new MapService.Game() {
			@Override
			public MapService.Position player(@Nullable UUID owner) {
				double t = (System.currentTimeMillis() - start) / 1000.0;
				return new MapService.Position("minecraft:overworld", 1200 + 40 * t, 200, -800, (float) (t * 20));
			}

			@Override
			public MapService.Position spawn() {
				return new MapService.Position("minecraft:overworld", 0.5, 64, 0.5, 0);
			}

			@Override
			public String name(@Nullable UUID owner) {
				return "Preview";
			}
		}, fine, InetAddress.getLoopbackAddress(), 0);
		Path out = Path.of(System.getProperty("diffusicraft.mapPreview"));
		Files.writeString(out, "http://127.0.0.1:" + service.port() + "/" + service.token(player) + "/\n");
		Path stop = out.resolveSibling(out.getFileName() + ".stop");
		long end = System.currentTimeMillis() + 25 * 60_000;
		while (!Files.exists(stop) && System.currentTimeMillis() < end) {
			Thread.sleep(500);
		}
		service.close();
		fine.close();
		fast.close();
	}
}
