package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Which of vanilla's water springs Diffusicraft worlds leave out: those that would spill out of a face under the open
 * sky and fall more than {@value #MAX_DROP} blocks onto dry ground. On the cliffs of a large world such a spring hangs
 * a thread of water down a wall of rock that stops in a puddle at its foot, where falls should run on as a river; the
 * falls Diffusicraft lays out itself ({@link RiverFallFeature}) are those of its rivers, which run on below them.
 * Springs in caves and under overhangs stay, as do those that fall into water or only a few blocks.
 */
public final class Springs {
	/** Most blocks a spring's water may fall freely onto dry ground. */
	static final int MAX_DROP = 8;
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/** The blocks around a spring. */
	interface World {
		/** Whether a block is empty, as the hole a spring's water leaves by. */
		boolean empty(int x, int y, int z);

		/** Whether a block holds water. */
		boolean water(int x, int y, int z);

		/** The lowest height of a column with nothing in it from there up. */
		int open(int x, int z);

		int minY();
	}

	private Springs() {
	}

	/** Whether a Diffusicraft world leaves out the water spring vanilla is about to place at {@code spring}. */
	public static boolean leftOut(WorldGenLevel level, BlockPos spring) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		if (world == null) {
			return false;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return fallsFar(new World() {
			@Override
			public boolean empty(int x, int y, int z) {
				return level.isEmptyBlock(pos.set(x, y, z));
			}

			@Override
			public boolean water(int x, int y, int z) {
				return level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER);
			}

			@Override
			public int open(int x, int z) {
				return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
			}

			@Override
			public int minY() {
				return level.getMinY();
			}
		}, spring.getX(), spring.getY(), spring.getZ());
	}

	/**
	 * Whether the water of a spring at {@code (x, y, z)} spills out of a hole beside it under the open sky and falls
	 * more than {@value #MAX_DROP} blocks onto anything but water.
	 */
	static boolean fallsFar(World world, int x, int y, int z) {
		for (int d = 0; d < 4; d++) {
			int hx = x + DX[d], hz = z + DZ[d];
			if (!world.empty(hx, y, hz) || world.open(hx, hz) > y) {
				continue;
			}
			int floor = y - 1;
			while (floor > world.minY() && world.empty(hx, floor, hz)) {
				floor--;
			}
			if (y - 1 - floor > MAX_DROP && !world.water(hx, floor, hz)) {
				return true;
			}
		}
		return false;
	}
}
