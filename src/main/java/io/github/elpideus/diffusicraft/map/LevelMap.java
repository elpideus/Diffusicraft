package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.height.ExtendedHeight;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.lod.LodColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A Diffusicraft level drawn from its {@link LodSampler}: the colour of the highest block of each column, as vanilla's
 * maps colour it, shaded by the slope of the ground with the light from the north-west, and the water by its depth.
 *
 * <p>Tiles at the fine zoom level and closer sample one column per pixel from the full-resolution terrain. Coarser
 * ones read the coarse map: its heights, interpolated bicubically between the coarse pixels' centres, give the shape
 * and the shore, and a grid of one column every four pixels gives the colours and the biomes. They show the land as
 * the coarse map averages it, so their mountains are lower and rounder than the world's.
 */
final class LevelMap implements MapLevel {
	/** Columns of the coarse tiles' colour grid along a side, without its border. */
	private static final int GRID = MapRaster.SIZE / 4;
	private static final int SAND = 0xDBD3A0;
	private static final int ICE = 0xD8E9F2;
	private static final int SHALLOW = 0x6FB2DC;
	private static final int DEEP = 0x1D3F74;
	/** Blocks of water over which its colour goes most of the way from shallow to deep. */
	private static final float DEPTH_SCALE = 24f;
	/** The light's elevation above the horizon. */
	private static final double LIGHT = Math.toRadians(45);
	private static final float MIN_SHADE = 0.35f;
	private static final float MAX_SHADE = 1.4f;
	/** How far from the start, in coarse pixels, a search looks. */
	private static final int SEARCH_REACH = 64;
	/** Samples of a search per coarse pixel along each axis. */
	private static final int SEARCH_DENSITY = 4;
	/** Places found by a search, at most. */
	private static final int SEARCH_RESULTS = 5;
	/** Closest two places found by a search can be, in coarse pixels. */
	private static final int SEARCH_SPACING = 2;

	private final String id;
	private final LodSampler sampler;
	private final WorldScale scale;
	private final TerrainField field;
	private final int seaLevel;
	private final int minY;
	private final int maxY;
	private final int fineZoom;
	private final int maxZoom;
	/** Blocks per coarse pixel. */
	private final double coarseBlocks;
	private final List<String> biomes = new ArrayList<>();
	private final List<String> biomeNames = new ArrayList<>();
	private final Map<ResourceKey<Biome>, Integer> biomeIndex = new HashMap<>();

	LevelMap(String id, LodSampler sampler) {
		this.id = id;
		this.sampler = sampler;
		this.scale = sampler.scale();
		this.field = sampler.field();
		this.seaLevel = sampler.seaLevel();
		this.minY = sampler.minY();
		this.maxY = sampler.maxY();
		double nativeBlocks = 1 / scale.pixelsPerBlock();
		this.coarseBlocks = field.coarseScale() * nativeBlocks;
		this.fineZoom = Math.max(0, (int) Math.round(Math.log(nativeBlocks) / Math.log(2)));
		this.maxZoom = Math.max(fineZoom + 1, (int) Math.round(Math.log(coarseBlocks) / Math.log(2)) - 2);
		List<ResourceKey<Biome>> keys = new ArrayList<>();
		for (Holder<Biome> biome : sampler.possibleBiomes()) {
			biome.unwrapKey().ifPresent(keys::add);
		}
		keys.sort(Comparator.comparing(key -> key.identifier().toString()));
		for (ResourceKey<Biome> key : keys) {
			if (biomes.size() == MapRaster.NO_BIOME) {
				break;
			}
			biomeIndex.put(key, biomes.size());
			biomes.add(key.identifier().toString());
			biomeNames.add(name(key));
		}
	}

