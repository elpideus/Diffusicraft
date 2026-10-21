package io.github.elpideus.diffusicraft.map;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapRasterTest {
	@Test
	void encodesAndDecodesEveryPlane() throws IOException {
		Random random = new Random(1);
		MapRaster tile = new MapRaster();
		for (int k = 0; k < MapRaster.PIXELS; k++) {
			// Below zero and above 8 bits: the ground spans -64 to 32,000 or so.
			tile.ground[k] = (short) (random.nextInt(33_000) - 64);
			tile.water[k] = (byte) random.nextInt(256);
			tile.biome[k] = (byte) random.nextInt(256);
			tile.rgb[k] = random.nextInt(1 << 24);
		}
		tile.flags = MapRaster.DETAIL;
		MapRaster back = MapRaster.decode(tile.encode());
		assertArrayEquals(tile.ground, back.ground);
		assertArrayEquals(tile.water, back.water);
		assertArrayEquals(tile.biome, back.biome);
		assertArrayEquals(tile.rgb, back.rgb);
		assertEquals(MapRaster.DETAIL, back.flags);
	}

	@Test
	void rejectsWhatIsNotATile() {
		assertThrows(IOException.class, () -> MapRaster.decode(new byte[]{1, 2, 3}));
	}

	@Test
	void downsamplesIntoAQuarter() {
		MapRaster child = new MapRaster();
		int size = MapRaster.SIZE;
		// The south-east tile's first four pixels: two black, two white, the ground from the first.
		child.rgb[0] = 0x000000;
		child.rgb[1] = 0xffffff;
		child.rgb[size] = 0xffffff;
		child.rgb[size + 1] = 0x000000;
		child.ground[0] = 1234;
		child.ground[1] = 99;
		child.biome[0] = 7;
		child.water[0] = 5;
		child.flags = MapRaster.DETAIL;
		MapRaster parent = new MapRaster();
		parent.downsample(child, 3);
		int k = (size / 2) * size + size / 2;
		assertEquals(0x808080, parent.rgb[k]);
		assertEquals(1234, parent.ground[k]);
		assertEquals(7, parent.biome[k]);
		assertEquals(5, parent.water[k]);
		assertEquals(0, parent.rgb[0], "the other quarters are untouched");
		assertEquals(MapRaster.DETAIL, parent.flags);
	}
}
