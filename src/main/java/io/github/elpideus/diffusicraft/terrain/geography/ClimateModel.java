package io.github.elpideus.diffusicraft.terrain.geography;

import static io.github.elpideus.diffusicraft.terrain.geography.Tectonics.gauss;

/**
 * The climate of a place from its latitude and the land and sea around it, as the coarse model is conditioned on it:
 * sea-level temperature, temperature seasonality, annual precipitation and its seasonality.
 *
 * <ul>
 *     <li><b>Winds</b> blow from the four quarters, each with a share that follows the real belts: mostly from the
 *     east in the tropics (the trade winds), with a monsoon from the equator side; from the west between about 35°
 *     and 65° (the westerlies); from the east and the pole beyond. The subtropics, between the trades and the
 *     westerlies, get a little of every wind.</li>
 *     <li><b>Moisture</b> is carried along each wind over the {@value #STEPS} steps of {@value #STEP} km upwind of a
 *     place. The sea fills the air with it; over land it slowly rains out, quickest in the subtropics, where the air
 *     sinks, and slowest in the rainforests, which give much of their rain back to the air. Rising over mountains
 *     wrings it out, so windward slopes are wet and the lands behind them lie in a rain shadow. The rain is that of
 *     the latitude scaled by what moisture each wind brings, and more on slopes facing the wind.</li>
 *     <li><b>Ocean currents</b>: cold ones along subtropical west coasts cool them and keep them dry, as in the
 *     Atacama, the Namib or Baja California; warm ones along east coasts make them warm and wet, as in Florida,
 *     southern China or Natal.</li>
 *     <li><b>Temperature</b> is that of the latitude at sea level, milder where the winds come off the sea and more
 *     extreme where they have crossed land, so west coasts in the westerlies are mild and the far side of a continent
 *     is not. Its seasonality grows towards the poles and away from the sea, from the near-constant tropics to the
 *     extremes of Siberia.</li>
 * </ul>
 * The values stay within those of the real world that the coarse model was trained on.
 */
final class ClimateModel {
	static final double STEP = 60;
	static final int STEPS = 40;
	private static final double RECHARGE = 1 - StrictMath.exp(-STEP / 350);
	private static final double WRING = 2500;
	private static final double MARITIME_REACH = 700;
	private static final double COLD_COAST_REACH = 360;
	/** Moisture of the air at the far end of a march that starts over land. */
	private static final double INLAND_START = 0.6;
	/** Meters per km at which the highest ground the air has crossed sinks back. */
	private static final double SETTLING = 2;
	/** Meters of height over which the air passes from sea to land. */
	private static final double COAST_BAND = 100;
	/** The coldest sea-level mean the coarse model knows. */
	private static final double COLDEST = -9;

	/** Zonal tables every 5° of latitude, from the equator to the pole. */
	private static final double[] SEA_TEMPERATURE = {
		27, 27, 26.5, 26, 25, 23.5, 21, 18, 15, 12, 9, 5.5, 2, -2.5, -7, -10, -13, -15, -16};
	private static final double[] PRECIPITATION = {
		2400, 2300, 1900, 1500, 1200, 1050, 950, 900, 950, 1050, 1050, 950, 800, 600, 420, 320, 250, 200, 180};
	/** How far moisture reaches over land, in km. */
	private static final double[] LAND_REACH = {
		4500, 4300, 3600, 2500, 1600, 1300, 1300, 1700, 2500, 3200, 4200, 5500, 6000, 5500, 4500, 3500, 3000, 3000, 3000};
	private static final double[] PRECIPITATION_CV = {
		30, 45, 80, 105, 100, 85, 70, 65, 55, 38, 30, 30, 35, 38, 40, 42, 44, 45, 45};
	/** Shares of the winds from the east, the west, the pole side and the equator side. */
	private static final double[][] WINDS = {
		{0.7, 0.7, 0.7, 0.7, 0.6, 0.55, 0.45, 0.3, 0.12, 0.05, 0.05, 0.08, 0.2, 0.35, 0.4, 0.5, 0.6, 0.6, 0.6},
		{0, 0, 0, 0, 0.05, 0.1, 0.2, 0.35, 0.6, 0.72, 0.75, 0.72, 0.6, 0.45, 0.4, 0.25, 0.05, 0, 0},
		{0, 0, 0, 0.05, 0.15, 0.15, 0.15, 0.15, 0.12, 0.1, 0.1, 0.1, 0.12, 0.15, 0.2, 0.25, 0.35, 0.4, 0.4},
		{0.3, 0.3, 0.3, 0.25, 0.2, 0.2, 0.2, 0.2, 0.16, 0.13, 0.1, 0.1, 0.08, 0.05, 0, 0, 0, 0, 0},
	};

	/** Elevation in meters at a point in km. */
	interface Relief {
		double elevation(double x, double z);
	}

	private final ReliefLattice relief;
	private final GradientNoise anomaly;

	ClimateModel(long seed, Relief relief) {
		this.relief = new ReliefLattice(relief, STEP);
		this.anomaly = new GradientNoise(Hash.of(seed, 20, 0));
	}

