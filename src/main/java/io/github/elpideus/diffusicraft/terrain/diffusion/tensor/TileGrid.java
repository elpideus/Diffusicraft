/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion.tensor;

/**
 * Square sliding-window layout over an infinite 2D plane. Window {@code w} covers pixels
 * {@code [w*stride + offset, w*stride + offset + size)} on both axes. Windows overlap when {@code stride < size}.
 */
public record TileGrid(int size, int stride, int offset) {
	public TileGrid {
		if (size <= 0 || stride <= 0) {
			throw new IllegalArgumentException("size and stride must be positive");
		}
	}

	public TileGrid(int size, int stride) {
		this(size, stride, 0);
	}

	/** First pixel covered by window {@code w}. */
	public int start(int w) {
		return w * stride + offset;
	}

	/** Lowest window index whose extent contains a pixel {@code >= p0}. */
	public int first(int p0) {
		// w*stride + offset + size > p0  <=>  w > (p0 - offset - size) / stride
		return Math.floorDiv(p0 - offset - size, stride) + 1;
	}

	/** Highest window index whose extent contains a pixel {@code < p1}. */
	public int last(int p1) {
		return Math.floorDiv(p1 - 1 - offset, stride);
	}

	/** Pixel range of window {@code (wi, wj)}, used as a dependency request. */
	public PixelRange window(int wi, int wj) {
		int i0 = start(wi);
		int j0 = start(wj);
		return new PixelRange(i0, j0, i0 + size, j0 + size);
	}

	/** Half-open pixel rectangle {@code [i0, i1) x [j0, j1)}; {@code i} is the row axis, {@code j} the column axis. */
	public record PixelRange(int i0, int j0, int i1, int j1) {
		public PixelRange {
			if (i1 <= i0 || j1 <= j0) {
				throw new IllegalArgumentException("empty range " + i0 + "," + j0 + " -> " + i1 + "," + j1);
			}
		}

		public int height() {
			return i1 - i0;
		}

		public int width() {
			return j1 - j0;
		}
	}
}
