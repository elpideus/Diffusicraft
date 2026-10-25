package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;

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
	/** Mean elevation of the land around, in meters; see {@link TerrainField.Column#meanElevation}. */
	public float meanElevation;
	/** How rugged the land around is, in meters; see {@link TerrainField.Column#relief}. */
	public float relief;
	/** Meters above the low ground around; see {@link TerrainField.Column#aboveLow}. */
	public float aboveLow;
	/** The variant noise, in [-1, 1]: where in a climate's patchwork the column lies. */
	public float variant;
	/** How far glaciers lowered ground at sea level, in meters; see {@link TerrainField.Column#iceCut}. */
	public float iceCut;
	/** The draw of the {@link SubBiomes sub-biome} patch the column lies in, in [0, 1). */
	public float patch;
	/** How far inside that patch the column lies: above zero inside, zero or less outside every patch. */
	public float patchDepth;
	/** Whether the ground under the column has sulfur caves in it; see {@link Geology#sulfurous}. */
	public boolean sulfurous;
	/** Whether the ground under the column holds beds of salt; see {@link Geology#evaporite}. */
	public boolean saline;

	public BiomeClimate set(TerrainField.Column column) {
		temperature = column.temperature;
		seasonality = column.temperatureSeasonality / 100f;
		precipitation = column.precipitation;
		precipitationSeasonality = column.precipitationSeasonality;
		elevation = column.elevation;
		meanElevation = column.meanElevation;
		relief = column.relief;
		aboveLow = column.aboveLow;
		variant = column.variant;
		iceCut = column.iceCut;
		patch = 0f;
		patchDepth = 0f;
		sulfurous = false;
		saline = false;
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

	/**
	 * Precipitation over Köppen's aridity threshold taken at the warmest month's temperature rather than the year's. The
	 * year's mean hides a continental summer's heat, when the trees need the water: Köppen calls Astana humid, and
	 * steppe grows there. Where the summers are warm, below 0.7 is steppe and below 1 forest-steppe.
	 */
	public float summerMoisture() {
		return precipitation / Math.max(20f * warmestMonth() + 140f, 140f);
	}
}
