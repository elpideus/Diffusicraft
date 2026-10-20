package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;

/** The climate and terrain of a column, as {@link BiomeRefiner} reads them. Mutable, so each thread can reuse one. */
public final class BiomeClimate {
	/** √2: a sinusoidal annual cycle with standard deviation σ peaks at σ√2 from its mean. */
	private static final float AMPLITUDE = 1.4142135f;

	/** Annual mean temperature at the column's elevation, °C. */
	public float temperature;
	/** Standard deviation of the monthly mean temperatures, °C (WorldClim BIO4 / 100). */
	public float seasonality;
	/** Annual precipitation, mm. */
	public float precipitation;
	/** Coefficient of variation of the monthly precipitation, % (WorldClim BIO15). */
	public float precipitationSeasonality;
	/** Meters above sea level. */
	public float elevation;
	/** How rugged the land around is, in meters; see {@link TerrainField.Column#relief}. */
	public float relief;
	/** The variant noise, in [-1, 1]: where in a climate's patchwork the column lies. */
	public float variant;
	/** How far glaciers lowered ground at sea level, in meters; see {@link TerrainField.Column#iceCut}. */
	public float iceCut;

	public BiomeClimate set(TerrainField.Column column) {
		temperature = column.temperature;
		seasonality = column.temperatureSeasonality / 100f;
		precipitation = column.precipitation;
		precipitationSeasonality = column.precipitationSeasonality;
		elevation = column.elevation;
		relief = column.relief;
		variant = column.variant;
		iceCut = column.iceCut;
		return this;
	}

	/** Mean temperature of the warmest month, °C. */
	public float warmestMonth() {
		return temperature + AMPLITUDE * seasonality;
	}

	/** Mean temperature of the coldest month, °C. */
	public float coldestMonth() {
		return temperature - AMPLITUDE * seasonality;
	}

	/** Precipitation over the Köppen aridity threshold: below 0.5 is desert, below 1 steppe. */
	public float aridity() {
		return ClimateMapping.aridityRatio(temperature, seasonality, precipitation, precipitationSeasonality);
	}
}
