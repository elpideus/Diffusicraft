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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surveys the rock under a real world at every scale and draws a cross-section of each, to look at the layers without
 * starting the game:
 * <pre>
 * ./gradlew test -PwithModels --tests '*GeologySurveyTest' -Pdiffusicraft.device=CUDA \
 *     [-Pdiffusicraft.geologySeed=42] [-Pdiffusicraft.geologyTiles=4] [-Pdiffusicraft.geologySearch=16] \
 *     [-Pdiffusicraft.geologyDir=build/geology] [-Pdiffusicraft.geologyStore=build/geology/windows] \
 *     [-Pdiffusicraft.cpuThreads=4]
 * </pre>
 * It looks on the coarse map, within {@code geologySearch} tiles of the origin, for the {@code geologyTiles}² tiles
 * with the most lowland, where the sediment is thickest. It samples that land at every scale and prints how far the
 * sedimentary cover reaches and how much of the land keeps a basement under it. Each section runs one tile long
 * through the deepest basin on land, at the same place at every scale, from the bottom of the world to the highest
 * ground on it.
 * Sections are drawn ten pixels per tile pixel across and one pixel per block up, or fewer if the world is taller than
 * the image. A {@code geologyStore} keeps the terrain on disk, a folder per seed, so that later runs need no inference.
 */
@Tag("models")
class GeologySurveyTest {
	private static final long GEOLOGY_SEED = 0x6E01_0617L;
	private static final Map<WorldScale, Integer> MIN_Y = new EnumMap<>(Map.of(
		WorldScale.STANDARD, -64, WorldScale.GRAND, -256, WorldScale.EARTH, -256));
	private static final int STEP = 4;
	private static final int SECTION_WIDTH = TerrainField.TILE * 3;
	private static final int SECTION_HEIGHT = 1200;

