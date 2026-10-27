package io.github.elpideus.diffusicraft.client.lod;

import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodMesher;
import io.github.elpideus.diffusicraft.worldgen.lod.LodTiles;
import java.nio.ByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes the faces {@link LodMesher} cuts a tile into as the vertices of {@link FarTerrainPipelines#VERTEX}, four to a
 * face, wound counter-clockwise seen from the side the face looks to. Safe off the render thread.
 */
final class FarMeshWriter {
	static final int QUAD = 4 * FarTerrainPipelines.VERTEX_SIZE;
	/** How much the water lets through of what lies under it. */
	private static final int WATER_ALPHA = 191;
	private static final int MAX_Y = 0xFFFF;

	private FarMeshWriter() {
	}

	/** The vertices of a tile, whose grid is {@code grid}, in a level whose lowest block is at {@code bottom}. */
	static FarMesh.Data write(LodTiles.Tile tile, LodGrid grid, int bottom, LodColors colors, FarMaterials materials) {
		Quads solid = new Quads(tile, bottom, colors, materials, 255);
		Quads water = new Quads(tile, bottom, colors, materials, WATER_ALPHA);
		try {
			LodMesher.mesh(grid, tile.step(), solid, water);
			int quads = solid.count + water.count;
			int minY = Math.min(solid.minY, water.minY);
			int maxY = Math.max(solid.maxY, water.maxY);
			if (quads == 0) {
				return new FarMesh.Data(tile, null, 0, 0, bottom, bottom);
			}
			ByteBuffer vertices = MemoryUtil.memAlloc(quads * QUAD);
			vertices.put(solid.buffer.flip()).put(water.buffer.flip()).flip();
			return new FarMesh.Data(tile, vertices, solid.count, water.count, minY, maxY);
		} finally {
			solid.free();
			water.free();
		}
	}

	/**
	 * Puts the four vertices of a face looking towards {@code side} into {@code buffer}, which has room for them, wound
	 * counter-clockwise seen from that side: from {@code (x0, y0, z0)} to {@code (x1, y1, z1)}, where an upward or
	 * downward face lies at {@code y0}, a northward or southward one at {@code z0} and a westward or eastward one at
	 * {@code x0}. X and Z count from the corner of the mesh, Y from the bottom of the level.
	 */
	static void quad(ByteBuffer buffer, Direction side, int x0, int y0, int z0, int x1, int y1, int z1, int light,
					 int color, int alpha, int material) {
		switch (side) {
			case UP -> {
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y0, z1, light, color, alpha, material, side);
				vertex(buffer, x1, y0, z1, light, color, alpha, material, side);
				vertex(buffer, x1, y0, z0, light, color, alpha, material, side);
			}
			case DOWN -> {
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x1, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x1, y0, z1, light, color, alpha, material, side);
				vertex(buffer, x0, y0, z1, light, color, alpha, material, side);
			}
			case NORTH -> {
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x1, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x1, y0, z0, light, color, alpha, material, side);
			}
			case SOUTH -> {
				vertex(buffer, x1, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x1, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
			}
			case WEST -> {
				vertex(buffer, x0, y0, z1, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z1, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
			}
			case EAST -> {
				vertex(buffer, x0, y0, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z0, light, color, alpha, material, side);
				vertex(buffer, x0, y1, z1, light, color, alpha, material, side);
				vertex(buffer, x0, y0, z1, light, color, alpha, material, side);
			}
		}
	}

	private static void vertex(ByteBuffer buffer, int x, int y, int z, int light, int color, int alpha, int material,
							   Direction side) {
		buffer.putShort((short) x).putShort((short) y).putShort((short) z).putShort((short) light)
			.put((byte) ARGB.red(color)).put((byte) ARGB.green(color)).put((byte) ARGB.blue(color)).put((byte) alpha)
			.put((byte) material).put((byte) side.ordinal()).put((byte) 0).put((byte) 0);
	}

	private static final class Quads implements LodMesher.Faces {
		private final int cornerX;
		private final int cornerZ;
		private final int bottom;
		private final LodColors colors;
		private final FarMaterials materials;
		private final int alpha;
		private ByteBuffer buffer = MemoryUtil.memAlloc(256 * QUAD);
		int count;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;

		Quads(LodTiles.Tile tile, int bottom, LodColors colors, FarMaterials materials, int alpha) {
			this.cornerX = tile.minBlockX();
			this.cornerZ = tile.minBlockZ();
			this.bottom = bottom;
			this.colors = colors;
			this.materials = materials;
			this.alpha = alpha;
		}

		@Override
		public void face(Direction side, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state,
						 Holder<Biome> biome, int sky) {
			if (buffer.remaining() < QUAD) {
				buffer = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
			}
			int color = colors.color(state, side, biome, cornerX + x0, cornerZ + z0);
			int light = sky | state.getLightEmission() << 4;
			int ya = Math.clamp(y0 - bottom, 0, MAX_Y);
			int yb = Math.clamp(y1 - bottom, 0, MAX_Y);
			minY = Math.min(minY, bottom + ya);
			maxY = Math.max(maxY, bottom + yb);
			quad(buffer, side, x0, ya, z0, x1, yb, z1, light, color, alpha, materials.of(state));
			count++;
		}

		void free() {
			MemoryUtil.memFree(buffer);
		}
	}
}
