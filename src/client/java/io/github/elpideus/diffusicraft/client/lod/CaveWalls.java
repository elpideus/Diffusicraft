package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import io.github.elpideus.diffusicraft.client.height.WindowedSectionStorage;
import io.github.elpideus.diffusicraft.height.RenderWindow;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Util;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Walls of dark stone where the caves run out of the chunks the world draws, so that the far terrain does not show the
 * sky through them. The far terrain is solid ground with faces only where it meets the air, and seen from inside, as a
 * cave that leads out of the chunks drawn sees it, it has none: the eye goes on through it to the sky, and to the
 * undersides of the canopies above. So on each side of a chunk the world draws that faces one it does not, the open
 * blocks along the chunk's edge that lie below the ground and out of the sky's light get a face at the edge, looking
 * back in, where the cave would go on.
 *
 * <p>Which chunks the world draws goes by where the camera stands, as {@link ChunkMask} reckons it, and not by what
 * it sees, so that the walls stay put as the camera turns: those within the render distance, loaded and lit with the
 * eight chunks around them, and where Sodium is installed, within its reach. The fragment shader drops a wall where the
 * world draws the chunk behind it after all, as it drops the far terrain. The rows of sections above and below those
 * the world draws are left open: the far terrain fills them. The edges are looked up a few at a time and then again
 * every so often, for the blocks broken and the light that changed meanwhile. On the render thread.
 */
final class CaveWalls implements AutoCloseable {
	/** Most edges new to the walls looked up in a frame. */
	private static final int NEW_EDGES_PER_FRAME = 24;
	/** Most edges looked up again in a frame. */
	private static final int OLD_EDGES_PER_FRAME = 4;
	/** How long the walls of an edge stand before they are looked up again. */
	private static final long REFRESH_MILLIS = 1000;
	/** How often whether the chunks are loaded and lit is looked up again. */
	private static final long READY_MILLIS = 250;
	/** The size of the {@code ChunkSection} uniform block: the corner as an {@code ivec3}, then a float. */
	private static final int SECTION_SIZE = 16;
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private final LodColors colors;
	private final int bottom;
	/** The walls of each edge looked up, by the chunk drawn and the side that faces the chunk not drawn. */
	private final Map<Edge, Walls> walls = new HashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private int viewDistance = -1;
	/** Columns of chunks a side, as many as the render distance reaches across. */
	private int size;
	private int centerX = Integer.MIN_VALUE;
	private int centerZ = Integer.MIN_VALUE;
	/**
	 * Whether each chunk out to one past the render distance is loaded and lit, by
	 * {@code (dz + 1) * (size + 2) + dx + 1}.
	 */
	private boolean[] ready = new boolean[0];
	private long readyAt;
	/** Whether the world draws each chunk within the render distance, by {@code dz * size + dx} from the north-west. */
	private boolean[] drawn = new boolean[0];
	private int lowest = Integer.MIN_VALUE;
	private int rows;
	/** The block Y the world draws from, and up to, exclusive, around the camera. */
	private int drawnFrom;
	private int drawnTo;
	/** The sides of the chunks drawn that face a chunk not drawn. */
	private List<Edge> edges = List.of();
	private int nextOld;
	private boolean changed;
	private @Nullable GpuBuffer buffer;
	private @Nullable GpuBuffer section;
	private int quads;
	private int cornerX;
	private int cornerZ;
	private int stone;
	private int deepslate;

	CaveWalls(LodColors colors, int bottom) {
		this.colors = colors;
		this.bottom = bottom;
	}

	/** The walls' vertices, as {@link FarTerrainPipelines#VERTEX}, or null while there are none. */
	@Nullable GpuBuffer buffer() {
		return quads > 0 ? buffer : null;
	}

	/** The {@code ChunkSection} uniform that places the walls. */
	@Nullable GpuBuffer section() {
		return section;
	}

	int quads() {
		return quads;
	}

	/** The block X and Z the walls' vertices count from; their Y counts from the bottom of the level. */
	int cornerX() {
		return cornerX;
	}

	int cornerZ() {
		return cornerZ;
	}