	/** The biome's name in the game's language if it has one, else from its id: {@code dark_forest} is Dark Forest. */
	private static String name(ResourceKey<Biome> key) {
		String translation = "biome." + key.identifier().getNamespace() + "." + key.identifier().getPath();
		Language language = Language.getInstance();
		if (language.has(translation)) {
			return language.getOrDefault(translation);
		}
		StringBuilder name = new StringBuilder();
		for (String word : key.identifier().getPath().split("[_/]")) {
			if (!word.isEmpty()) {
				if (!name.isEmpty()) {
					name.append(' ');
				}
				name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
			}
		}
		return name.toString();
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public int fineZoom() {
		return fineZoom;
	}

	@Override
	public int maxZoom() {
		return maxZoom;
	}

	@Override
	public int minY() {
		return minY;
	}

	@Override
	public int maxY() {
		return maxY;
	}

	@Override
	public int seaLevel() {
		return seaLevel;
	}

	@Override
	public double metersPerBlock() {
		return scale.metersPerBlock();
	}

	@Override
	public int border() {
		return ExtendedHeight.BORDER_LIMIT;
	}

	@Override
	public List<String> biomes() {
		return biomes;
	}

	@Override
	public List<String> biomeNames() {
		return biomeNames;
	}

	private int biome(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> biomeIndex.getOrDefault(key, MapRaster.NO_BIOME)).orElse(MapRaster.NO_BIOME);
	}

	@Override
	public MapRaster render(int zoom, int x, int y, boolean coarse) {
		return coarse ? renderCoarse(zoom, x, y) : renderFine(zoom, x, y);
	}

	/** One column per pixel, from the full-resolution terrain, with a border of one for the slopes at the edges. */
	private MapRaster renderFine(int zoom, int x, int y) {
		int step = 1 << zoom, size = MapRaster.SIZE, span = size + 2;
		int minX = x * size * step, minZ = y * size * step;
		MapRaster tile = new MapRaster();
		float[] heights = new float[span * span];
		sampler.sample(minX - step, minZ - step, span, step, (a, b, column) -> {
			int ground = column.top(column.ground()) - 1;
			int water = column.waterDepth();
			heights[b * span + a] = ground + water;
			if (a == 0 || b == 0 || a == span - 1 || b == span - 1) {
				return;
			}
			int k = (b - 1) * size + a - 1;
			tile.ground[k] = (short) ground;
			tile.water[k] = (byte) Math.min(255, water);
			tile.biome[k] = (byte) biome(column.biome());
			tile.rgb[k] = colour(column, water);
		});
		shade(tile, heights, step);
		tile.flags |= MapRaster.DETAIL;
		return tile;
	}

	private static int colour(LodColumn column, int water) {
		BlockState top = column.state(column.size() - 1);
		if (water > 0) {
			return top.is(Blocks.ICE) ? ICE : water(water);
		}
		int colour = mapColour(top);
		return colour != 0 ? colour : mapColour(column.state(column.ground()));
	}

	private static int mapColour(BlockState state) {
		return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
	}

	private static int water(int depth) {
		return lerp(SHALLOW, DEEP, 1 - (float) Math.exp(-depth / DEPTH_SCALE));
	}

