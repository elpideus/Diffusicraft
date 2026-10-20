package io.github.elpideus.diffusicraft.worldgen;

/**
 * Turns real terrain and climate into the six parameters vanilla's multi-noise biome source reads, so that the
 * overworld biome table (and every mod that extends it) picks biomes that match the land.
 *
 * <p>Vanilla's parameters have no physical meaning; they are noise. We give each one the meaning its biome table
 * implies, with breakpoints at vanilla's own band edges (see {@code OverworldBiomeBuilder}):
 * <ul>
 *   <li><b>temperature</b>: the annual mean at the column's elevation, so the lapse rate makes mountain tops cold,
 *       and on its slope, which the sun warms or leaves in the shade ({@link #expose}). The hot band (deserts and
 *       badlands) needs dry air as well as heat.</li>
 *   <li><b>humidity</b>: precipitation relative to the Köppen aridity threshold {@code 20 (T + 7)} mm (at least
 *       140 mm), less what the sun dries out of a sunny slope.</li>
 *   <li><b>continentalness</b>: water depth offshore, the height above the sea on the coast, the mean elevation
 *       inland. The coast, which holds vanilla's beaches and stony shores, is the strip of low ground along the
 *       coastline, as wide as real beaches are.</li>
 *   <li><b>erosion</b>: the local relief. Rugged land has low erosion, flat land high. Flat, low, wet land gets
 *       vanilla's swamp band.</li>
 *   <li><b>weirdness</b>: whether the column is a local peak or a hollow, from its elevation against the local mean
 *       (vanilla's "peaks and valleys"). The sign comes from smooth noise and only picks biome variants.</li>
 *   <li><b>depth</b>: blocks below the surface, capped so tall mountains do not fill with cave biomes.</li>
 * </ul>
 * Vanilla puts snow on every mountain colder than its temperate band. Here a mountain that is warmer than 1 °C is
 * moved to the plateau and forest biomes instead, with a bare stony summit when it is still cool.
 *
 * <p>Everything is static and pure, so it can be unit-tested without a world.
 */
public final class ClimateMapping {
	public static final int TEMPERATURE = 0;
	public static final int HUMIDITY = 1;
	public static final int CONTINENTALNESS = 2;
	public static final int EROSION = 3;
	public static final int WEIRDNESS = 4;
	public static final int PARAMETERS = 5;

	/** Height above the sea, in blocks, up to which land counts as coast at least. */
	static final float COAST_HEIGHT_BLOCKS = 2.5f;
	/** Real height above the sea up to which land counts as coast, in meters, where that is more blocks. */
	static final float COAST_HEIGHT = 5f;
	/** Real width of the coast inland from the coastline, in meters, from narrow to wide beaches. */
	static final float COAST_WIDTH_MIN = 45f;
	static final float COAST_WIDTH_MAX = 120f;
	/** Narrowest coast, in blocks, so that the smaller scales still have beaches. */
	static final float COAST_WIDTH_BLOCKS = 5f;
	/** Mountains warmer than this have no snow. */
	static final float SNOW_LINE_TEMPERATURE = 1f;
	/** Bare stony summits stop below this temperature; warmer peaks are forested. */
	static final float STONY_PEAK_TEMPERATURE = 8f;
	/**
	 * Erosion below which vanilla's peak slice picks its peak biome however far inland: relief over 250 m. Only there
	 * does a summit's forced temperature band give stony peaks; elsewhere it would give a jungle.
	 */
	static final float PEAK_EROSION = -0.78f;
	/** How much warmer than flat ground a sunny slope is, at most, in °C: a cliff facing south. */
	public static final float EXPOSURE_WARMING = 3f;
	/** The share of its water the sun dries out of a sunny slope, at most, and a shaded slope keeps. */
	public static final float EXPOSURE_DRYING = 0.4f;
	/** Depth is measured from at most this many blocks above the sea. */
	static final int DEPTH_CEILING = 60;
	/** Blocks per unit of depth. Cave biomes sit between 0.2 and 0.9, the deep dark at 1.1. */
	static final float DEPTH_SCALE = 128f;

