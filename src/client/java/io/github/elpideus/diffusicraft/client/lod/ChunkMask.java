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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Where the world draws its own chunks, so that the far terrain leaves them out: a texture of one texel per chunk
 * around the camera, 1 where the chunk is drawn, and the {@code FarTerrainInfo} uniform the far terrain's fragment
 * shader reads it with. A chunk counts as drawn once the world would draw it: it is loaded and lit, and so are its
 * eight neighbours, it lies within the render distance, and the section of its surface has its mesh, built by Sodium
 * where it is installed or else by vanilla. Where the chunk is not drawn, as while it loads or past the vertical window of the world's
 * own sections, the far terrain shows instead, so there is no hole.
 */
final class ChunkMask implements AutoCloseable {
	private static final int INFO_SIZE = 32;
	/** How often the mask is worked out while the camera stays in its chunk. */
	private static final long REFRESH_MILLIS = 100;
	private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

	private final GpuBuffer info;
	private final ByteBuffer infoData = MemoryUtil.memAlloc(INFO_SIZE);
	private @Nullable GpuTexture texture;
	private @Nullable GpuTextureView view;
	private @Nullable ByteBuffer upload;
	private int radius = -1;
	private int size;
	private byte[] texels = new byte[0];
	private byte[] next = new byte[0];
	private boolean[] ready = new boolean[0];
	private int centerX = Integer.MIN_VALUE;
	private int centerZ = Integer.MIN_VALUE;
	private final int[] infoValues = new int[8];
	private boolean infoWritten;
	private long refreshed;

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
	 * Works the mask out for a camera at block {@code camera}, with the world drawing chunks out to {@code viewDistance}
	 * and {@code viewArea} the grid of vanilla's chunk meshes, if vanilla builds them. On the render thread.
	 */
	void update(ClientLevel level, @Nullable ViewArea viewArea, int viewDistance, BlockPos camera) {
		int r = viewDistance + 2;
		if (r != radius) {
			resize(r);
		}
		int cx = SectionPos.blockToSectionCoord(camera.getX());
		int cz = SectionPos.blockToSectionCoord(camera.getZ());
		int cy = SectionPos.blockToSectionCoord(camera.getY());
		RenderWindow window = WindowedSectionStorage.windowFor(level.getMinSectionY(), level.getMaxSectionY(), viewDistance);
		int lowest = window == null ? level.getMinSectionY() : window.lowest(cy);
		int highest = window == null ? level.getMaxSectionY() : lowest + window.rows() - 1;
		writeInfo(cx, cz, lowest, highest);
		long now = Util.getMillis();
		if (cx == centerX && cz == centerZ && now - refreshed < REFRESH_MILLIS) {
			return;
		}
		centerX = cx;
		centerZ = cz;
		refreshed = now;
		for (int j = 0; j < size; j++) {
			for (int i = 0; i < size; i++) {
				int x = cx - radius + i;
				int z = cz - radius + j;
				ready[j * size + i] = level.getChunk(x, z, ChunkStatus.FULL, false) != null
					&& level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z));
			}
		}
		Arrays.fill(next, (byte) 0);
		for (int j = 1; j < size - 1; j++) {
			for (int i = 1; i < size - 1; i++) {
				int x = cx - radius + i;
				int z = cz - radius + j;
				if (ChunkTrackingView.isInViewDistance(cx, cz, viewDistance, x, z) && readyAround(i, j)
					&& built(level, viewArea, x, z, lowest, highest)) {
					next[j * size + i] = (byte) 0xFF;
				}
			}
		}
		if (!Arrays.equals(next, texels)) {
			byte[] swap = texels;
			texels = next;
			next = swap;
			upload.clear();
			upload.put(texels).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, upload, 0, 0, 0, 0, size, size);
		}
	}

	private boolean readyAround(int i, int j) {
		for (int dj = -1; dj <= 1; dj++) {
			for (int di = -1; di <= 1; di++) {
				if (!ready[(j + dj) * size + i + di]) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Whether the world has built the mesh of the section that holds the surface of chunk {@code (x, z)}: Sodium where
	 * it is installed, which builds the meshes in vanilla's place, or else vanilla, if it builds them.
	 */
	private static boolean built(ClientLevel level, @Nullable ViewArea viewArea, int x, int z, int lowest, int highest) {
		if (!SODIUM && viewArea == null) {
			return true;
		}
		LevelChunk chunk = level.getChunk(x, z);
		int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
		int sectionY = Math.clamp(SectionPos.blockToSectionCoord(surface), lowest, highest);
		if (SODIUM) {
			return SodiumSections.ready(x, sectionY, z);
		}
		SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(
			new BlockPos(SectionPos.sectionToBlockCoord(x, 8), SectionPos.sectionToBlockCoord(sectionY, 8),
				SectionPos.sectionToBlockCoord(z, 8)));
		return section != null && section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED;
	}

	private void writeInfo(int cx, int cz, int lowest, int highest) {
		int[] values = {radius, cx, cz, 0, lowest, highest, 0, 0};
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

	private void resize(int r) {
		closeTexture();
		radius = r;
		size = 2 * r + 1;
		texels = new byte[size * size];
		next = new byte[size * size];
		ready = new boolean[size * size];
		upload = MemoryUtil.memAlloc(size * size);
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture("Diffusicraft chunk mask", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.R8_UNORM, size, size, 1, 1);
		view = device.createTextureView(texture);
		upload.put(texels).flip();
		device.createCommandEncoder().writeToTexture(texture, upload, 0, 0, 0, 0, size, size);
		centerX = Integer.MIN_VALUE;
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
