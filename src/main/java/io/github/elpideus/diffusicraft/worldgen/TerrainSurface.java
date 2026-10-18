package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.Gullies;

/**
 * The height of the ground, in blocks: the terrain's elevation through the world's {@link WorldScale}, with the
 * {@link Gullies} cut into its slopes. The density, the materials, the rivers, the Hollows, the ceiling of each chunk
 * and the levels of detail all read the ground here, so they agree on where it is. The climate and the biomes keep the
 * smooth elevation: gullies are too small to change them.
 *
 * <p>Points are given in blocks, block {@code x} spanning {@code [x, x + 1)}, as {@link Erosion.Surface} has them.
 * Get one from {@link TerrainField#surface}; it holds no state of its own and is safe on any thread.
 */
public final class TerrainSurface implements Erosion.Surface {
	private final TerrainField field;
	private final WorldScale scale;
	private final Gullies gullies;
	private final double pixelsPerBlock;
	private final double lowest;
	private final double highest;

	TerrainSurface(TerrainField field, WorldScale scale) {
		this.field = field;
		this.scale = scale;
		this.gullies = new Gullies(field.seed(), scale.metersPerBlock());
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
		double depth = gullies.depth(x, z, gradient[1] * rate, gradient[0] * rate);
		return Math.clamp(scale.surfaceY(elevation) + depth, lowest, highest);
	}
}
