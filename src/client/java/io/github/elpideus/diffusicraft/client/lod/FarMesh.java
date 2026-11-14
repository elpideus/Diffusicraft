package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.lod.LodTiles;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The mesh of a tile of far terrain on the GPU: its solid quads, then its water's, in one vertex buffer of
 * {@link FarTerrainPipelines#VERTEX}, and the {@code ChunkSection} uniform that places it, which never changes. A tile
 * with nothing to draw has no buffers.
 */
final class FarMesh implements AutoCloseable {
	/** The size of the {@code ChunkSection} uniform block: the corner as an {@code ivec3}, then a float. */
	private static final int SECTION_SIZE = 16;
	/** Bytes of every mesh not closed yet. On the render thread. */
	private static long allocated;
	/** Whether the log has said that a mesh did not fit on the GPU. On the render thread. */
	private static boolean toldOutOfMemory;

	final LodTiles.Tile tile;
	final @Nullable GpuBuffer buffer;
	final @Nullable GpuBuffer section;
	final int solidQuads;
	final int waterQuads;
	/** Block Y of the lowest and of the highest vertex. */
	final int minY;
	final int maxY;
	/** Block Y of the lowest ground or cave floor, over the sides that reach far under it along the tile's edges. */
	final int groundY;
	private long bytes;

	private FarMesh(LodTiles.Tile tile, @Nullable GpuBuffer buffer, @Nullable GpuBuffer section, int solidQuads,
					int waterQuads, int minY, int maxY, int groundY) {
		bytes = (buffer == null ? 0 : buffer.size()) + (section == null ? 0 : section.size());
		allocated += bytes;
		this.tile = tile;
		this.buffer = buffer;
		this.section = section;
		this.solidQuads = solidQuads;
		this.waterQuads = waterQuads;
		this.minY = minY;
		this.maxY = maxY;
		this.groundY = groundY;
	}

	/** Bytes of the meshes on the GPU. On the render thread. */
	static long allocated() {
		return allocated;
	}

	@Override
	public void close() {
		allocated -= bytes;
		bytes = 0;
		if (buffer != null) {
			buffer.close();
		}
		if (section != null) {
			section.close();
		}
	}

	/**
	 * The vertices of a tile, off the heap until they are uploaded, which frees them. {@code vertices} is null for a
	 * tile with nothing to draw.
	 */
	record Data(LodTiles.Tile tile, @Nullable ByteBuffer vertices, int solidQuads, int waterQuads, int minY, int maxY,
				int groundY) {
		int bytes() {
			return vertices == null ? 0 : vertices.remaining();
		}

		/**
		 * Uploads the vertices and frees them, for a level whose lowest block is at {@code bottom}, which the vertices'
		 * Y counts from. A mesh that does not fit on the GPU comes back with nothing to draw: a hole in the far terrain,
		 * which is not built again in a loop, and not a crash of the game. On the render thread.
		 */
		FarMesh upload(int bottom) {
			if (vertices == null) {
				return new FarMesh(tile, null, null, 0, 0, minY, maxY, groundY);
			}
			ByteBuffer corner = MemoryUtil.memAlloc(SECTION_SIZE);
			GpuBuffer buffer = null;
			try {
				GpuDevice device = RenderSystem.getDevice();
				buffer = device.createBuffer(() -> "Diffusicraft far terrain " + tile, GpuBuffer.USAGE_VERTEX, vertices);
				corner.putInt(tile.minBlockX()).putInt(bottom).putInt(tile.minBlockZ()).putFloat(1).flip();
				GpuBuffer section = device.createBuffer(() -> "Diffusicraft far terrain corner " + tile,
					GpuBuffer.USAGE_UNIFORM, corner);
				return new FarMesh(tile, buffer, section, solidQuads, waterQuads, minY, maxY, groundY);
			} catch (GpuOutOfMemoryException e) {
				if (buffer != null) {
					buffer.close();
				}
				if (!toldOutOfMemory) {
					toldOutOfMemory = true;
					Diffusicraft.LOGGER.warn("The far terrain does not fit on the GPU, with {} of meshes on it, so that "
						+ "parts of it stay empty: {}", allocated, e.toString());
				}
				return new FarMesh(tile, null, null, 0, 0, minY, maxY, groundY);
			} finally {
				MemoryUtil.memFree(vertices);
				MemoryUtil.memFree(corner);
			}
		}

		void free() {
			if (vertices != null) {
				MemoryUtil.memFree(vertices);
			}
		}
	}
}