	private static final float[] TEMPERATURE_C = {-15f, -5f, 5f, 15f, 25f};
	private static final float[] TEMPERATURE_P = {-1f, -0.45f, -0.15f, 0.2f, 0.54f};
	private static final float[] ARIDITY_Q = {0f, 0.5f, 1f, 2f, 3f, 6f};
	private static final float[] ARIDITY_P = {-1f, -0.35f, -0.1f, 0.1f, 0.3f, 1f};
	private static final float[] RELIEF_M = {0f, 50f, 90f, 150f, 250f, 500f};
	private static final float[] RELIEF_P = {0.44f, 0.05f, -0.2225f, -0.375f, -0.78f, -1f};
	private static final float[] OCEAN_DEPTH_M = {0f, 200f, 2000f};
	private static final float[] OCEAN_DEPTH_P = {-0.19f, -0.455f, -1f};
	private static final float[] INLAND_MEAN_M = {0f, 150f, 600f, 2500f};
	private static final float[] INLAND_MEAN_P = {-0.11f, 0.03f, 0.3f, 1f};
	private static final float[] PEAKS_Z = {-1.5f, 0f, 1.5f};
	private static final float[] PEAKS_P = {-0.8f, 0f, 1f};

	private ClimateMapping() {
	}

	/**
	 * Fills {@code out} (length {@link #PARAMETERS}) with the 2D biome parameters of a column whose ground lies at
	 * {@code groundY}, in blocks. Only the coast reads the ground; elsewhere it may be the smooth surface.
	 */
	public static void compute(WorldScale scale, TerrainField.Column c, float groundY, float[] out) {
		float aridity = aridityRatio(c.temperature, c.precipitation);
		float humidity = humidity(aridity);
		boolean hot = isHotDesert(c.temperature, aridity);
		float temperature = temperature(c.temperature, hot);

		float coastHeight = (groundY - scale.seaLevel()) / coastHeight(scale);
		boolean coast = c.elevation >= 0f && c.coast < coastWidth(scale, c.variant) && coastHeight < 1f;
		float continentalness = continentalness(c.elevation, c.meanElevation, coast ? coastHeight : Float.NaN);
		float peaks = peaksAndValleys(c.elevation, c.meanElevation, c.relief);
		float erosion = erosion(c.relief);

		boolean mountain = erosion < -0.375f || peaks > 0.2f && erosion < -0.2225f;
		if (mountain && !hot && c.temperature >= SNOW_LINE_TEMPERATURE) {
			if (peaks >= 0.7f && erosion < PEAK_EROSION && c.temperature < STONY_PEAK_TEMPERATURE) {
				temperature = 0.3f;
			} else {
				erosion = Math.max(erosion, -0.3f);
			}
		}
		boolean wetland = c.relief < 6f && c.elevation < 60f && !coast && humidity >= 0.1f && c.temperature > -5f;
		if (wetland) {
			erosion = 0.75f;
		}

		out[TEMPERATURE] = temperature;
		out[HUMIDITY] = humidity;
		out[CONTINENTALNESS] = continentalness;
		out[EROSION] = erosion;
		out[WEIRDNESS] = weirdness(peaks, c.variant);
	}

	/**
	 * How squarely a slope faces the sun, in [-1, 1]: the sine of its steepness times the cosine of the angle between
	 * the way it faces and the south. A cliff facing south is 1, one facing north -1, flat ground and slopes facing east
	 * or west 0. The world lies in the northern hemisphere, where the noon sun stands in the south.
	 *
	 * @param slopeX rise in meters per meter eastward (+X)
	 * @param slopeZ rise in meters per meter southward (+Z)
	 */
	public static float exposure(float slopeX, float slopeZ) {
		return (float) (-slopeZ / Math.sqrt(1.0 + slopeX * slopeX + slopeZ * slopeZ));
	}

	/**
	 * Gives a column the climate of its slope. The sun warms a slope that faces it and dries its ground, and a slope
	 * facing away stays cool and moist, so the tree line and the snow climb higher on the sunny side of a mountain,
	 * and in a dry climate the woods keep to the shaded slopes. The precipitation becomes the water the ground keeps.
	 */
	public static void expose(TerrainField.Column c, float exposure) {
		c.temperature += EXPOSURE_WARMING * exposure;
		c.precipitation *= 1f - EXPOSURE_DRYING * exposure;
	}

