package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Cuts a tile of far terrain into the faces a renderer draws. Each column of the {@link LodGrid} stands
 * {@code step} blocks on a side, and a face shows wherever one of its runs meets air, or water for a solid run:
 *
 * <ul>
 *     <li><b>Tops</b> of the highest solid run and of the highest water in each column merge into rectangles of the
 *     same block, biome, height and light, as Voxy and Distant Horizons merge theirs, so that a plain or a sea takes a
 *     few faces. So do the highest floor of a cave in each column, the highest lake in one and the highest ceiling.
 *     The other tops, under a bridge or an overhang, are kept one by one.</li>
 *     <li><b>Sides</b> show the part of a run its neighbour does not cover. Along the tile's edges, whose neighbours
 *     may be of another level, the solid runs show their sides down to a little under the ground there, a skirt that
 *     closes the cracks between tiles, and through the depths where the tile has caves, so that a cave that runs a
 *     little differently in the tile beside it, sampled more or less finely, meets rock there and not a hole. Water
 *     has none, which would show as walls under the sea.</li>
 *     <li><b>Bottoms</b> show under a solid run with air under it, such as a bridge or the ceiling of a cave.</li>
 * </ul>
 *
 * <p>The sky does not light the faces in a cave ({@link LodGrid#CAVE}), nor those in or under the water of a lake in
 * one, so that the caves stay as dark as the world's own and a shader pack does not light them as if they were open
 * to the sun. Leaves, glass and ice draw as solid, as they look from afar.
 */
public final class LodMesher {
	/** Sky light of the faces under something: an overhang, a bridge, the ground under a tree. */
	public static final int SHADED = LodGrid.FULL_SKY - 2;
	/** How far below the lowest ground along a tile's edge its skirts reach, in steps. */
	private static final int SKIRT = 2;
	/**
	 * Steps beyond a tile's edge a skirt follows the slope down: as far as the column of a tile two levels coarser
	 * may lie from the edge column it meets.
	 */
	private static final int SLOPE_STEPS = 3;
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	/** Takes the faces of a tile. */
	@FunctionalInterface
	public interface Faces {
		/**
		 * A face looking towards {@code side}, of the blocks of {@code state} in {@code biome}: the rectangle from
		 * {@code (x0, y0, z0)} to {@code (x1, y1, z1)}, flat along {@code side}'s axis. X and Z count blocks from the
		 * tile's corner and Y is the block Y. {@code sky} is the sky light in front of the face.
		 */
		void face(Direction side, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state, Holder<Biome> biome,
				  int sky);
	}

	private LodMesher() {
	}

	/** Hands the faces of {@code grid}, whose columns are {@code step} blocks apart, to {@code solid} and {@code water}. */
	public static void mesh(LodGrid grid, int step, Faces solid, Faces water) {
		int width = grid.width();
		Surface solidTops = new Surface(width, Direction.UP);
		Surface waterTops = new Surface(width, Direction.UP);
		Surface floors = new Surface(width, Direction.UP);
		Surface lakes = new Surface(width, Direction.UP);
		Surface ceilings = new Surface(width, Direction.DOWN);
		for (int column = 0; column < width * width; column++) {
			tops(grid, column, step, solid, water, solidTops, waterTops, floors, lakes, ceilings);
		}
		solidTops.merge(grid, step, solid);
		waterTops.merge(grid, step, water);
		floors.merge(grid, step, solid);
		lakes.merge(grid, step, water);
		ceilings.merge(grid, step, solid);
		long caves = caves(grid, step);
		for (int column = 0; column < width * width; column++) {
			for (Direction side : SIDES) {
				sides(grid, column, side, step, caves, solid, water);
			}
		}
	}

	/**
	 * The tops and bottoms of a column's runs: the highest of each kind into the surfaces, the others straight out.
	 * {@code floors}, {@code lakes} and {@code ceilings} take those in the caves.
	 */
	private static void tops(LodGrid grid, int column, int step, Faces solid, Faces water, Surface solidTops,
							 Surface waterTops, Surface floors, Surface lakes, Surface ceilings) {
		int width = grid.width();
		int x0 = column % width * step;
		int z0 = column / width * step;
		int start = grid.firstRun(column);
		int end = start + grid.runs(column);
		boolean solidSeen = false;
		boolean waterSeen = false;
		boolean floorSeen = false;
		boolean lakeSeen = false;
		boolean ceilingSeen = false;
		for (int run = end - 1; run >= start; run--) {
			byte kind = grid.kind(run);
			if (kind == LodGrid.AIR || kind == LodGrid.CAVE) {
				continue;
			}
			byte above = above(grid, run, end);
			if (kind == LodGrid.WATER) {
				if (above == LodGrid.AIR) {
					if (waterSeen) {
						water.face(Direction.UP, x0, grid.top(run), z0, x0 + step, grid.top(run), z0 + step,
							grid.state(run), grid.biome(column), SHADED);
					} else {
						waterTops.set(column, grid.top(run), grid.state(run), LodGrid.FULL_SKY);
					}
				} else if (above == LodGrid.CAVE) {
					if (lakeSeen) {
						water.face(Direction.UP, x0, grid.top(run), z0, x0 + step, grid.top(run), z0 + step,
							grid.state(run), grid.biome(column), 0);
					} else {
						lakes.set(column, grid.top(run), grid.state(run), 0);
					}
					lakeSeen = true;
				}
				waterSeen = true;
				continue;
			}
			if (above == LodGrid.CAVE) {
				if (floorSeen) {
					solid.face(Direction.UP, x0, grid.top(run), z0, x0 + step, grid.top(run), z0 + step, grid.state(run),
						grid.biome(column), 0);
				} else {
					floors.set(column, grid.top(run), grid.state(run), 0);
				}
				floorSeen = true;
			} else if (above == LodGrid.AIR || above == LodGrid.WATER) {
				int sky = above != LodGrid.WATER ? solidSeen ? SHADED : LodGrid.FULL_SKY
					: inCave(grid, run + 1, end) ? 0
					: Math.max(0, LodGrid.FULL_SKY - (waterTop(grid, run, end) - grid.top(run)));
				if (solidSeen) {
					solid.face(Direction.UP, x0, grid.top(run), z0, x0 + step, grid.top(run), z0 + step, grid.state(run),
						grid.biome(column), sky);
				} else {
					solidTops.set(column, grid.top(run), grid.state(run), sky);
				}
			}
			// Nothing shows under the lowest run, which reaches the bottom of the world.
			byte below = run > start ? below(grid, run, start) : LodGrid.OPAQUE;
			if (below == LodGrid.CAVE && !ceilingSeen) {
				ceilings.set(column, grid.bottom(run), grid.state(run), 0);
				ceilingSeen = true;
			} else if (!isSolid(below)) {
				int sky = below == LodGrid.CAVE || below == LodGrid.WATER && inCave(grid, run - 1, end) ? 0 : SHADED;
				solid.face(Direction.DOWN, x0, grid.bottom(run), z0, x0 + step, grid.bottom(run), z0 + step,
					grid.state(run), grid.biome(column), sky);
			}
			solidSeen = true;
		}
	}

	/** What lies right above a run: the next run if it starts where this one ends, air otherwise. */
	private static byte above(LodGrid grid, int run, int end) {
		return run + 1 < end && grid.bottom(run + 1) == grid.top(run) ? grid.kind(run + 1) : LodGrid.AIR;
	}

	/** Block Y right above the water that lies on a run. */
	private static int waterTop(LodGrid grid, int run, int end) {
		int top = grid.top(run);
		for (int r = run + 1; r < end && grid.kind(r) == LodGrid.WATER && grid.bottom(r) == top; r++) {
			top = grid.top(r);
		}
		return top;
	}

	private static byte below(LodGrid grid, int run, int start) {
		return run - 1 >= start && grid.top(run - 1) == grid.bottom(run) ? grid.kind(run - 1) : LodGrid.AIR;
	}

	/**
	 * Whether the water of a run lies in a cave: whether the first run over it, past the water and the ice, is the air
	 * of a cave or rock, and not the open air.
	 */
	private static boolean inCave(LodGrid grid, int run, int end) {
		for (int r = run; r + 1 < end && grid.bottom(r + 1) == grid.top(r); r++) {
			byte kind = grid.kind(r + 1);
			if (kind == LodGrid.CAVE || kind == LodGrid.OPAQUE) {
				return true;
			}
			if (kind == LodGrid.AIR) {
				return false;
			}
		}
		return false;
	}

	/**
	 * The depths of a grid's caves, with what lies in them, packed as the lowest block Y in the high half and the block
	 * Y right above the highest in the low, and widened by {@value #SKIRT} steps each way; {@code 0} if it has none.
	 */
	private static long caves(LodGrid grid, int step) {
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (int column = 0; column < grid.width() * grid.width(); column++) {
			int start = grid.firstRun(column);
			for (int run = start + grid.runs(column) - 1; run >= start; run--) {
				if (grid.kind(run) != LodGrid.CAVE) {
					continue;
				}
				high = Math.max(high, grid.top(run));
				// The lakes and the lava under the cave's air.
				int r = run;
				while (r > start && grid.top(r - 1) == grid.bottom(r) && grid.kind(r - 1) != LodGrid.OPAQUE
					&& grid.kind(r - 1) != LodGrid.AIR) {
					r--;
				}
				low = Math.min(low, grid.bottom(r));
			}
		}
		return low > high ? 0 : (long) (low - SKIRT * step) << 32 | (high + SKIRT * step) & 0xFFFFFFFFL;
	}

	private static boolean isSolid(byte kind) {
		return kind == LodGrid.OPAQUE || kind == LodGrid.CLEAR;
	}

	/**
	 * Block Y the skirt of a column on the tile's edge reaches down to: a little under the lowest ground of the column
	 * and of its neighbours along the edge, where the tile next to it may stand a little lower, and further down where
	 * the ground falls towards the edge, as far as it would go on falling for {@value #SLOPE_STEPS} steps past it. A
	 * coarser tile beside it meets the edge with a column whose ground lies that far out, so on a steep slope a skirt
	 * of a fixed depth leaves a crack the sky shows through.
	 */
	private static int skirt(LodGrid grid, int column, Direction side, int step) {
		int width = grid.width();
		boolean alongX = side.getAxis() == Direction.Axis.Z;
		int i = alongX ? column % width : column / width;
		int next = alongX ? 1 : width;
		int ground = ground(grid, column);
		int lowest = ground;
		if (i > 0) {
			lowest = Math.min(lowest, ground(grid, column - next));
		}
		if (i < width - 1) {
			lowest = Math.min(lowest, ground(grid, column + next));
		}
		if (lowest == Integer.MAX_VALUE) {
			return lowest;
		}
		int x = column % width - side.getStepX();
		int z = column / width - side.getStepZ();
		if (ground != Integer.MAX_VALUE && x >= 0 && x < width && z >= 0 && z < width) {
			int inward = ground(grid, z * width + x);
			if (inward != Integer.MAX_VALUE && inward > ground) {
				lowest = Math.min(lowest, ground - SLOPE_STEPS * (inward - ground));
			}
		}
		return lowest - SKIRT * step;
	}

	/**
	 * Block Y right above a column's ground: the highest solid block under its water and open sky, not the crown of a
	 * tree or a bridge over it. {@link Integer#MAX_VALUE} for a column with nothing solid.
	 */
	private static int ground(LodGrid grid, int column) {
		int surface = grid.surface(column);
		int start = grid.firstRun(column);
		for (int run = start + grid.runs(column) - 1; run >= start; run--) {
			if (isSolid(grid.kind(run)) && (surface == Integer.MIN_VALUE || grid.top(run) <= surface)) {
				return grid.top(run);
			}
		}
		return Integer.MAX_VALUE;
	}

	/**
	 * The sides of a column's runs towards {@code side} that its neighbour there leaves open. {@code caves} are the
	 * depths of the grid's caves, as {@link #caves} packs them.
	 */
	private static void sides(LodGrid grid, int column, Direction side, int step, long caves, Faces solid,
							  Faces water) {
		int width = grid.width();
		int x = column % width + side.getStepX();
		int z = column / width + side.getStepZ();
		boolean edge = x < 0 || x >= width || z < 0 || z >= width;
		int neighbour = edge ? column : z * width + x;
		int skirt = edge ? skirt(grid, column, side, step) : Integer.MIN_VALUE;
		// Faces under the water in front of them darken with depth, as the water's floor does.
		int ground = ground(grid, neighbour);
		int surface = grid.surface(neighbour);
		int waterTop = surface != Integer.MIN_VALUE && ground != Integer.MAX_VALUE && surface > ground ? surface
			: Integer.MIN_VALUE;
		int start = grid.firstRun(column);
		int end = start + grid.runs(column);
		for (int run = start; run < end; run++) {
			byte kind = grid.kind(run);
			if (kind == LodGrid.AIR || kind == LodGrid.CAVE || edge && kind == LodGrid.WATER) {
				continue;
			}
			int bottom = Math.max(grid.bottom(run), skirt);
			int top = grid.top(run);
			if (edge) {
				if (bottom < top) {
					side(grid, column, side, step, bottom, top, run, waterTop, solid);
				}
				// Under the skirt, in the dark, through the depths of the caves.
				int low = Math.max(grid.bottom(run), (int) (caves >> 32));
				int high = Math.min(Math.min(top, skirt), (int) caves);
				if (caves != 0 && low < high) {
					side(grid, column, side, step, low, high, run, solid, 0);
				}
				continue;
			}
			// The parts of [bottom, top) that the neighbour's runs do not cover.
			Faces faces = kind == LodGrid.WATER ? water : solid;
			int from = bottom;
			int nStart = grid.firstRun(neighbour);
			int nEnd = nStart + grid.runs(neighbour);
			for (int n = nStart; n < nEnd && from < top; n++) {
				byte covering = grid.kind(n);
				if (grid.top(n) <= from || !(isSolid(covering) || kind == LodGrid.WATER && covering == LodGrid.WATER)) {
					continue;
				}
				if (grid.bottom(n) >= top) {
					break;
				}
				if (grid.bottom(n) > from) {
					side(grid, column, side, step, from, grid.bottom(n), run, faces, sky(grid, neighbour, grid.bottom(n),
						waterTop));
				}
				from = Math.max(from, grid.top(n));
			}
			if (from < top) {
				side(grid, column, side, step, from, top, run, faces, sky(grid, neighbour, top, waterTop));
			}
		}
	}

	/**
	 * The sky light in front of a side that the neighbour leaves open up to block Y {@code top}, from what the
	 * neighbour holds right under it: none in a cave or a lake in one, less the deeper under its water, full in the
	 * open air.
	 */
	private static int sky(LodGrid grid, int neighbour, int top, int waterTop) {
		int start = grid.firstRun(neighbour);
		int end = start + grid.runs(neighbour);
		for (int n = start; n < end && grid.bottom(n) < top; n++) {
			if (grid.top(n) >= top) {
				byte kind = grid.kind(n);
				if (kind == LodGrid.CAVE || kind == LodGrid.WATER && inCave(grid, n, end)) {
					return 0;
				}
				break;
			}
		}
		return top <= waterTop ? Math.max(0, LodGrid.FULL_SKY - (waterTop - top)) : LodGrid.FULL_SKY;
	}

	private static void side(LodGrid grid, int column, Direction side, int step, int bottom, int top, int run,
							 int waterTop, Faces faces) {
		int sky = top <= waterTop ? Math.max(0, LodGrid.FULL_SKY - (waterTop - top)) : LodGrid.FULL_SKY;
		side(grid, column, side, step, bottom, top, run, faces, sky);
	}

	private static void side(LodGrid grid, int column, Direction side, int step, int bottom, int top, int run,
							 Faces faces, int sky) {
		int width = grid.width();
		int x0 = column % width * step;
		int z0 = column / width * step;
		int x = side == Direction.EAST ? x0 + step : x0;
		int z = side == Direction.SOUTH ? z0 + step : z0;
		int x1 = side.getAxis() == Direction.Axis.X ? x : x0 + step;
		int z1 = side.getAxis() == Direction.Axis.Z ? z : z0 + step;
		faces.face(side, x, bottom, z, x1, top, z1, grid.state(run), grid.biome(column), sky);
	}

	/** The highest top (or bottom) of one kind in each column, merged into rectangles. */
	private static final class Surface {
		private final int width;
		private final Direction facing;
		private final int[] heights;
		private final @Nullable BlockState[] states;
		private final byte[] sky;

		Surface(int width, Direction facing) {
			this.width = width;
			this.facing = facing;
			this.heights = new int[width * width];
			this.states = new BlockState[width * width];
			this.sky = new byte[width * width];
		}

		void set(int column, int height, BlockState state, int light) {
			heights[column] = height;
			states[column] = state;
			sky[column] = (byte) light;
		}

		private boolean same(LodGrid grid, int a, int b) {
			return states[b] != null && states[b] == states[a] && heights[b] == heights[a] && sky[b] == sky[a]
				&& grid.biome(b) == grid.biome(a);
		}

		/** Hands out the faces as the fewest rectangles found growing each along X, then along Z. */
		void merge(LodGrid grid, int step, Faces faces) {
			for (int z = 0; z < width; z++) {
				for (int x = 0; x < width; x++) {
					int column = z * width + x;
					BlockState state = states[column];
					if (state == null) {
						continue;
					}
					int across = 1;
					while (x + across < width && same(grid, column, column + across)) {
						across++;
					}
					int down = 1;
					grow:
					while (z + down < width) {
						for (int i = 0; i < across; i++) {
							if (!same(grid, column, column + down * width + i)) {
								break grow;
							}
						}
						down++;
					}
					for (int j = 0; j < down; j++) {
						for (int i = j == 0 ? 1 : 0; i < across; i++) {
							states[column + j * width + i] = null;
						}
					}
					faces.face(facing, x * step, heights[column], z * step, (x + across) * step, heights[column],
						(z + down) * step, state, grid.biome(column), sky[column]);
					states[column] = null;
				}
			}
		}
	}
}
