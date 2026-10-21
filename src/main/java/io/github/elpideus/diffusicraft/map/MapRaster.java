package io.github.elpideus.diffusicraft.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One tile of the relief map: {@value #SIZE}² pixels, each with its colour, the top of the ground, the water over it
 * and its biome. The page draws the colours and reads the rest under the cursor.
 *
 * <p>On disk and on the wire a tile is gzipped, and the page lets the browser unzip it: an {@value #HEADER}-byte header
 * ({@code DMAP}, the format's version, the flags and two zero bytes), then four planes, row by row from the north-west
 * corner: the Y of the ground's top block as little-endian 16-bit integers, the depth of the water over it in blocks
 * (at most 255), the biome's index in the level's palette ({@value #NO_BIOME} where unknown) and the colour as RGB.
 */
final class MapRaster {
	static final int SIZE = 256;
	static final int PIXELS = SIZE * SIZE;
	static final int HEADER = 8;
	static final int NO_BIOME = 255;
	static final byte VERSION = 1;
	/** Set on a tile drawn from the full-resolution terrain, in part or whole. */
	static final int DETAIL = 1;
	private static final int BYTES = HEADER + 7 * PIXELS;

	final short[] ground = new short[PIXELS];
	final byte[] water = new byte[PIXELS];
	final byte[] biome = new byte[PIXELS];
	final int[] rgb = new int[PIXELS];
	int flags;

	byte[] encode() {
		byte[] raw = new byte[BYTES];
		raw[0] = 'D';
		raw[1] = 'M';
		raw[2] = 'A';
		raw[3] = 'P';
		raw[4] = VERSION;
		raw[5] = (byte) flags;
		int o = HEADER;
		for (short g : ground) {
			raw[o++] = (byte) g;
			raw[o++] = (byte) (g >> 8);
		}
		System.arraycopy(water, 0, raw, o, PIXELS);
		o += PIXELS;
		System.arraycopy(biome, 0, raw, o, PIXELS);
		o += PIXELS;
		for (int c : rgb) {
			raw[o++] = (byte) (c >> 16);
			raw[o++] = (byte) (c >> 8);
			raw[o++] = (byte) c;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(BYTES / 4);
		try (GZIPOutputStream gzip = new GZIPOutputStream(out, 1 << 16) {
			{
				def.setLevel(Deflater.BEST_SPEED + 3);
			}
		}) {
			gzip.write(raw);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}

	static MapRaster decode(byte[] gzipped) throws IOException {
		byte[] raw;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped), 1 << 16)) {
			raw = in.readNBytes(BYTES + 1);
		}
		if (raw.length != BYTES || raw[0] != 'D' || raw[1] != 'M' || raw[2] != 'A' || raw[3] != 'P'
			|| raw[4] != VERSION) {
			throw new IOException("Not a relief map tile of version " + VERSION);
		}
		MapRaster tile = new MapRaster();
		tile.flags = raw[5] & 0xff;
		int o = HEADER;
		for (int k = 0; k < PIXELS; k++, o += 2) {
			tile.ground[k] = (short) ((raw[o] & 0xff) | raw[o + 1] << 8);
		}
		System.arraycopy(raw, o, tile.water, 0, PIXELS);
		o += PIXELS;
		System.arraycopy(raw, o, tile.biome, 0, PIXELS);
		o += PIXELS;
		for (int k = 0; k < PIXELS; k++, o += 3) {
			tile.rgb[k] = (raw[o] & 0xff) << 16 | (raw[o + 1] & 0xff) << 8 | raw[o + 2] & 0xff;
		}
		return tile;
	}

	/**
	 * Draws {@code child}, a tile of the next zoom level in, into one quarter of this one: {@code quadrant} 0 to 3 is
	 * north-west, north-east, south-west and south-east. Each pixel averages the colours of the four it covers and
	 * takes the rest from the north-west one.
	 */
	void downsample(MapRaster child, int quadrant) {
		int half = SIZE / 2;
		int x0 = (quadrant & 1) * half, z0 = (quadrant >> 1) * half;
		for (int z = 0; z < half; z++) {
			for (int x = 0; x < half; x++) {
				int c = 2 * z * SIZE + 2 * x;
				int k = (z0 + z) * SIZE + x0 + x;
				ground[k] = child.ground[c];
				water[k] = child.water[c];
				biome[k] = child.biome[c];
				rgb[k] = average(child.rgb[c], child.rgb[c + 1], child.rgb[c + SIZE], child.rgb[c + SIZE + 1]);
			}
		}
		flags |= child.flags & DETAIL;
	}

	private static int average(int a, int b, int c, int d) {
		int r = ((a >> 16 & 0xff) + (b >> 16 & 0xff) + (c >> 16 & 0xff) + (d >> 16 & 0xff) + 2) >> 2;
		int g = ((a >> 8 & 0xff) + (b >> 8 & 0xff) + (c >> 8 & 0xff) + (d >> 8 & 0xff) + 2) >> 2;
		int bl = ((a & 0xff) + (b & 0xff) + (c & 0xff) + (d & 0xff) + 2) >> 2;
		return r << 16 | g << 8 | bl;
	}
}
