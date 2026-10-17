/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seed-independent real-world distributions ({@code pipeline_data.json}) that the synthetic conditioning
 * map is quantile-matched against: elevation, temperature, temperature seasonality, precipitation and
 * precipitation seasonality, derived from WorldClim/ETOPO by the Terrain Diffusion authors.
 */
public record ClimateQuantiles(float[][] dataQuantiles, float aTempStd, float bTempStd, float tempStdP1, float tempStdP99) {
	public static final String FILE_NAME = "pipeline_data.json";
	public static final int CHANNELS = 5;

	public static ClimateQuantiles load(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject data = new Gson().fromJson(reader, JsonObject.class);
			int quantiles = data.get("n_quantiles").getAsInt();
			JsonArray tables = data.getAsJsonArray("data_quantile_tables");
			float[][] dataQuantiles = new float[CHANNELS][quantiles];
			for (int ch = 0; ch < CHANNELS; ch++) {
				JsonArray table = tables.get(ch).getAsJsonArray();
				for (int i = 0; i < quantiles; i++) {
					dataQuantiles[ch][i] = table.get(i).getAsFloat();
				}
			}
			return new ClimateQuantiles(dataQuantiles,
				data.get("a_temp_std").getAsFloat(), data.get("b_temp_std").getAsFloat(),
				data.get("temp_std_p1").getAsFloat(), data.get("temp_std_p99").getAsFloat());
		} catch (RuntimeException e) {
			throw new IOException("Invalid pipeline data " + file + ": " + e.getMessage(), e);
		}
	}
}