	/**
	 * Precipitation over the Köppen aridity threshold: below 0.5 is desert, below 1 steppe. The threshold stops falling
	 * below 0 °C, so tundra counts as moderately dry (snowy plains) rather than as wet as a rainforest.
	 */
	public static float aridityRatio(float temperature, float precipitation) {
		float threshold = 20f * Math.max(temperature + 7f, 7f);
		return precipitation / threshold;
	}

	static boolean isHotDesert(float temperature, float aridity) {
		return aridity < 0.5f && temperature >= 5f;
	}

	static float temperature(float celsius, boolean hotDesert) {
		if (hotDesert) {
			return clamp(0.56f + (celsius - 5f) * 0.0125f, 0.56f, 1f);
		}
		return piecewise(celsius, TEMPERATURE_C, TEMPERATURE_P);
	}

	static float humidity(float aridity) {
		return piecewise(aridity, ARIDITY_Q, ARIDITY_P);
	}

	/**
	 * The continentalness of the sea from its depth, of the coast from {@code coastHeight}, its height over
	 * {@link #coastHeight}, and inland from the mean elevation. {@code coastHeight} is NaN away from the coast.
	 */
	static float continentalness(float elevation, float meanElevation, float coastHeight) {
		if (elevation < 0f) {
			return piecewise(-elevation, OCEAN_DEPTH_M, OCEAN_DEPTH_P);
		}
		if (!Float.isNaN(coastHeight)) {
			return -0.19f + 0.08f * clamp(coastHeight, 0f, 1f);
		}
		return piecewise(meanElevation, INLAND_MEAN_M, INLAND_MEAN_P);
	}

	/** Height above the sea, in blocks, below which land along the coastline is coast. */
	public static float coastHeight(WorldScale scale) {
		return Math.max(COAST_HEIGHT_BLOCKS, scale.surfaceY(COAST_HEIGHT) - scale.seaLevel());
	}

	/** How far inland the coast reaches, in meters, where the variant noise is {@code variant}. */
	public static float coastWidth(WorldScale scale, float variant) {
		float width = COAST_WIDTH_MIN + (COAST_WIDTH_MAX - COAST_WIDTH_MIN) * clamp((variant + 1f) * 0.5f, 0f, 1f);
		return Math.max(width, (float) (COAST_WIDTH_BLOCKS * scale.metersPerBlock()));
	}

	/** The widest {@link #coastWidth} at a scale, in meters. */
	public static float maxCoastWidth(WorldScale scale) {
		return Math.max(COAST_WIDTH_MAX, (float) (COAST_WIDTH_BLOCKS * scale.metersPerBlock()));
	}

	static float erosion(float relief) {
		return piecewise(relief, RELIEF_M, RELIEF_P);
	}

	/**
	 * Vanilla's peaks-and-valleys value in [-0.8, 1]. It stays clear of the river band below -0.85: rivers come from
	 * the terrain, not from the biome table.
	 */
	static float peaksAndValleys(float elevation, float meanElevation, float relief) {
		float z = (elevation - meanElevation) / Math.max(relief, 15f);
		return piecewise(z, PEAKS_Z, PEAKS_P);
	}

	/** Inverts vanilla's {@code PV = 1 - |3|w| - 2|} on its lower branch, with the sign taken from the variant noise. */
	static float weirdness(float peaksAndValleys, float variant) {
		float magnitude = (peaksAndValleys + 1f) / 3f;
		return variant >= 0f ? magnitude : -magnitude;
	}

	/** Depth below the surface (capped at {@link #DEPTH_CEILING} above the sea), in vanilla's units. */
	public static float depth(WorldScale scale, float surfaceY, int y) {
		float top = Math.min(surfaceY, scale.seaLevel() + DEPTH_CEILING);
		return (top - y) / DEPTH_SCALE;
	}

	static float piecewise(float x, float[] xs, float[] ys) {
		if (x <= xs[0]) {
			return ys[0];
		}
		for (int k = 1; k < xs.length; k++) {
			if (x <= xs[k]) {
				float t = (x - xs[k - 1]) / (xs[k] - xs[k - 1]);
				return ys[k - 1] + (ys[k] - ys[k - 1]) * t;
			}
		}
		return ys[ys.length - 1];
	}

	static float clamp(float v, float min, float max) {
		return v < min ? min : Math.min(v, max);
	}
}
