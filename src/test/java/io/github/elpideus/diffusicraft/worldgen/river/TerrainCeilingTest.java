package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainCeiling;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TerrainCeiling} over {@link RiversTest.ValleySource}'s valleys, which hold rivers and reach the sea. */
class TerrainCeilingTest {
	@Test
	void columnsStayUnderTheirChunkAndOverTheirWater() {
		TerrainField field = new TerrainField(1, new RiversTest.ValleySource());
		Rivers rivers = Rivers.of(field);
		for (WorldScale scale : WorldScale.values()) {
			int blocksPerTile = (int) Math.round(TerrainField.TILE / scale.pixelsPerBlock());
			int sea = scale.seaLevel(), channels = 0;
			// Rows of chunks across the valley floor, from the sea up the river.
			int floor = Math.floorDiv(blocksPerTile * 9 / 2, 16) * 16;
			for (int minZ = -32; minZ < 3 * blocksPerTile; minZ += blocksPerTile / 64 * 16) {
				for (int minX = floor - 256; minX < floor + 256; minX += 16) {
					int chunk = TerrainCeiling.ofChunk(field, rivers, scale, sea, minX, minZ);
					for (int z = minZ; z < minZ + 16; z++) {
						for (int x = minX; x < minX + 16; x++) {
							int column = TerrainCeiling.ofColumn(field, rivers, scale, sea, x, z);
							assertTrue(column <= chunk, scale + ": column " + x + ", " + z + " above its chunk");
							assertTrue(column > sea, scale + ": the sea at " + x + ", " + z);
							RiverColumn river = rivers.column(scale, x, z);
							if (river.channel) {
								channels++;
								assertTrue(river.waterTop < column, scale + ": water at " + x + ", " + z);
							}
						}
					}
				}
			}
			assertTrue(channels > 0, scale + " crosses no river");
		}
	}
}