	/**
	 * Works the walls out for a camera at {@code camera}, with the world drawing chunks out to {@code viewDistance} and
	 * the fog ending {@code fogEnd} blocks from the camera, or NaN for none that hides what lies behind it.
	 */
	void update(ClientLevel level, int viewDistance, Vec3 camera, float fogEnd) {
		if (stone == 0) {
			Holder<Biome> biome = level.getBiome(BlockPos.containing(camera));
			stone = colors.color(Blocks.STONE.defaultBlockState(), Direction.NORTH, biome, 0, 0);
			deepslate = colors.color(Blocks.DEEPSLATE.defaultBlockState(), Direction.NORTH, biome, 0, 0);
		}
		if (viewDistance != this.viewDistance) {
			this.viewDistance = viewDistance;
			size = 2 * viewDistance + 1;
			ready = new boolean[(size + 2) * (size + 2)];
			drawn = new boolean[size * size];
			walls.clear();
			centerX = Integer.MIN_VALUE;
		}
		int cx = SectionPos.posToSectionCoord(camera.x);
		int cz = SectionPos.posToSectionCoord(camera.z);
		long now = Util.getMillis();
		boolean moved = cx != centerX || cz != centerZ;
		if (moved) {
			centerX = cx;
			centerZ = cz;
			walls.keySet().removeIf(edge -> Math.max(Math.abs(edge.x - cx), Math.abs(edge.z - cz)) > viewDistance);
		}
		if (moved || now - readyAt > READY_MILLIS) {
			lookUpReady(level);
			readyAt = now;
		}
		RenderWindow window = WindowedSectionStorage.windowFor(level.getMinSectionY(), level.getMaxSectionY(), viewDistance);
		int rows = window == null ? level.getSectionsCount() : window.rows();
		int lowest = window == null ? level.getMinSectionY() : window.lowest(SectionPos.posToSectionCoord(camera.y));
		if (lowest != this.lowest || rows != this.rows) {
			this.lowest = lowest;
			this.rows = rows;
			// Still drawn until they are looked up again over the new rows.
			for (Walls stale : walls.values()) {
				stale.lookedUp = Long.MIN_VALUE;
			}
		}
		float reach = ChunkMask.reach(viewDistance, fogEnd);
		float limit = reach - ChunkMask.REACH_MARGIN;
		boolean redrawn = moved;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = cx - viewDistance + dx;
				int z = cz - viewDistance + dz;
				boolean on = ChunkTrackingView.isInViewDistance(cx, cz, viewDistance, x, z) && readyAround(dx, dz)
					&& (reach <= 0 || withinReach(x, z, camera, limit));
				if (drawn[dz * size + dx] != on) {
					drawn[dz * size + dx] = on;
					redrawn = true;
				}
			}
		}
		int from = lowest;
		int to = lowest + rows - 1;
		while (reach > 0 && from <= to && Math.abs(toNearest(from, camera.y)) >= limit) {
			from++;
		}
		while (reach > 0 && to >= from && Math.abs(toNearest(to, camera.y)) >= limit) {
			to--;
		}
		if (SectionPos.sectionToBlockCoord(from) != drawnFrom || SectionPos.sectionToBlockCoord(to + 1) != drawnTo) {
			drawnFrom = SectionPos.sectionToBlockCoord(from);
			drawnTo = SectionPos.sectionToBlockCoord(to + 1);
			changed = true;
		}
		if (redrawn) {
			findEdges();
			changed = true;
		}
		lookUpEdges(level, now);
		if (changed) {
			changed = false;
			build();
		}
	}

	/** Whether the nearest point of chunk {@code (x, z)} lies within {@code limit} blocks of the camera, across. */
	private static boolean withinReach(int x, int z, Vec3 camera, float limit) {
		double dx = toNearest(x, camera.x);
		double dz = toNearest(z, camera.z);
		return dx * dx + dz * dz < (double) limit * limit;
	}

	/** From {@code camera} to the nearest block coordinate of section {@code section}, its far edge included. */
	private static double toNearest(int section, double camera) {
		double low = SectionPos.sectionToBlockCoord(section);
		return Math.clamp(camera, low, low + 16) - camera;
	}

	/** Looks up whether each chunk out to one past the render distance is loaded and lit, as {@link ChunkMask} does. */
	private void lookUpReady(ClientLevel level) {
		int across = size + 2;
		for (int dz = 0; dz < across; dz++) {
			for (int dx = 0; dx < across; dx++) {
				int x = centerX - viewDistance - 1 + dx;
				int z = centerZ - viewDistance - 1 + dz;
				ready[dz * across + dx] = level.getChunk(x, z, ChunkStatus.FULL, false) != null
					&& level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z));
			}
		}
	}

	/** Whether the chunk {@code (dx, dz)} from the north-west of the render distance is ready, and the eight around. */
	private boolean readyAround(int dx, int dz) {
		int across = size + 2;
		for (int z = dz; z < dz + 3; z++) {
			for (int x = dx; x < dx + 3; x++) {
				if (!ready[z * across + x]) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean drawn(int x, int z) {
		int dx = x - centerX + viewDistance;
		int dz = z - centerZ + viewDistance;
		return dx >= 0 && dz >= 0 && dx < size && dz < size && drawn[dz * size + dx];
	}

	/** Finds the sides of the chunks drawn that face a chunk not drawn. */
	private void findEdges() {
		List<Edge> found = new ArrayList<>();
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				if (!drawn[dz * size + dx]) {
					continue;
				}
				int x = centerX - viewDistance + dx;
				int z = centerZ - viewDistance + dz;
				for (Direction side : SIDES) {
					if (!drawn(x + side.getStepX(), z + side.getStepZ())) {
						found.add(new Edge(x, z, side));
					}
				}
			}
		}
		edges = found;
		nextOld = 0;
	}

	/** Looks up the edges new to the walls, and some of those whose walls have stood a while again. */
	private void lookUpEdges(ClientLevel level, long now) {
		LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
		int added = 0;
		for (Edge edge : edges) {
			if (added == NEW_EDGES_PER_FRAME) {
				break;
			}
			if (!walls.containsKey(edge)) {
				walls.put(edge, new Walls(lookUp(level, sky, edge), now));
				changed = true;
				added++;
			}
		}
		for (int i = 0, again = 0; i < edges.size() && again < OLD_EDGES_PER_FRAME; i++) {
			nextOld = (nextOld + 1) % edges.size();
			Walls old = walls.get(edges.get(nextOld));
			if (old != null && now - old.lookedUp > REFRESH_MILLIS) {
				int[] faces = lookUp(level, sky, edges.get(nextOld));
				if (!Arrays.equals(faces, old.faces)) {
					old.faces = faces;
					changed = true;
				}
				old.lookedUp = now;
				again++;
			}
		}
	}

	/**
	 * The walls along {@code edge}, four numbers a face: the blocks along the edge it spans, from and to, exclusive, and
	 * the block Y it spans, from and to, exclusive. A block of the chunk's edge gets a wall where it is open (neither
	 * solid nor fluid), below the highest block of its column, and dark: no light of the sky reaches it. Over the rows
	 * of sections the world draws around the camera; a face never spans Y 0, where stone turns to deepslate.
	 */
	private int[] lookUp(ClientLevel level, LayerLightEventListener sky, Edge edge) {
		LevelChunk chunk = level.getChunk(edge.x, edge.z);
		int localX = edge.side == Direction.WEST ? 0 : 15;
		int localZ = edge.side == Direction.NORTH ? 0 : 15;
		boolean alongX = edge.side.getAxis() == Direction.Axis.Z;
		int[] surface = new int[16];
		int top = Integer.MIN_VALUE;
		for (int i = 0; i < 16; i++) {
			surface[i] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, alongX ? i : localX,
				alongX ? localZ : i);
			top = Math.max(top, surface[i]);
		}
		int lowestRow = Math.max(lowest, level.getMinSectionY());
		int highestRow = Math.min(Math.min(lowest + rows - 1, level.getMaxSectionY()), SectionPos.blockToSectionCoord(top));
		Faces faces = new Faces();
		int[] runs = new int[32];
		for (int i = 0; i < 16; i++) {
			int lx = alongX ? i : localX;
			int lz = alongX ? localZ : i;
			int count = 0;
			int start = Integer.MIN_VALUE;
			for (int sy = lowestRow; sy <= highestRow; sy++) {
				LevelChunkSection blocks = chunk.getSection(level.getSectionIndexFromSectionY(sy));
				DataLayer light = sky.getDataLayerData(SectionPos.of(edge.x, sy, edge.z));
				// No open block, or all of it in the light of the sky.
				boolean none = !blocks.hasOnlyAir() && !blocks.maybeHas(CaveWalls::open)
					|| light != null && light.isDefinitelyHomogenous() && !light.isDefinitelyFilledWith(0);
				for (int ly = 0; ly < 16; ly++) {
					int y = SectionPos.sectionToBlockCoord(sy, ly);
					boolean wall = !none && y < surface[i] && open(blocks.getBlockState(lx, ly, lz))
						&& (light != null ? light.get(lx, ly, lz)
						: sky.getLightValue(pos.set(SectionPos.sectionToBlockCoord(edge.x, lx), y,
						SectionPos.sectionToBlockCoord(edge.z, lz)))) == 0;
					if (start != Integer.MIN_VALUE && (!wall || y == 0)) {
						runs = put(runs, count, start, y);
						count += 2;
						start = Integer.MIN_VALUE;
					}
					if (wall && start == Integer.MIN_VALUE) {
						start = y;
					}
				}
			}
			if (start != Integer.MIN_VALUE) {
				runs = put(runs, count, start, SectionPos.sectionToBlockCoord(highestRow + 1));
				count += 2;
			}
			faces.column(i, runs, count);
		}
		faces.column(16, runs, 0);
		int origin = alongX ? SectionPos.sectionToBlockCoord(edge.x) : SectionPos.sectionToBlockCoord(edge.z);
		return faces.done(origin);
	}

	private static int[] put(int[] runs, int count, int from, int to) {
		int[] grown = count + 2 > runs.length ? Arrays.copyOf(runs, runs.length * 2) : runs;
		grown[count] = from;
		grown[count + 1] = to;
		return grown;
	}

	/** Whether the eye passes through {@code state} into what lies behind it: neither a solid block nor a fluid. */
	private static boolean open(BlockState state) {
		return !state.isSolidRender() && state.getFluidState().isEmpty();
	}

	/** Writes the walls of the edges into a new vertex buffer, in the rows the world draws. */
	private void build() {
		int most = 0;
		for (Edge edge : edges) {
			Walls found = walls.get(edge);
			most += found == null ? 0 : found.faces.length / 4;
		}
		quads = 0;
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		if (most == 0) {
			return;
		}
		int cornerX = SectionPos.sectionToBlockCoord(centerX - viewDistance - 1);
		int cornerZ = SectionPos.sectionToBlockCoord(centerZ - viewDistance - 1);
		ByteBuffer vertices = MemoryUtil.memAlloc(most * FarMeshWriter.QUAD);
		try {
			for (Edge edge : edges) {
				Walls found = walls.get(edge);
				if (found == null) {
					continue;
				}
				for (int i = 0; i < found.faces.length; i += 4) {
					int y0 = Math.max(found.faces[i + 2], drawnFrom);
					int y1 = Math.min(found.faces[i + 3], drawnTo);
					if (y0 < y1) {
						face(vertices, edge, found.faces[i], found.faces[i + 1], y0, y1, cornerX, cornerZ);
						quads++;
					}
				}
			}
			if (quads == 0) {
				return;
			}
			GpuDevice device = RenderSystem.getDevice();
			buffer = device.createBuffer(() -> "Diffusicraft cave walls", GpuBuffer.USAGE_VERTEX, vertices.flip());
			if (section == null || cornerX != this.cornerX || cornerZ != this.cornerZ) {
				this.cornerX = cornerX;
				this.cornerZ = cornerZ;
				if (section != null) {
					section.close();
				}
				ByteBuffer corner = MemoryUtil.memAlloc(SECTION_SIZE);
				try {
					corner.putInt(cornerX).putInt(bottom).putInt(cornerZ).putFloat(1).flip();
					section = device.createBuffer(() -> "Diffusicraft cave walls corner", GpuBuffer.USAGE_UNIFORM, corner);
				} finally {
					MemoryUtil.memFree(corner);
				}
			}
		} finally {
			MemoryUtil.memFree(vertices);
		}
	}

	/**
	 * Puts a wall on {@code edge} from block {@code from} to {@code to} along it and from block Y {@code y0} to
	 * {@code y1}, on the plane between the chunk drawn and the one not drawn, looking back into the chunk drawn.
	 */
	private void face(ByteBuffer vertices, Edge edge, int from, int to, int y0, int y1, int cornerX, int cornerZ) {
		Direction looks = edge.side.getOpposite();
		boolean deep = y1 <= 0;
		int color = deep ? deepslate : stone;
		int material = deep ? FarMaterials.DEEPSLATE : FarMaterials.STONE;
		int ya = y0 - bottom;
		int yb = y1 - bottom;
		if (edge.side.getAxis() == Direction.Axis.X) {
			int plane = SectionPos.sectionToBlockCoord(edge.side == Direction.EAST ? edge.x + 1 : edge.x) - cornerX;
			FarMeshWriter.quad(vertices, looks, plane, ya, from - cornerZ, plane, yb, to - cornerZ, 0, color, 255,
				material);
		} else {
			int plane = SectionPos.sectionToBlockCoord(edge.side == Direction.SOUTH ? edge.z + 1 : edge.z) - cornerZ;
			FarMeshWriter.quad(vertices, looks, from - cornerX, ya, plane, to - cornerX, yb, plane, 0, color, 255,
				material);
		}
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		if (section != null) {
			section.close();
			section = null;
		}
		quads = 0;
	}

	/** The side {@code side} of chunk {@code (x, z)}. */
	private record Edge(int x, int z, Direction side) {
	}

	/** The walls of an edge, as {@link #lookUp} gives them, and when they were looked up. */
	private static final class Walls {
		int[] faces;
		long lookedUp;

		Walls(int[] faces, long lookedUp) {
			this.faces = faces;
			this.lookedUp = lookedUp;
		}
	}

	/**
	 * Joins the runs of walls of the blocks along an edge, one block after the other, into faces: a run goes on into the
	 * next block where that block has a run from and to the same Y.
	 */
	private static final class Faces {
		/** The runs still open: from and to Y, and the block along the edge they began at. */
		private int[] open = new int[48];
		private int opened;
		private int[] done = new int[64];
		private int count;

		/** Takes in the {@code count / 2} runs of block {@code i} along the edge, in {@code runs}. */
		void column(int i, int[] runs, int count) {
			int kept = 0;
			for (int j = 0; j < opened; j += 3) {
				boolean goesOn = false;
				for (int k = 0; k < count; k += 2) {
					if (runs[k] == open[j] && runs[k + 1] == open[j + 1]) {
						goesOn = true;
						runs[k + 1] = runs[k];
						break;
					}
				}
				if (goesOn) {
					open[kept] = open[j];
					open[kept + 1] = open[j + 1];
					open[kept + 2] = open[j + 2];
					kept += 3;
				} else {
					add(open[j + 2], i, open[j], open[j + 1]);
				}
			}
			opened = kept;
			for (int k = 0; k < count; k += 2) {
				// The runs taken up by an open one were emptied above.
				if (runs[k] < runs[k + 1]) {
					if (opened + 3 > open.length) {
						open = Arrays.copyOf(open, open.length * 2);
					}
					open[opened] = runs[k];
					open[opened + 1] = runs[k + 1];
					open[opened + 2] = i;
					opened += 3;
				}
			}
		}

		private void add(int from, int to, int y0, int y1) {
			if (count + 4 > done.length) {
				done = Arrays.copyOf(done, done.length * 2);
			}
			done[count] = from;
			done[count + 1] = to;
			done[count + 2] = y0;
			done[count + 3] = y1;
			count += 4;
		}

		/** The faces, with the blocks along the edge counted from block {@code origin}. */
		int[] done(int origin) {
			int[] faces = Arrays.copyOf(done, count);
			for (int i = 0; i < count; i += 4) {
				faces[i] += origin;
				faces[i + 1] += origin;
			}
			return faces;
		}
	}
}
