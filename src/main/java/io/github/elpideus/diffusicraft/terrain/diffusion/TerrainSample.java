package io.github.elpideus.diffusicraft.terrain.diffusion;

/**
 * Elevation and climate over the native-resolution pixel rectangle {@code [i0, i0+height) x [j0, j0+width)}.
 * Row {@code i} grows southwards (Minecraft +Z), column {@code j} eastwards (+X).
 *
 * <p>Climate follows the WorldClim bioclimatic variables the model was trained on.
 *
 * @param elevation meters above sea level, {@code [height][width]} row-major; negative is below sea level
 * @param climate   {@code null}, or {@link #CLIMATE_CHANNELS} planes of {@code [height][width]}:
 *                  {@link #TEMPERATURE} (annual mean, °C, corrected for the local elevation),
 *                  {@link #TEMPERATURE_SEASONALITY} (BIO4: monthly std × 100),
 *                  {@link #PRECIPITATION} (annual, mm), {@link #PRECIPITATION_SEASONALITY}
 *                  (BIO15: coefficient of variation, %) and {@link #LAPSE_RATE} (°C per meter, ≤ 0)
 */
public record TerrainSample(int i0, int j0, int height, int width, float[] elevation, float[] climate) {
	public static final int CLIMATE_CHANNELS = 5;
	public static final int TEMPERATURE = 0;
	public static final int TEMPERATURE_SEASONALITY = 1;
	public static final int PRECIPITATION = 2;
	public static final int PRECIPITATION_SEASONALITY = 3;
	public static final int LAPSE_RATE = 4;

	public float elevation(int i, int j) {
		return elevation[(i - i0) * width + (j - j0)];
	}

	public float climate(int channel, int i, int j) {
		return climate[(channel * height + (i - i0)) * width + (j - j0)];
	}
}