	@Test
	void survey(@TempDir Path game) throws Exception {
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "AUTO")
			.toUpperCase(Locale.ROOT));
		Resources resources = Resources.init(game, game.resolve("config"));
		resources.updateConfig(config -> {
			config.device = device;
			config.cpuThreads = Integer.getInteger("diffusicraft.cpuThreads", 0);
		});
		Path out = Path.of(System.getProperty("diffusicraft.geologyDir", "build/geology"));
		Files.createDirectories(out);
		long seed = Long.getLong("diffusicraft.geologySeed", 42L);
		int tiles = Integer.getInteger("diffusicraft.geologyTiles", 4);
		String storeDir = System.getProperty("diffusicraft.geologyStore");
		// The windows do not record their seed: each seed gets a folder of its own.
		DiskWindowStore store = storeDir == null ? null
			: new DiskWindowStore(Path.of(storeDir).resolve(Long.toString(seed)));

		try (ModelBundle models = ModelBundle.load(resources.models(), resources.modelSettings())) {
			WorldPipeline pipeline = new WorldPipeline(seed, models, store, WorldPipeline.CacheLimits.defaults());
			TerrainField field = new TerrainField(seed, PipelineSources.of(seed, pipeline));

			int half = tiles * TerrainField.TILE / 2;
			long start = System.nanoTime();
			int[] centre = lowlands(field, tiles, Integer.getInteger("diffusicraft.geologySearch", 16));
			int i0 = centre[0] - half;
			int j0 = centre[1] - half;
			System.out.printf("most lowland around pixel (%d, %d), found on the coarse map in %.1f s%n", centre[0],
				centre[1], (System.nanoTime() - start) / 1e9);
			int side = 2 * half / STEP;
			float[] elevation = new float[side * side];
			float[] meanElevation = new float[side * side];
			TerrainField.Column[] columns = new TerrainField.Column[side * side];
			start = System.nanoTime();
			for (int a = 0; a < side; a++) {
				for (int b = 0; b < side; b++) {
					TerrainField.Column t = new TerrainField.Column();
					field.column(i0 + a * STEP + 0.5, j0 + b * STEP + 0.5, t);
					columns[a * side + b] = t;
					elevation[a * side + b] = t.elevation;
					meanElevation[a * side + b] = t.meanElevation;
				}
			}
			System.out.printf("terrain of %d² tiles (%.1f km across): %.1f s%n", tiles,
				2 * half * WorldScale.METERS_PER_PIXEL / 1000, (System.nanoTime() - start) / 1e9);

			Map<WorldScale, Double> basement = new EnumMap<>(WorldScale.class);
			int deepest = -1;
			float deepestBase = Float.POSITIVE_INFINITY;
			for (WorldScale scale : WorldScale.values()) {
				int minY = MIN_Y.get(scale);
				Geology geology = new Geology(GEOLOGY_SEED, minY, scale.metersPerBlock(),
					scale.verticalMetersPerBlock());
				Geology.Column c = new Geology.Column();
				float[] bases = new float[columns.length];
				int land = 0;
				int kept = 0;
				int lowland = 0;
				int lowlandKept = 0;
				for (int k = 0; k < columns.length; k++) {
					int a = k / side;
					int b = k % side;
					prepare(geology, scale, columns[k], blockOf(scale, j0 + b * STEP), blockOf(scale, i0 + a * STEP), c);
					bases[k] = c.sedimentBase();
					// A basement shows between the sediment and the deepslate.
					boolean shows = c.sedimentBase() > c.deepslateTop();
					if (elevation[k] >= 0) {
						land++;
						kept += shows ? 1 : 0;
						if (meanElevation[k] < 300) {
							lowland++;
							lowlandKept += shows ? 1 : 0;
						}
					}
					if (scale == WorldScale.STANDARD && elevation[k] >= 0 && c.sedimentBase() < deepestBase) {
						deepestBase = c.sedimentBase();
						deepest = k;
					}
				}
				float[] sorted = bases.clone();
				Arrays.sort(sorted);
				double share = kept / (double) Math.max(1, land);
				basement.put(scale, share);
				System.out.printf("%-8s min_y %4d: sediment base lowest %.0f, 5%% %.0f, median %.0f; basement under "
						+ "%.0f%% of the land (%d columns) and %.0f%% of the lowlands (%d)%n", scale.getSerializedName(),
					minY, sorted[0], sorted[sorted.length / 20], sorted[sorted.length / 2], 100 * share, land,
					100.0 * lowlandKept / Math.max(1, lowland), lowland);
			}

			assertTrue(deepest >= 0, "no land in the survey");
			// Inside the surveyed terrain, so the sections need no more inference.
			int row = i0 + (deepest / side) * STEP;
			int middle = Math.clamp(j0 + (deepest % side) * STEP, j0 + TerrainField.TILE / 2,
				j0 + 2 * half - TerrainField.TILE / 2);
			System.out.printf("sections along pixel row %d, around pixel column %d (elevation %.0f m, mean %.0f m)%n",
				row, middle, elevation[deepest], meanElevation[deepest]);
			for (WorldScale scale : WorldScale.values()) {
				section(field, scale, row, middle - TerrainField.TILE / 2, out.resolve("section-"
					+ scale.getSerializedName() + ".png"));
			}

			assertTrue(basement.get(WorldScale.STANDARD) > 0, "the standard world has a basement somewhere");
			for (WorldScale scale : WorldScale.values()) {
				assertTrue(basement.get(scale) > 0.5 * basement.get(WorldScale.STANDARD),
					scale + " keeps a basement under " + basement.get(scale) + " of the land against "
						+ basement.get(WorldScale.STANDARD) + " in the standard world");
			}
		}
	}

	/**
	 * The centre, in native pixels, of the {@code tiles}² tiles within {@code radius} tiles of the origin that hold the
	 * most lowland on the coarse map: land under 300 m.
	 */
	private static int[] lowlands(TerrainField field, int tiles, int radius) {
		int perTile = 2;
		int step = TerrainField.TILE / perTile;
		int n = (2 * radius + tiles) * perTile;
		int first = (-radius - tiles / 2) * TerrainField.TILE;
		boolean[] low = new boolean[n * n];
		for (int a = 0; a < n; a++) {
			for (int b = 0; b < n; b++) {
				float e = field.coarseElevation(first + (a + 0.5) * step, first + (b + 0.5) * step);
				low[a * n + b] = e >= 0 && e < 300;
			}
		}
		int best = -1;
		int[] centre = new int[2];
		int span = tiles * perTile;
		for (int ci = -radius; ci <= radius; ci++) {
			for (int cj = -radius; cj <= radius; cj++) {
				int count = 0;
				for (int a = (ci + radius) * perTile; a < (ci + radius) * perTile + span; a++) {
					for (int b = (cj + radius) * perTile; b < (cj + radius) * perTile + span; b++) {
						count += low[a * n + b] ? 1 : 0;
					}
				}
				if (count > best) {
					best = count;
					centre[0] = ci * TerrainField.TILE;
					centre[1] = cj * TerrainField.TILE;
				}
			}
		}
		return centre;
	}

	private static int blockOf(WorldScale scale, double pixel) {
		return (int) Math.floor(pixel / scale.pixelsPerBlock());
	}

	private static void prepare(Geology geology, WorldScale scale, TerrainField.Column t, int x, int z,
								Geology.Column c) {
		c.surfaceY = scale.surfaceY(t.elevation);
		c.meanSurfaceY = scale.surfaceY(t.meanElevation);
		c.meanElevation = t.meanElevation;
		c.relief = t.relief;
		c.temperature = t.temperature;
		c.precipitation = t.precipitation;
		geology.column(x, z, c);
	}

	/** One tile of a pixel row, cut down to the bottom of the world. */
	private static void section(TerrainField field, WorldScale scale, int row, int from, Path file) throws Exception {
		int minY = MIN_Y.get(scale);
		Geology geology = new Geology(GEOLOGY_SEED, minY, scale.metersPerBlock(), scale.verticalMetersPerBlock());
		Hollows hollows = new Hollows(GEOLOGY_SEED, minY);
		TerrainField.Column t = new TerrainField.Column();
		Geology.Column[] geo = new Geology.Column[SECTION_WIDTH];
		Hollows.Column[] open = new Hollows.Column[SECTION_WIDTH];
		int[] xs = new int[SECTION_WIDTH];
		int z = blockOf(scale, row);
		float top = scale.seaLevel();
		for (int a = 0; a < SECTION_WIDTH; a++) {
			double j = from + a * (double) TerrainField.TILE / SECTION_WIDTH;
			field.column(row + 0.5, j, t);
			xs[a] = blockOf(scale, j);
			geo[a] = new Geology.Column();
			prepare(geology, scale, t, xs[a], z, geo[a]);
			open[a] = new Hollows.Column();
			int surface = (int) Math.floor(geo[a].surfaceY);
			hollows.column(xs[a], z, (p, q) -> surface, open[a]);
			top = Math.max(top, geo[a].surfaceY);
		}
		int height = (int) Math.ceil(top) + 8 - minY;
		int blocksPerPixel = Math.max(1, (int) Math.ceil(height / (double) SECTION_HEIGHT));
		int rows = height / blocksPerPixel;
		BufferedImage image = new BufferedImage(SECTION_WIDTH, rows, BufferedImage.TYPE_INT_RGB);
		int lava = minY + 10;
		for (int a = 0; a < SECTION_WIDTH; a++) {
			for (int r = 0; r < rows; r++) {
				int y = minY + height - 1 - r * blocksPerPixel;
				int rgb;
				if (y > geo[a].surfaceY) {
					rgb = y <= scale.seaLevel() ? 0x2f5fb3 : 0xbfd8ee;
				} else if (y > minY + 4 && hollows.openKind(open[a], y) != null) {
					rgb = y <= lava ? 0xe0661a : 0x1a1a1a;
				} else {
					rgb = colour(geology.rock(geo[a], xs[a], y, z));
				}
				image.setRGB(a, r, rgb);
			}
		}
		// Sea level and y=0.
		for (int a = 0; a < SECTION_WIDTH; a += 3) {
			for (int y : new int[] {scale.seaLevel(), 0}) {
				int r = (minY + height - 1 - y) / blocksPerPixel;
				if (r >= 0 && r < rows) {
					image.setRGB(a, r, 0xffffff);
				}
			}
		}
		ImageIO.write(image, "png", file.toFile());
		System.out.printf("%s: %d blocks across, y %d to %d, %d block(s) per pixel up and %.1f across%n",
			file.getFileName(), xs[SECTION_WIDTH - 1] - xs[0], minY, minY + height, blocksPerPixel,
			(xs[SECTION_WIDTH - 1] - xs[0]) / (double) SECTION_WIDTH);
	}

	private static int colour(Rock rock) {
		return switch (rock) {
			case SANDSTONE -> 0xd8c88f;
			case RED_SANDSTONE -> 0xc0703a;
			case SHALE -> 0x5d5a55;
			case COAL_SEAM -> 0x262422;
			case LIMESTONE -> 0xcfc7b0;
			case CHALK -> 0xf0eee4;
			case ROCK_SALT -> 0xe8d4d4;
			case SLATE -> 0x4a5560;
			case MARBLE -> 0xe6e6ea;
			case SCHIST -> 0x7c8a78;
			case GNEISS -> 0x9a8f86;
			case STONE -> 0x7f7f7f;
			case GRANITE -> 0x9a6b5a;
			case DIORITE -> 0xbcbcbc;
			case ANDESITE -> 0x888888;
			case TUFF -> 0x6c6d5f;
			case DEEPSLATE -> 0x46464c;
			case GABBRO -> 0x30353a;
			case PERIDOTITE -> 0x56693f;
			case KIMBERLITE -> 0x3b4a5a;
			case MAGMA -> 0x9c3a12;
			case BLACKSTONE -> 0x2a2327;
			case BASALT -> 0x505058;
		};
	}
}