	/**
	 * The climate at a point in km.
	 *
	 * @param latitude degrees, positive north of the equator
	 * @param poleward the direction along z (+1 or -1) in which the latitude grows away from the equator
	 * @param out      sea-level temperature (°C), temperature seasonality (100 × the standard deviation of the
	 *                 monthly means), annual precipitation (mm) and its coefficient of variation (%)
	 */
	void climate(double x, double z, double latitude, double poleward, double[] out) {
		double a = Math.min(90, Math.abs(latitude));
		double landLoss = StrictMath.exp(-STEP / table(LAND_REACH, a));
		// Where each wind comes from: east, west, the pole side, the equator side.
		double[] fromX = {1, -1, 0, 0};
		double[] fromZ = {0, 0, poleward, -poleward};

		double total = 0, rain = 0, maritime = 0;
		for (int w = 0; w < 4; w++) {
			double share = table(WINDS[w], a);
			if (share <= 0) {
				continue;
			}
			total += share;
			double[] wind = march(x, z, fromX[w], fromZ[w], landLoss);
			rain += share * StrictMath.pow(wind[0], 1.15) * (1 + wind[1]);
			maritime += share * wind[2];
		}
		rain /= total;
		maritime /= total;

		// Cold currents off subtropical west coasts.
		double westSea = 0;
		for (int k = 1; k <= 6; k++) {
			if (relief.elevation(x - k * COLD_COAST_REACH / 6, z) <= 0) {
				westSea += 1.0 / 6;
			}
		}
		double coldCoast = westSea * gauss((a - 23) / 9);
		// Warm currents off subtropical east coasts.
		double eastSea = 0;
		for (int k = 1; k <= 6; k++) {
			if (relief.elevation(x + k * COLD_COAST_REACH / 6, z) <= 0) {
				eastSea += 1.0 / 6;
			}
		}
		double warmCoast = eastSea * gauss((a - 30) / 11);

		double precipitation = table(PRECIPITATION, a) * rain * (1 - 0.65 * coldCoast) * (1 + 0.7 * warmCoast);

		double s = StrictMath.sin(Math.toRadians(a));
		double continentality = 14 * s * s - 7 * gauss((a - 25) / 10);
		double temperature = table(SEA_TEMPERATURE, a) + (maritime - 0.5) * continentality
			+ 1.5 * anomaly.sample(x / 2500, z / 2500) - 2.5 * coldCoast + 1.5 * warmCoast;
		double spread = 0.6 + StrictMath.pow(s, 1.3) * (3 + 17 * (1 - maritime));

		double variation = table(PRECIPITATION_CV, a) + 25 * (1 - maritime) * gauss((a - 50) / 15)
			+ 35 * (1 - Math.min(1, precipitation / 500)) + 25 * maritime * gauss((a - 36) / 5);

		// Colder than the model knows: keep the warmest month, which decides what grows, and narrow the seasons.
		if (temperature < COLDEST) {
			spread = Math.max(0.2, spread - (COLDEST - temperature) / 1.4);
		}
		out[0] = Math.clamp(temperature, COLDEST, 32);
		out[1] = Math.max(20, 100 * spread);
		out[2] = Math.clamp(precipitation, 0, 6000);
		out[3] = Math.clamp(variation, 8, Math.max(8, 185 - 0.04111 * precipitation));
	}

	/**
	 * Carries moisture along one wind, from far upwind to the point: the moisture left, how steeply the ground rises
	 * into the wind and how much of the way upwind lies over the sea, nearer stretches counting more.
	 *
	 * <p>Air that has been lifted over a range has already lost what the climb wrings out, so only rising above the
	 * highest ground it has crossed dries it further. That height sinks back slowly as the land gives moisture back.
	 * The coast is a narrow band rather than a line, so the winds do not split into streaks on either side of a cape.
	 */
	private double[] march(double x, double z, double fromX, double fromZ, double landLoss) {
		double moisture = 0, ceiling = 0, upwind = 0, here = 0;
		double maritime = 0, weights = 0;
		for (int k = STEPS; k >= 0; k--) {
			double h = relief.elevation(x + k * STEP * fromX, z + k * STEP * fromZ);
			double sea = Math.clamp(0.5 - h / COAST_BAND, 0, 1);
			double ground = Math.max(0, h);
			if (k == STEPS) {
				moisture = sea + (1 - sea) * INLAND_START;
			} else {
				double overLand = moisture * landLoss * StrictMath.exp(-Math.max(0, ground - ceiling) / WRING);
				moisture = sea * (moisture + (1 - moisture) * RECHARGE) + (1 - sea) * overLand;
			}
			ceiling = (1 - sea) * Math.max(ground, ceiling - SETTLING * STEP);
			double weight = StrictMath.exp(-k * STEP / MARITIME_REACH);
			weights += weight;
			maritime += weight * sea;
			if (k == 1) {
				upwind = ground;
			} else if (k == 0) {
				here = ground;
			}
		}
		double windward = Math.clamp((here - upwind) / STEP / 20, 0, 1.2);
		return new double[]{moisture, windward, maritime / weights};
	}

	private static double table(double[] values, double a) {
		double t = a / 5;
		int i = Math.min((int) t, values.length - 2);
		double f = t - i;
		return values[i] + f * (values[i + 1] - values[i]);
	}
}
