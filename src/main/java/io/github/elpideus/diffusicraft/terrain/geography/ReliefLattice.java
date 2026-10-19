package io.github.elpideus.diffusicraft.terrain.geography;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The broad relief sampled on a lattice and kept, so that the winds can cross it many times over at little cost. Each
 * point holds a weighted mean of its neighbourhood, as air spreads sideways as it goes, so winds running past a cape or
 * along a ridge do not split into streaks. Between the points it is interpolated. The values depend only on the
 * position, so what is kept, dropped or computed twice by two threads never changes a result.
 */
final class ReliefLattice {
	/** Lattice points along each side of a block. */
	private static final int BLOCK = 32;
	/** Blocks kept before all are dropped: four megabytes. */
	private static final int MAX_BLOCKS = 1024;
	/** How deep the sea counts: below this it makes no difference to the winds, and the coasts would blur seawards. */
	private static final double FLOOR = -200;
	private static final double[] KERNEL = {1, 2, 1};

	private final ClimateModel.Relief relief;
	private final double spacing;
	private final ConcurrentHashMap<Long, float[]> blocks = new ConcurrentHashMap<>();

	/** @param spacing km between the points of the lattice */
	ReliefLattice(ClimateModel.Relief relief, double spacing) {
		this.relief = relief;
		this.spacing = spacing;
	}

	/** Elevation in meters at a point in km, interpolated between the points of the lattice. */
	double elevation(double x, double z) {
		double gx = x / spacing, gz = z / spacing;
		long ix = (long) Math.floor(gx), iz = (long) Math.floor(gz);
		double fx = gx - ix, fz = gz - iz;
		double top = at(ix, iz) + fx * (at(ix + 1, iz) - at(ix, iz));
		double bottom = at(ix, iz + 1) + fx * (at(ix + 1, iz + 1) - at(ix, iz + 1));
		return top + fz * (bottom - top);
	}

	private double at(long ix, long iz) {
		long bx = Math.floorDiv(ix, BLOCK), bz = Math.floorDiv(iz, BLOCK);
		return block(bx, bz)[(int) (Math.floorMod(iz, BLOCK) * BLOCK + Math.floorMod(ix, BLOCK))];
	}

	private float[] block(long bx, long bz) {
		Long key = bx << 32 ^ (bz & 0xFFFF_FFFFL);
		float[] block = blocks.get(key);
		if (block != null) {
			return block;
		}
		int side = BLOCK + 2;
		double[] raw = new double[side * side];
		for (int r = 0; r < side; r++) {
			for (int c = 0; c < side; c++) {
				double x = (bx * BLOCK + c - 1) * spacing, z = (bz * BLOCK + r - 1) * spacing;
				raw[r * side + c] = Math.max(FLOOR, relief.elevation(x, z));
			}
		}
		block = new float[BLOCK * BLOCK];
		for (int r = 0; r < BLOCK; r++) {
			for (int c = 0; c < BLOCK; c++) {
				double sum = 0;
				for (int i = 0; i < 3; i++) {
					for (int j = 0; j < 3; j++) {
						sum += KERNEL[i] * KERNEL[j] * raw[(r + i) * side + c + j];
					}
				}
				block[r * BLOCK + c] = (float) (sum / 16);
			}
		}
		if (blocks.size() >= MAX_BLOCKS) {
			blocks.clear();
		}
		float[] previous = blocks.putIfAbsent(key, block);
		return previous != null ? previous : block;
	}
}
