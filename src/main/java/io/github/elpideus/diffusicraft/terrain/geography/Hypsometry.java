package io.github.elpideus.diffusicraft.terrain.geography;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Turns the fields of the {@link Continents} into meters so that the heights are spread like those of the real Earth
 * (ETOPO, as the coarse model saw them in training): a little over two fifths land, broad continental platforms,
 * narrow shelves, a steep continental slope and wide abyssal plains.
 *
 * <ul>
 *     <li>Under the sea, the continental field is matched quantile for quantile to the depths of the real oceans.</li>
 *     <li>On land, the uplands field is matched to the real heights of the land, and the land rises to them from the
 *     coast over a coastal plain one or two hundred kilometres wide.</li>
 * </ul>
 * The highest mountains and the deepest trenches come from the {@link Tectonics plates}, so the top of the real
 * distribution is compressed above {@value #HIGH} m and the bottom below {@value #DEEP} m. The quantiles of the fields
 * do not depend on the seed. They are measured once, over a map far larger than the longest octave.
 */
final class Hypsometry {
	/** Above this height the real distribution is compressed, leaving the mountains to the plates. */
	static final float HIGH = 450f;
	private static final float HIGH_KEEP = 0.45f;
	/** Below this depth the real distribution is compressed, leaving the trenches to the plates. */
	static final float DEEP = -5500f;
	private static final float DEEP_KEEP = 0.25f;
	/** How far above sea level the continental field rises from the coast to where the land reaches its full height. */
	private static final double COASTAL_PLAIN = 0.06;
	private static final int SAMPLES = 800;
	private static final double SPACING = 250;
	private static final double EPS = 1e-4;

	private static volatile Reference reference;

	private final double[] seaField;
	private final double[] seaMeters;
	private final double[] landField;
	private final double[] landMeters;
	private final double seaLevel;

	/** @param elevationQuantiles the real distribution of elevations in meters, at evenly spaced probabilities */
	Hypsometry(float[] elevationQuantiles) {
		int n = elevationQuantiles.length;
		double[] meters = new double[n];
		double[] probability = new double[n];
		for (int i = 0; i < n; i++) {
			float v = elevationQuantiles[i];
			if (v > HIGH) {
				v = HIGH + (v - HIGH) * HIGH_KEEP;
			} else if (v < DEEP) {
				v = DEEP + (v - DEEP) * DEEP_KEEP;
			}
			meters[i] = v;
			probability[i] = EPS + i * (1 - 2 * EPS) / (n - 1);
		}
		// The share of the sea: where the real table crosses sea level.
		int above = 0;
		while (above < n && meters[above] <= 0) {
			above++;
		}
		double sea = above == 0 ? 0 : above == n ? 1 : probability[above - 1]
			+ (probability[above] - probability[above - 1]) * -meters[above - 1] / (meters[above] - meters[above - 1]);

		Reference ref = reference(sea);
		this.seaLevel = ref.seaLevel;
		// Under the sea: the quantiles of the continental field below sea level against the real depths.
		this.seaField = new double[above + 1];
		this.seaMeters = new double[above + 1];
		for (int i = 0; i < above; i++) {
			seaField[i] = ref.continental(probability[i]);
			seaMeters[i] = meters[i];
		}
		seaField[above] = seaLevel;
		seaMeters[above] = 0;
		// On land: the quantiles of the uplands field over land against the real heights, as shares of the land.
		this.landField = new double[n - above + 1];
		this.landMeters = new double[n - above + 1];
		landField[0] = ref.upland(0);
		landMeters[0] = 0;
		for (int i = above; i < n; i++) {
			landField[i - above + 1] = ref.upland((probability[i] - sea) / (1 - sea));
			landMeters[i - above + 1] = meters[i];
		}
		increasing(seaField);
		increasing(landField);
	}

	/** The value of the continental field at sea level. */
	double seaLevel() {
		return seaLevel;
	}

	/** Meters for values of the continental and the uplands field. */
	double meters(double continental, double upland) {
		if (continental <= seaLevel) {
			return interpolate(continental, seaField, seaMeters);
		}
		double rise = Continents.smoothstep(0, COASTAL_PLAIN, continental - seaLevel);
		return rise * interpolate(upland, landField, landMeters);
	}

	private static double interpolate(double x, double[] xs, double[] ys) {
		int n = xs.length;
		if (x <= xs[0]) {
			return ys[0];
		}
		if (x >= xs[n - 1]) {
			return ys[n - 1];
		}
		int lo = 0, hi = n - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (xs[mid] <= x) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double t = (x - xs[lo]) / (xs[hi] - xs[lo]);
		return ys[lo] + t * (ys[hi] - ys[lo]);
	}

	private static void increasing(double[] q) {
		for (int i = 1; i < q.length; i++) {
			if (q[i] <= q[i - 1]) {
				q[i] = Math.nextUp(q[i - 1]);
			}
		}
	}

	private static Reference reference(double sea) {
		Reference ref = reference;
		if (ref == null || ref.sea != sea) {
			ref = new Reference(sea);
			reference = ref;
		}
		return ref;
	}

	/** The sorted values of both fields over a reference map. */
	private static final class Reference {
		final double sea;
		final double[] continental;
		final double[] upland;
		final double seaLevel;

		Reference(double sea) {
			this.sea = sea;
			Continents continents = new Continents(0x5EED_C0DEL);
			double[] fields = new double[SAMPLES * SAMPLES];
			double[] uplands = new double[SAMPLES * SAMPLES];
			double half = SAMPLES * SPACING / 2;
			IntStream.range(0, SAMPLES).parallel().forEach(r -> {
				for (int c = 0; c < SAMPLES; c++) {
					double x = c * SPACING - half, z = r * SPACING - half;
					fields[r * SAMPLES + c] = continents.field(x, z, 0);
					uplands[r * SAMPLES + c] = continents.uplands(x, z);
				}
			});
			this.continental = fields.clone();
			Arrays.sort(continental);
			this.seaLevel = quantile(continental, sea);
			int land = 0;
			for (double f : fields) {
				if (f > seaLevel) {
					land++;
				}
			}
			this.upland = new double[land];
			int k = 0;
			for (int i = 0; i < fields.length; i++) {
				if (fields[i] > seaLevel) {
					upland[k++] = uplands[i];
				}
			}
			Arrays.sort(upland);
		}

		double continental(double p) {
			return quantile(continental, p);
		}

		double upland(double p) {
			return quantile(upland, p);
		}

		private static double quantile(double[] sorted, double p) {
			double index = Math.clamp(p, 0, 1) * (sorted.length - 1);
			int lo = (int) index;
			int hi = Math.min(lo + 1, sorted.length - 1);
			return sorted[lo] + (index - lo) * (sorted[hi] - sorted[lo]);
		}
	}
}
