package io.github.elpideus.diffusicraft.worldgen;

import java.util.Arrays;

/**
 * How far each pixel of a {@link TerrainField} tile lies from the coastline, which the beaches and the sea cliffs
 * follow (see {@link ClimateMapping} and {@link TerrainSurface}).
 *
 * <p>The coastline is where the elevation crosses zero. Marching squares trace it through the cells between pixel
 * centres as segments, crossing each edge where the elevation, read linearly along it, is zero; a cell whose corners
 * alternate takes the side of the mean of its corners. Each pixel keeps its distance to the nearest segment, positive
 * on land (elevation zero or more) and negative at sea, up to {@link #REACH}.
 *
 * <p>Neighbouring tiles agree to the bit where they overlap, as their elevations do: each distance is worked out from
 * the pixel's own point of view, from whole-pixel offsets and the fractions of the edges, and the nearest segment does
 * not depend on the order the segments come in.
 */
final class Coastline {
	/** Farthest distance measured, in native pixels (360 m). Pixels farther from the coastline read this much. */
	static final int REACH = 12;
	/** Stored units per pixel. */
	static final float UNIT = 1024f;
	/**
	 * Stored pixels per side: the tile, with a pixel before it and two after, so that a point of the tile and the points
	 * a pixel away on either side interpolate within one tile.
	 */
	static final int SIDE = TerrainField.TILE + 3;
	/** The row and column of the padded tile where the stored pixels begin. */
	static final int ORIGIN = TerrainField.PAD - 1;

	private static final int SPAN = TerrainField.SPAN;
	private static final float FAR = REACH * REACH;

	private Coastline() {
	}

	/**
	 * The signed distances of a padded tile's {@link #SIDE}² stored pixels, row-major in units of 1/{@link #UNIT} pixel,
	 * or {@code null} when the coastline comes within {@link #REACH} of none of them: then they all lie on the side of
	 * the first.
	 *
	 * @param elevation {@link TerrainField#SPAN}² elevations in meters, as {@link TerrainField#paddedTile} has them
	 */
	static short[] compute(float[] elevation) {
		float[] signed = signed(elevation, SPAN, ORIGIN, SIDE);
		return signed == null ? null : store(signed, SIDE, 0, SIDE);
	}

	/**
	 * The signed distances to the coastline of the {@code size}² pixels at row and column {@code from} of a
	 * {@code span}² elevation array, row-major in pixels: positive on land (elevation zero or more) and negative at
	 * sea, up to {@link #REACH} either way, or {@code null} when the coastline comes within {@link #REACH} of none of
	 * them. Every segment within reach of those pixels must lie inside the array: {@code from} must be at least
	 * {@link #REACH}{@code + 1} and {@code from + size + REACH + 1} at most {@code span}.
	 */
	static float[] signed(float[] elevation, int span, int from, int size) {
		if (from < REACH + 1 || from + size + REACH + 1 > span) {
			throw new IllegalArgumentException("The coastline within reach of " + size + " pixels at " + from
				+ " reaches outside " + span);
		}
		float[] nearest = new float[size * size];
		Arrays.fill(nearest, FAR);
		boolean found = false;
		// Cell (r, c) lies between pixels r and r + 1, c and c + 1 of the array.
		int first = from - REACH - 1, last = from + size - 1 + REACH;
		float[] ends = new float[8];
		for (int r = first; r <= last; r++) {
			for (int c = first; c <= last; c++) {
				int k = r * span + c;
				float e00 = elevation[k], e01 = elevation[k + 1], e10 = elevation[k + span], e11 = elevation[k + span + 1];
				int land = (e00 >= 0f ? 1 : 0) | (e01 >= 0f ? 2 : 0) | (e10 >= 0f ? 4 : 0) | (e11 >= 0f ? 8 : 0);
				if (land == 0 || land == 15) {
					continue;
				}
				found = true;
				int segments = segments(land, e00, e01, e10, e11, ends);
				for (int s = 0; s < segments; s++) {
					stamp(nearest, size, from, r, c, ends[4 * s], ends[4 * s + 1], ends[4 * s + 2], ends[4 * s + 3]);
				}
			}
		}
		if (!found) {
			return null;
		}
		boolean near = false;
		float[] out = new float[size * size];
		for (int a = 0; a < size; a++) {
			for (int b = 0; b < size; b++) {
				int k = a * size + b;
				near |= nearest[k] < FAR;
				float d = (float) Math.sqrt(nearest[k]);
				out[k] = elevation[(from + a) * span + from + b] >= 0f ? d : -d;
			}
		}
		return near ? out : null;
	}