	/**
	 * Heights interpolated over the coarse map, and the colours and biomes of a grid of columns sampled from it, one
	 * every four pixels.
	 */
	private MapRaster renderCoarse(int zoom, int x, int y) {
		int step = 1 << zoom, size = MapRaster.SIZE, span = size + 2;
		int minX = x * size * step, minZ = y * size * step;
		MapRaster tile = new MapRaster();

		// The coarse pixels under the tile and its border, with two more on each side for the bicubic.
		double pixel = scale.pixelsPerBlock() / field.coarseScale();
		double u0 = (minZ - step + 0.5) * pixel - 0.5, v0 = (minX - step + 0.5) * pixel - 0.5;
		double u1 = (minZ + (size + 1) * step + 0.5) * pixel - 0.5, v1 = (minX + (size + 1) * step + 0.5) * pixel - 0.5;
		int cu0 = Mth.floor(u0) - 1, cv0 = Mth.floor(v0) - 1;
		int rows = Mth.floor(u1) + 3 - cu0, cols = Mth.floor(v1) + 3 - cv0;
		float[] coarse = new float[rows * cols];
		CoarseClimate.call(() -> {
			int scaleNative = field.coarseScale();
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols; c++) {
					coarse[r * cols + c] = field.coarseElevation((cu0 + r + 0.5) * scaleNative,
						(cv0 + c + 0.5) * scaleNative);
				}
			}
			return null;
		});
		float[] heights = new float[span * span];
		int[] top = new int[span * span];
		for (int b = 0; b < span; b++) {
			double u = (minZ + (b - 1) * step + step / 2 + 0.5) * pixel - 0.5 - cu0;
			for (int a = 0; a < span; a++) {
				double v = (minX + (a - 1) * step + step / 2 + 0.5) * pixel - 0.5 - cv0;
				float surface = scale.surfaceY(bicubic(coarse, cols, u, v));
				int k = b * span + a;
				top[k] = Mth.clamp(Mth.ceil(surface) - 1, minY, maxY - 1);
				heights[k] = top[k] < seaLevel - 1 ? seaLevel - 1 : surface;
			}
		}

		// The colour grid, with a border of one for the interpolation at the edges.
		int gridSpan = GRID + 2, gridStep = 4 * step;
		int[] gridColour = new int[gridSpan * gridSpan];
		int[] gridBiome = new int[gridSpan * gridSpan];
		boolean[] gridLand = new boolean[gridSpan * gridSpan];
		boolean[] gridIce = new boolean[gridSpan * gridSpan];
		sampler.sampleCoarse(minX - gridStep, minZ - gridStep, gridSpan, gridStep, (a, b, column) -> {
			int k = b * gridSpan + a;
			gridBiome[k] = biome(column.biome());
			BlockState state = column.state(column.size() - 1);
			gridLand[k] = column.waterDepth() <= 0;
			gridIce[k] = state.is(Blocks.ICE);
			gridColour[k] = gridLand[k] ? colour(column, 0) : 0;
		});

		for (int b = 0; b < size; b++) {
			float gu = (b + 0.5f) / 4 - 0.5f + 1;
			int g0 = Mth.floor(gu);
			float fu = gu - g0;
			for (int a = 0; a < size; a++) {
				float gv = (a + 0.5f) / 4 - 0.5f + 1;
				int h0 = Mth.floor(gv);
				float fv = gv - h0;
				int g = g0 * gridSpan + h0;
				int nearest = (fu < 0.5f ? g : g + gridSpan) + (fv < 0.5f ? 0 : 1);
				int k = b * size + a;
				int ground = top[(b + 1) * span + a + 1];
				tile.ground[k] = (short) ground;
				tile.biome[k] = (byte) gridBiome[nearest];
				if (ground < seaLevel - 1) {
					int depth = seaLevel - 1 - ground;
					tile.water[k] = (byte) Math.min(255, depth);
					tile.rgb[k] = gridIce[nearest] ? ICE : water(depth);
				} else if (gridLand[g] && gridLand[g + 1] && gridLand[g + gridSpan] && gridLand[g + gridSpan + 1]) {
					tile.rgb[k] = lerp(lerp(gridColour[g], gridColour[g + 1], fv),
						lerp(gridColour[g + gridSpan], gridColour[g + gridSpan + 1], fv), fu);
				} else {
					tile.rgb[k] = gridLand[nearest] ? gridColour[nearest] : SAND;
				}
			}
		}
		shade(tile, heights, step);
		return tile;
	}

	/** Catmull-Rom interpolation of a row-major grid at fractional row {@code u} and column {@code v}. */
	private static float bicubic(float[] grid, int cols, double u, double v) {
		int r = Mth.floor(u), c = Mth.floor(v);
		float fu = (float) (u - r), fv = (float) (v - c);
		float[] row = new float[4];
		for (int n = 0; n < 4; n++) {
			int base = (r - 1 + n) * cols + c - 1;
			row[n] = cubic(grid[base], grid[base + 1], grid[base + 2], grid[base + 3], fv);
		}
		return cubic(row[0], row[1], row[2], row[3], fu);
	}

	private static float cubic(float p0, float p1, float p2, float p3, float t) {
		return p1 + 0.5f * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
	}

	/**
	 * Shades the tile's colours by the slope of {@code heights}, a grid of the tile with a border of one: Lambert's
	 * law with the light from the north-west, scaled so that level ground keeps its colour. The further out, the more
	 * the heights are exaggerated, for slopes measured over wider steps are gentler.
	 */
	private void shade(MapRaster tile, float[] heights, int step) {
		int size = MapRaster.SIZE, span = size + 2;
		double exaggeration = Math.max(1, Math.sqrt(step * scale.pixelsPerBlock()) / 2);
		float scaleSlope = (float) (exaggeration / (2.0 * step));
		float lx = (float) (-Math.cos(LIGHT) / Math.sqrt(2)), lz = lx, ly = (float) Math.sin(LIGHT);
		for (int b = 0; b < size; b++) {
			for (int a = 0; a < size; a++) {
				int h = (b + 1) * span + a + 1;
				float dx = (heights[h + 1] - heights[h - 1]) * scaleSlope;
				float dz = (heights[h + span] - heights[h - span]) * scaleSlope;
				// The normal is (-dx, 1, -dz), unnormalised, in (east, up, south).
				float light = (-dx * lx + ly - dz * lz) / (float) Math.sqrt(dx * dx + 1 + dz * dz) / ly;
				float shade = Mth.clamp(light, MIN_SHADE, MAX_SHADE);
				int k = b * size + a;
				tile.rgb[k] = scale(tile.rgb[k], shade);
			}
		}
	}

	private static int scale(int rgb, float f) {
		int r = Math.min(255, Math.round((rgb >> 16 & 0xff) * f));
		int g = Math.min(255, Math.round((rgb >> 8 & 0xff) * f));
		int b = Math.min(255, Math.round((rgb & 0xff) * f));
		return r << 16 | g << 8 | b;
	}

	private static int lerp(int from, int to, float t) {
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xff, to >> 16 & 0xff));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xff, to >> 8 & 0xff));
		int b = Math.round(Mth.lerp(t, from & 0xff, to & 0xff));
		return r << 16 | g << 8 | b;
	}

	@Override
	public @Nullable Point point(int x, int z) {
		if (Math.abs(x) > border() || Math.abs(z) > border()) {
			return null;
		}
		Point[] point = new Point[1];
		sampler.sample(x, z, 1, 1, (a, b, column) -> point[0] = new Point(x, z, column.top(column.ground()) - 1,
			column.waterDepth(), biome(column.biome())));
		return point[0];
	}

	@Override
	public List<Point> search(int biome, int x, int z, long deadline) {
		return CoarseClimate.call(() -> {
			LodSampler.CoarseBiomes biomes = sampler.coarseBiomes();
			List<Point> found = new ArrayList<>();
			double step = coarseBlocks / SEARCH_DENSITY;
			double spacing = SEARCH_SPACING * coarseBlocks;
			int border = border();
			int[] at = new int[2];
			for (int ring = 0; ring <= SEARCH_REACH * SEARCH_DENSITY && found.size() < SEARCH_RESULTS; ring++) {
				if (System.nanoTime() > deadline) {
					break;
				}
				// The ring's samples, clockwise from its north-west corner.
				int samples = ring == 0 ? 1 : 8 * ring;
				for (int n = 0; n < samples && found.size() < SEARCH_RESULTS; n++) {
					ringPoint(ring, n, at);
					long px = Math.round(x + at[0] * step), pz = Math.round(z + at[1] * step);
					if (Math.abs(px) > border || Math.abs(pz) > border) {
						continue;
					}
					int bx = (int) px, bz = (int) pz;
					if (biome(biomes.at(bx, bz)) != biome || near(found, bx, bz, spacing)) {
						continue;
					}
					float surface = scale.surfaceY(field.coarseElevation(scale.toPixel(bz), scale.toPixel(bx)));
					int ground = Mth.clamp(Mth.ceil(surface) - 1, minY, maxY - 1);
					found.add(new Point(bx, bz, ground, Math.max(0, seaLevel - 1 - ground), biome));
				}
			}
			return found;
		});
	}

	/** Sample {@code n} of the square ring {@code ring} steps from the centre, as (east, south) steps. */
	private static void ringPoint(int ring, int n, int[] at) {
		if (ring == 0) {
			at[0] = 0;
			at[1] = 0;
			return;
		}
		int side = 2 * ring, edge = n / side, along = n % side;
		switch (edge) {
			case 0 -> {
				at[0] = -ring + along;
				at[1] = -ring;
			}
			case 1 -> {
				at[0] = ring;
				at[1] = -ring + along;
			}
			case 2 -> {
				at[0] = ring - along;
				at[1] = ring;
			}
			default -> {
				at[0] = -ring;
				at[1] = ring - along;
			}
		}
	}

	private static boolean near(List<Point> found, int x, int z, double spacing) {
		for (Point point : found) {
			double dx = point.x() - x, dz = point.z() - z;
			if (dx * dx + dz * dz < spacing * spacing) {
				return true;
			}
		}
		return false;
	}
}
