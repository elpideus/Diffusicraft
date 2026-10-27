package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.elpideus.diffusicraft.client.compat.sodium.SodiumSections;
import io.github.elpideus.diffusicraft.client.height.WindowedSectionStorage;
import io.github.elpideus.diffusicraft.height.RenderWindow;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Where the world draws its own chunks, so that the far terrain leaves them out: a texture of one texel per section
 * around the camera, 1 where the world draws the section, and the {@code FarTerrainInfo} uniform the far terrain's
 * fragment shader reads it with. A section counts as drawn once the world would draw it: its chunk is loaded and lit,
 * and so are the eight around it, it lies within the render distance and the rows of sections the world draws around
 * the camera, and it is air, or its mesh is built and the world has taken it in: where Sodium is installed, Sodium's
 * tree of the sections it draws holds it, and else vanilla's graph of the sections the camera can see has reached it.
 * Everywhere else the far terrain shows, so there is no hole: not while a chunk loads, not where a section waits for
 * its mesh as the player flies, and not above or below the rows the world draws.
 *
 * <p>Vanilla goes by whole chunks, as the mask does, but Sodium draws a section only while its nearest point lies
 * within the render distance of the camera itself, or nearer where the fog ends sooner (Sodium's
 * {@code TraversableTree}), which leaves out more of the outermost chunks the nearer the camera is to the edge of its
 * own. The shader and {@link #covers} test that themselves, as the camera stands in the frame, for a section that lies
 * a little nearer than Sodium needs, so that the far terrain fills those chunks wherever Sodium might leave them out.
 *
 * <p>The texture's columns and rows are slots that rotate as the camera moves, as the world's own grid of sections
 * does: section {@code (x, y, z)} always lives in slot {@code (x mod size, y mod rows, z mod size)}. Moving the
 * camera clears the slots of the sections that enter the mask, and the others keep what they held. The sections are
 * looked up a few lines of chunks at a time, so that a large render distance spreads the work over several frames.
 */
final class ChunkMask implements AutoCloseable {
	private static final int INFO_SIZE = 64;
	/** How much nearer than Sodium needs a section must lie to count as drawn, in blocks, for rounding. */
	private static final float REACH_MARGIN = 0.0625F;
	/** Most sections looked up in a frame: the whole mask at a render distance of 12, a twentieth of it at 32. */
	private static final int SECTIONS_PER_FRAME = 16384;
	private static final byte DRAWN = (byte) 0xFF;
	private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

	private final GpuBuffer info;
	private final ByteBuffer infoData = MemoryUtil.memAlloc(INFO_SIZE);
	private final int[] infoValues = new int[INFO_SIZE / Integer.BYTES];
	/** How far from the camera Sodium draws a section, in blocks, or 0 where the world goes by whole chunks. */
	private float reach;
	private double cameraX;
	private double cameraY;
	private double cameraZ;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private @Nullable GpuTexture texture;
	private @Nullable GpuTextureView view;
	private @Nullable ByteBuffer upload;
	private int viewDistance = -1;
	/** Columns of chunks a side, as many as the render distance reaches across. */
	private int size;
	/** Rows of sections the world draws at most. */
	private int rows;
	private int minSectionY;
	/** Texels in a line of the texture: every row of sections of a line of chunks, one row after the other. */
	private int width;
	/** One byte a texel, line by line: texel {@code (row * size + column, line)} is at {@code line * width + row * size + column}. */
	private byte[] texels = new byte[0];
	/** The chunk each column's slot holds now, as {@link ChunkPos#pack}. */
	private long[] columnAt = new long[0];
	/** The section row each row's slot holds now. */
	private int[] rowAt = new int[0];
	/** Whether each chunk of the three lines around the line being looked up is loaded and lit. */
	private boolean[] ready = new boolean[0];
	private int centerX = Integer.MIN_VALUE;
	private int centerZ = Integer.MIN_VALUE;
	private int lowest = Integer.MIN_VALUE;
	/** The line of chunks, counted from the mask's northern edge, to look up next. */
	private int nextLine;
	private int dirtyFrom = Integer.MAX_VALUE;
	private int dirtyTo = -1;
	private boolean infoWritten;

	ChunkMask() {
		info = RenderSystem.getDevice().createBuffer(() -> "Diffusicraft far terrain info",
			GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, INFO_SIZE);
	}

	GpuBuffer info() {
		return info;
	}

	@Nullable GpuTextureView view() {
		return view;
	}

	/**
	 * Works the mask out for a camera at {@code camera}, with the world drawing chunks out to {@code viewDistance},
	 * {@code viewArea} the grid of vanilla's chunk meshes, if vanilla builds them, and the fog of the air, of water or of
	 * blindness ending {@code fogEnd} blocks from the camera, or NaN for a fog that does not hide what lies behind it.
	 * On the render thread, once the world has culled its sections for the frame.
	 */
	void update(ClientLevel level, @Nullable ViewArea viewArea, int viewDistance, Vec3 camera, float fogEnd) {
		RenderWindow window = WindowedSectionStorage.windowFor(level.getMinSectionY(), level.getMaxSectionY(), viewDistance);
		int rows = window == null ? level.getSectionsCount() : window.rows();
		if (viewDistance != this.viewDistance || rows != this.rows || level.getMinSectionY() != minSectionY) {
			resize(viewDistance, rows, level.getMinSectionY());
		}
		reach = reach(viewDistance, fogEnd);
		cameraX = camera.x;
		cameraY = camera.y;
		cameraZ = camera.z;
		int cx = SectionPos.posToSectionCoord(camera.x);
		int cz = SectionPos.posToSectionCoord(camera.z);
		int lowest = window == null ? level.getMinSectionY() : window.lowest(SectionPos.posToSectionCoord(camera.y));
		if (cx != centerX || cz != centerZ || lowest != this.lowest) {
			moveTo(cx, cz, lowest);
		}
		SectionOcclusionGraph graph = viewArea == null ? null : Minecraft.getInstance().levelRenderer.sectionOcclusionGraph();
		for (int looked = 0, lines = 0; looked < SECTIONS_PER_FRAME && lines < size; lines++) {
			lookUp(level, viewArea, graph, nextLine);
			nextLine = (nextLine + 1) % size;
			looked += size * rows;
		}
		if (dirtyTo >= dirtyFrom) {
			upload.clear();
			upload.put(texels, dirtyFrom * width, (dirtyTo - dirtyFrom + 1) * width).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, upload, 0, 0, 0, dirtyFrom, width,
				dirtyTo - dirtyFrom + 1);
			dirtyFrom = Integer.MAX_VALUE;
			dirtyTo = -1;
		}
		writeInfo();
	}

	/**
	 * How far from the camera Sodium draws a section, in blocks, with the world drawing chunks out to
	 * {@code viewDistance} and the fog ending {@code fogEnd} blocks from the camera, or NaN for none that hides what lies
	 * behind it; or 0 where Sodium is not installed and the world goes by whole chunks.
	 */
	private static float reach(int viewDistance, float fogEnd) {
		// RenderSectionManager.getSearchDistance, with Sodium's fog occlusion on, as it is unless turned off: then
		// Sodium draws more, which only the fog hides.
		return SODIUM ? Math.min(viewDistance * 16F, Float.isNaN(fogEnd) ? Float.MAX_VALUE : fogEnd + 0.5F) : 0;
	}

	/**
	 * Whether the world draws every section of the box of blocks from {@code (minX, minY, minZ)} up to
	 * {@code (maxX, maxY, maxZ)}, exclusive, as the mask stands since the last {@link #update}: then the fragment
	 * shader drops all the far terrain in the box, and it need not be drawn at all. On the render thread.
	 */
	boolean covers(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if (centerX == Integer.MIN_VALUE || maxY <= minY) {
			return false;
		}
		int y0 = SectionPos.blockToSectionCoord(minY);
		int y1 = SectionPos.blockToSectionCoord(maxY - 1);
		if (y0 < lowest || y1 >= lowest + rows) {
			return false;
		}
		for (int z = SectionPos.blockToSectionCoord(minZ); z <= SectionPos.blockToSectionCoord(maxZ - 1); z++) {
			for (int x = SectionPos.blockToSectionCoord(minX); x <= SectionPos.blockToSectionCoord(maxX - 1); x++) {
				int column = Math.floorMod(x, size);
				int line = Math.floorMod(z, size);
				if (!ChunkTrackingView.isInViewDistance(centerX, centerZ, viewDistance, x, z)
					|| columnAt[line * size + column] != ChunkPos.pack(x, z)) {
					return false;
				}
				for (int y = y0; y <= y1; y++) {
					int row = Math.floorMod(y, rows);
					if (rowAt[row] != y || texels[line * width + row * size + column] != DRAWN
						|| !withinReach(x, y, z)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Whether Sodium's test of the distance passes for section {@code (x, y, z)} from where the camera stands, with
	 * {@value #REACH_MARGIN} of a block to spare, as the shader tests it: the section's nearest point lies within a
	 * cylinder around the camera {@link #reach} blocks across and as high above and below it.
	 */
	private boolean withinReach(int x, int y, int z) {
		if (reach <= 0) {
			return true;
		}
		double dx = toNearest(x, cameraX);
		double dy = toNearest(y, cameraY);
		double dz = toNearest(z, cameraZ);
		double limit = reach - REACH_MARGIN;
		return dx * dx + dz * dz < limit * limit && Math.abs(dy) < limit;
	}

	/** From {@code camera} to the nearest block coordinate of section {@code section}, its far edge included. */
	private static double toNearest(int section, double camera) {
		double low = SectionPos.sectionToBlockCoord(section);
		return Math.clamp(camera, low, low + 16) - camera;
	}

	/** Clears the slots of the chunks and rows that enter the mask as its centre moves to {@code (cx, cz)}. */
	private void moveTo(int cx, int cz, int lowest) {
		centerX = cx;
		centerZ = cz;
		this.lowest = lowest;
		for (int dz = -viewDistance; dz <= viewDistance; dz++) {
			for (int dx = -viewDistance; dx <= viewDistance; dx++) {
				int column = Math.floorMod(cx + dx, size);
				int line = Math.floorMod(cz + dz, size);
				long at = ChunkPos.pack(cx + dx, cz + dz);
				if (columnAt[line * size + column] != at) {
					columnAt[line * size + column] = at;
					for (int row = 0; row < rows; row++) {
						clear(line, row, column);
					}
				}
			}
		}
		for (int y = lowest; y < lowest + rows; y++) {
			int row = Math.floorMod(y, rows);
			if (rowAt[row] != y) {
				rowAt[row] = y;
				for (int line = 0; line < size; line++) {
					for (int column = 0; column < size; column++) {
						clear(line, row, column);
					}
				}
			}
		}
	}

	private void clear(int line, int row, int column) {
		set(line, row, column, (byte) 0);
	}

	private void set(int line, int row, int column, byte value) {
		int index = line * width + row * size + column;
		if (texels[index] != value) {
			texels[index] = value;
			dirtyFrom = Math.min(dirtyFrom, line);
			dirtyTo = Math.max(dirtyTo, line);
		}
	}

	/** Looks up every section of the chunks {@code offset} lines south of the mask's northern edge. */
	private void lookUp(ClientLevel level, @Nullable ViewArea viewArea, @Nullable SectionOcclusionGraph graph, int offset) {
		int z = centerZ - viewDistance + offset;
		int west = centerX - viewDistance - 1;
		int across = size + 2;
		for (int dz = -1; dz <= 1; dz++) {
			for (int i = 0; i < across; i++) {
				int x = west + i;
				ready[(dz + 1) * across + i] = level.getChunk(x, z + dz, ChunkStatus.FULL, false) != null
					&& level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z + dz));
			}
		}
		int line = Math.floorMod(z, size);
		for (int i = 1; i <= size; i++) {
			int x = west + i;
			int column = Math.floorMod(x, size);
			LevelChunk chunk = ChunkTrackingView.isInViewDistance(centerX, centerZ, viewDistance, x, z)
				&& readyAround(i, across) ? level.getChunk(x, z) : null;
			for (int y = lowest; y < lowest + rows; y++) {
				set(line, Math.floorMod(y, rows), column, chunk != null && drawn(level, chunk, viewArea, graph, x, y, z)
					? DRAWN : 0);
			}
		}
	}

	private boolean readyAround(int i, int across) {
		for (int dz = 0; dz < 3; dz++) {
			for (int di = -1; di <= 1; di++) {
				if (!ready[dz * across + i + di]) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Whether the world draws section {@code (x, y, z)} of {@code chunk}, where the camera can see it: it is air, so
	 * there is nothing to draw, or Sodium draws it, where it is installed, which builds the meshes in vanilla's place, or
	 * else vanilla has built its mesh and its graph has reached it, since vanilla draws no section the graph has not
	 * reached, built or not.
	 */
	private boolean drawn(ClientLevel level, LevelChunk chunk, @Nullable ViewArea viewArea,
						  @Nullable SectionOcclusionGraph graph, int x, int y, int z) {
		if (chunk.getSection(level.getSectionIndexFromSectionY(y)).hasOnlyAir()) {
			return true;
		}
		if (SODIUM) {
			return SodiumSections.drawn(x, y, z);
		}
		if (viewArea == null || graph == null) {
			return true;
		}
		SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(pos.set(
			SectionPos.sectionToBlockCoord(x, 8), SectionPos.sectionToBlockCoord(y, 8), SectionPos.sectionToBlockCoord(z, 8)));
		return section != null && section.getSectionNode() == SectionPos.asLong(x, y, z)
			&& section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED && graph.getNode(section) != null;
	}

	private void writeInfo() {
		int[] values = {
			viewDistance, centerX, centerZ, size,
			lowest, lowest + rows - 1, rows, 0,
			Math.floorMod(centerX - viewDistance, size), Math.floorMod(centerZ - viewDistance, size),
			Math.floorMod(lowest, rows), 0,
			Float.floatToRawIntBits(reach == 0 ? 0 : reach - REACH_MARGIN), 0, 0, 0};
		if (infoWritten && Arrays.equals(values, infoValues)) {
			return;
		}
		System.arraycopy(values, 0, infoValues, 0, values.length);
		infoData.clear();
		for (int value : values) {
			infoData.putInt(value);
		}
		infoData.flip();
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(info.slice(), infoData);
		infoWritten = true;
	}

	private void resize(int viewDistance, int rows, int minSectionY) {
		closeTexture();
		this.viewDistance = viewDistance;
		this.rows = rows;
		this.minSectionY = minSectionY;
		size = 2 * viewDistance + 1;
		width = size * rows;
		texels = new byte[width * size];
		columnAt = new long[size * size];
		Arrays.fill(columnAt, Long.MAX_VALUE);
		rowAt = new int[rows];
		Arrays.fill(rowAt, Integer.MIN_VALUE);
		ready = new boolean[3 * (size + 2)];
		upload = MemoryUtil.memAlloc(texels.length);
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture("Diffusicraft chunk mask", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.R8_UNORM, width, size, 1, 1);
		view = device.createTextureView(texture);
		upload.put(texels).flip();
		device.createCommandEncoder().writeToTexture(texture, upload, 0, 0, 0, 0, width, size);
		centerX = Integer.MIN_VALUE;
		lowest = Integer.MIN_VALUE;
		nextLine = 0;
		dirtyFrom = Integer.MAX_VALUE;
		dirtyTo = -1;
	}

	private void closeTexture() {
		if (view != null) {
			view.close();
			view = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
		if (upload != null) {
			MemoryUtil.memFree(upload);
			upload = null;
		}
	}

	@Override
	public void close() {
		closeTexture();
		info.close();
		MemoryUtil.memFree(infoData);
	}
}
