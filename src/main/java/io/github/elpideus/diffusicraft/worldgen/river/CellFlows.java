package io.github.elpideus.diffusicraft.worldgen.river;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rivers crossing the four sides of one coarse cell, from {@link RiverNetwork#flows}. Sides are numbered north
 * (towards -Z), south, west (towards -X), east. Every river that enters goes somewhere inside: out through a side,
 * into the sea, or into a pond if the cell is a pit.
 */
final class CellFlows {
	static final CellFlows NONE = new CellFlows();
	/** Target of a river that ends in the sea inside the cell. */
	static final int SEA = -1;
	/** Target of a river that ends in a pond inside the cell. */
	static final int SINK = -2;

	/** One river entering through {@code side}, bound for {@code target}: a side, {@link #SEA} or {@link #SINK}. */
	record Inflow(int side, int target, float discharge) {
	}

	/** Water level where rivers leave through each side, meters; NaN where none does. */
	final float[] outLevel = nan();
	/** Discharge leaving through each side, m³/s. */
	final float[] outDischarge = new float[4];
	/** Water level where rivers enter through each side, meters; NaN where none does. */
	final float[] inLevel = nan();
	final float[] inDischarge = new float[4];
	final List<Inflow> inflows = new ArrayList<>(2);
	/** The side a river that starts in this cell leaves through, or -1. */
	int source = -1;
	/** Which way from the middle of the cell the river starts: towards the stream that feeds it most. */
	int sourceDi;
	int sourceDj;

	boolean isEmpty() {
		return this == NONE;
	}

	void addOut(int side, float level, float discharge) {
		outLevel[side] = Float.isNaN(outLevel[side]) ? level : Math.min(outLevel[side], level);
		outDischarge[side] += discharge;
	}

	void addIn(int side, int target, float level, float discharge) {
		inLevel[side] = Float.isNaN(inLevel[side]) ? level : Math.min(inLevel[side], level);
		inDischarge[side] += discharge;
		for (int k = 0; k < inflows.size(); k++) {
			Inflow f = inflows.get(k);
			if (f.side == side && f.target == target) {
				inflows.set(k, new Inflow(side, target, f.discharge + discharge));
				return;
			}
		}
		inflows.add(new Inflow(side, target, discharge));
	}

	private static float[] nan() {
		float[] a = new float[4];
		Arrays.fill(a, Float.NaN);
		return a;
	}
}