	/**
	 * The {@link #SIDE}² stored distances of a tile, in units of 1/{@link #UNIT} pixel, from the {@code size}² signed
	 * distances {@link #signed} gave, whose stored pixels start at row and column {@code from}; {@code null} when the
	 * coastline comes within {@link #REACH} of none of the stored pixels.
	 */
	static short[] store(float[] signed, int size, int from, int side) {
		boolean near = false;
		short[] out = new short[side * side];
		for (int a = 0; a < side; a++) {
			for (int b = 0; b < side; b++) {
				float d = signed[(from + a) * size + from + b];
				near |= Math.abs(d) < REACH;
				int units = Math.round(Math.abs(d) * UNIT);
				out[a * side + b] = (short) (d >= 0f ? units : -units);
			}
		}
		return near ? out : null;
	}

	/**
	 * The segments of the coastline in a cell whose corners are land as the bits of {@code land} say (1 for
	 * {@code e00}, 2 for {@code e01}, 4 for {@code e10}, 8 for {@code e11}), written to {@code ends} as
	 * {@code (row, column)} pairs relative to the cell's first corner, four values per segment.
	 *
	 * @return how many segments: one, or two where the corners alternate
	 */
	static int segments(int land, float e00, float e01, float e10, float e11, float[] ends) {
		// Where each crossed edge meets the coastline: top (00-01), left (00-10), right (01-11), bottom (10-11).
		boolean top = (land & 1) != (land >> 1 & 1), left = (land & 1) != (land >> 2 & 1);
		boolean right = (land >> 1 & 1) != (land >> 3 & 1), bottom = (land >> 2 & 1) != (land >> 3 & 1);
		float tTop = top ? e00 / (e00 - e01) : 0f, tLeft = left ? e00 / (e00 - e10) : 0f;
		float tRight = right ? e01 / (e01 - e11) : 0f, tBottom = bottom ? e10 / (e10 - e11) : 0f;
		if (land == 6 || land == 9) {
			// A saddle: the coastline cuts off the two corners on the other side from the cell's mean.
			boolean middle = e00 + e01 + e10 + e11 >= 0f;
			if (middle == (land == 9)) {
				// Around 01 and around 10.
				set(ends, 0, 0f, tTop, tRight, 1f);
				set(ends, 4, tLeft, 0f, 1f, tBottom);
			} else {
				// Around 00 and around 11.
				set(ends, 0, 0f, tTop, tLeft, 0f);
				set(ends, 4, tRight, 1f, 1f, tBottom);
			}
			return 2;
		}
		int n = 0;
		if (top) {
			ends[n++] = 0f;
			ends[n++] = tTop;
		}
		if (left) {
			ends[n++] = tLeft;
			ends[n++] = 0f;
		}
		if (right) {
			ends[n++] = tRight;
			ends[n++] = 1f;
		}
		if (bottom) {
			ends[n++] = 1f;
			ends[n++] = tBottom;
		}
		return 1;
	}

	private static void set(float[] ends, int at, float r0, float c0, float r1, float c1) {
		ends[at] = r0;
		ends[at + 1] = c0;
		ends[at + 2] = r1;
		ends[at + 3] = c1;
	}

	/**
	 * Lowers the squared distance of the {@code size}² pixels at row and column {@code from} within reach of a segment
	 * in cell {@code (r, c)}.
	 */
	private static void stamp(float[] nearest, int size, int from, int r, int c, float r0, float c0, float r1,
							  float c1) {
		int aFrom = Math.max(0, r - REACH - from), aTo = Math.min(size - 1, r + 1 + REACH - from);
		int bFrom = Math.max(0, c - REACH - from), bTo = Math.min(size - 1, c + 1 + REACH - from);
		for (int a = aFrom; a <= aTo; a++) {
			// The segment as the pixel sees it: whole pixels to the cell, then the fractions.
			float dr = r - from - a;
			float ar = dr + r0, br = dr + r1;
			for (int b = bFrom; b <= bTo; b++) {
				float dc = c - from - b;
				float ac = dc + c0, bc = dc + c1;
				float sr = br - ar, sc = bc - ac;
				float length = sr * sr + sc * sc;
				float t = length > 0f ? Math.clamp(-(ar * sr + ac * sc) / length, 0f, 1f) : 0f;
				float qr = ar + t * sr, qc = ac + t * sc;
				float d = qr * qr + qc * qc;
				int k = a * size + b;
				if (d < nearest[k]) {
					nearest[k] = d;
				}
			}
		}
	}
}
