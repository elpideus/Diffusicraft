package io.github.elpideus.diffusicraft.worldgen.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.elpideus.diffusicraft.terrain.diffusion.TerrainSample;
import io.github.elpideus.diffusicraft.worldgen.FormulaSource;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSource;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoughGroundTest {
	private static final WorldScale SCALE = WorldScale.DEFAULT;
	private static final double EDGE = TerrainField.TILE + 0.5;

	/** Low, rolling land under a coarse map that stands far over it, as the mean of a mountain range does over a fjord. */
	private static final class Plateau implements TerrainSource {
		final AtomicInteger samples = new AtomicInteger();
		private final FormulaSource fine = new FormulaSource((i, j) ->
			(float) (100 + 50 * Math.sin(i / 9.0) * Math.cos(j / 13.0)));
		private final FormulaSource coarse = new FormulaSource((i, j) -> 1200f);

		@Override
		public TerrainSample sample(int i0, int j0, int i1, int j1) {
			samples.incrementAndGet();
			return fine.sample(i0, j0, i1, j1);
		}

		@Override
		public int coarseScale() {
			return coarse.coarseScale();
		}

		@Override
		public float[] coarse(int ci0, int cj0, int ci1, int cj1) {
			return coarse.coarse(ci0, cj0, ci1, cj1);
		}
	}

	private final Plateau source = new Plateau();
	private final TerrainField field = new TerrainField(1, source);

	private static RoughGround ground(TerrainField field) {
		return new RoughGround(field, SCALE, 0, 0, 4 * TerrainField.TILE, 4 * TerrainField.TILE);
	}

	private static int block(double pixel) {
		return (int) Math.floor(pixel / SCALE.pixelsPerBlock());
	}

	private float surfaceY(RoughGround ground, double i, double j) {
		return ground.surfaceY(block(j), block(i));
	}

	@Test
	void isTheTerrainOnTheTilesComputed() {
		field.elevation(10, 10);
		RoughGround ground = ground(field);
		int x = block(120), z = block(130);
		assertEquals(field.surface(SCALE).shore(x, z), ground.surfaceY(x, z), 1e-3f);
	}

	@Test
	void meetsATileComputedAtItsEdge() {
		field.elevation(10, 10);
		RoughGround ground = ground(field);
		int z0 = block(EDGE - 2), z1 = block(EDGE + 2);
		int x = block(120);
		float last = ground.surfaceY(x, z0);
		for (int z = z0 + 1; z <= z1; z++) {
			float y = ground.surfaceY(x, z);
			assertTrue(Math.abs(y - last) < 3, "a step of " + (y - last) + " blocks at z = " + z);
			last = y;
		}
	}

	@Test
	void reachesTheCoarseMapAtTheEndOfTheRelief() {
		field.elevation(10, 10);
		RoughGround ground = ground(field);
		double i = EDGE + RoughGround.REACH + 1, j = 120;
		assertEquals(SCALE.surfaceY(field.coarseElevation(i, j)), surfaceY(ground, i, j), 1e-3f);
		float halfway = surfaceY(ground, EDGE + RoughGround.REACH / 2.0, j);
		assertTrue(halfway > surfaceY(ground, EDGE - 1, j) && halfway < surfaceY(ground, i, j),
			"halfway out it stands at y = " + halfway);
	}

	@Test
	void isOneSurfaceWhicheverGridSamplesIt() {
		field.elevation(10, 10);
		field.elevation(10, TerrainField.TILE + 10);
		RoughGround first = ground(field);
		RoughGround second = new RoughGround(field, SCALE, 200, 200, 900, 900);
		for (double i : new double[] {EDGE + 1, EDGE + 7, EDGE + 40}) {
			for (double j : new double[] {230, EDGE, 300}) {
				assertEquals(surfaceY(first, i, j), surfaceY(second, i, j), 1e-3f);
			}
		}
	}

	@Test
	void runsNoModelsForTheTilesMissing() {
		field.elevation(10, 10);
		RoughGround ground = ground(field);
		int samples = source.samples.get();
		for (double i = 20; i < 4 * TerrainField.TILE; i += 11) {
			for (double j = 20; j < 4 * TerrainField.TILE; j += 11) {
				surfaceY(ground, i, j);
			}
		}
		assertEquals(samples, source.samples.get());
	}

	@Test
	void saysWhenTheTerrainAroundAGridHasChanged() {
		long before = RoughGround.computed(field, 0, 0, 100, 100);
		field.elevation(10, 10);
		assertNotEquals(before, RoughGround.computed(field, 0, 0, 100, 100));
	}
}
