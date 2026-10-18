package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Gullies;

/**
 * The height of the ground, in blocks: the terrain's elevation through the world's {@link WorldScale}, with the
 * {@link Gullies} cut into its slopes. The density, the materials, the rivers, the Hollows, the ceiling of each chunk
 * and the levels of detail all read the ground here, so they agree on where it is. The climate and the biomes keep the
 * smooth elevation: gullies are too small to change them.
 *
 * <p>The gullies take their character from the rock under the slope, as the world's {@link Geology} has it, and from
 * how much soil and plants cover it, which the climate decides: it takes rain (by the same aridity the biomes use)
 * and warmth. Deserts, tundra and the ground above the tree line are bare.
 *
 * <p>Points are given in blocks, block {@code x} spanning {@code [x, x + 1)}, as {@link Erosion.Surface} has them.
 * Get one from {@link TerrainField#surface}; it holds no state of its own and is safe on any thread.
 */
public final class TerrainSurface implements Erosion.Surface {
	private final TerrainField field;
	private final WorldScale scale;
	private final Gullies gullies;
	private final Geology geology;
	private final double pixelsPerBlock;
	private final double lowest;
	private final double highest;

	TerrainSurface(TerrainField field, WorldScale scale) {
		this.field = field;
		this.scale = scale;
		this.gullies = new Gullies(field.seed(), scale.metersPerBlock());
		// Only the deep layers depend on the bottom of the world, and the hardness reads none of them.
		this.geology = new Geology(field.geologySeed(), 0, scale.metersPerBlock(), scale.verticalMetersPerBlock());
		this.pixelsPerBlock = scale.pixelsPerBlock();
		this.lowest = scale.minSurfaceY();
		this.highest = scale.maxSurfaceY();
	}

	/** The ground at the centre of column {@code (x, z)}. */
	public float y(int x, int z) {
		return (float) y(x + 0.5, z + 0.5);
	}

	@Override
	public double y(double x, double z) {
		double i = z * pixelsPerBlock, j = x * pixelsPerBlock;
		if (!gullies.any()) {
			return scale.surfaceY(field.elevation(i, j));
		}
		float[] gradient = new float[2];
		float elevation = field.elevation(i, j, gradient);
		// Meters per pixel to blocks per block.
		double rate = scale.surfaceRate(elevation) * pixelsPerBlock;
		double gx = gradient[1] * rate, gz = gradient[0] * rate;
		double y = scale.surfaceY(elevation);
		if (gullies.cuts(gx, gz)) {
			TerrainField.Column column = new TerrainField.Column();
			field.column(i, j, column);
			double hardness = geology.hardness(x, z, y, scale.surfaceY(column.meanElevation), column.meanElevation,
				column.relief);
			y += gullies.depth(x, z, gx, gz, hardness, cover(column.temperature, column.precipitation));
		}
		return Math.clamp(y, lowest, highest);
	}

	/**
	 * How much soil and plants cover the ground, from 0 to 1, at an annual mean temperature in °C and annual
	 * precipitation in mm: none in deserts or where the ground is frozen most of the year, full where it is humid and
	 * mild.
	 */
	static double cover(float temperature, float precipitation) {
		return GeoNoise.smoothstep(0.5, 1.5, ClimateMapping.aridityRatio(temperature, precipitation))
			* GeoNoise.smoothstep(-4, 4, temperature);
	}
}
