package io.github.elpideus.diffusicraft.terrain.diffusion.tensor;

/**
 * A dense {@code [channels][height][width]} float block stored channel-major in one array.
 * Every tensor of the Terrain Diffusion pipeline is two-dimensional in space with a fixed channel count,
 * so this replaces upstream's n-dimensional {@code FloatTensor}.
 */
public final class Tile {
	public final int channels;
	public final int height;
	public final int width;
	public final float[] data;

	public Tile(int channels, int height, int width) {
		this(channels, height, width, new float[Math.multiplyExact(Math.multiplyExact(channels, height), width)]);
	}

	public Tile(int channels, int height, int width, float[] data) {
		if (data.length != channels * height * width) {
			throw new IllegalArgumentException("data length " + data.length + " != " + channels + "x" + height + "x" + width);
		}
		this.channels = channels;
		this.height = height;
		this.width = width;
		this.data = data;
	}

	public int plane() {
		return height * width;
	}

	public float get(int channel, int row, int col) {
		return data[(channel * height + row) * width + col];
	}

	public long byteSize() {
		return (long) data.length * Float.BYTES;
	}
}
