package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Fills the lakes of the Grand Hollows: the basins sunk into the middle of their floors ({@link Hollows#lakeAt}) fill
 * with water up to their brims, where {@link HollowBiomes#holdsLake} says the cavern holds one. It stands in front of
 * the aquifer of every noise chunk of a Diffusicraft world, which the noise fill and the carvers both ask what fills
 * open space, so a carver's tunnel through a lake bed floods too.
 */
public final class HollowLakes {
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

	private HollowLakes() {
	}

	/** The aquifer of a noise chunk with the lakes of the Hollows in front of it. */
	public static Aquifer aquifer(BiomeWorld world, Aquifer aquifer) {
		int top = world.hollows().highestLake();
		return new Aquifer() {
			private boolean lake;

			@Override
			public @Nullable BlockState computeSubstance(int x, int y, int z, double density) {
				lake = density <= 0 && y < top && CACHE.get().lake(world, x, y, z);
				return lake ? WATER : aquifer.computeSubstance(x, y, z, density);
			}

			@Override
			public boolean shouldScheduleFluidUpdate() {
				// A lake is still: its water stays where it is put, like that of vanilla's lakes.
				return !lake && aquifer.shouldScheduleFluidUpdate();
			}
		};
	}

	/** The columns of the chunk a thread fills, and whether the caverns it met lately hold lakes. */
	private static final class Cache {
		private static final int CAVERNS = 16;
		private final Hollows.Column[] columns = new Hollows.Column[256];
		private @Nullable Hollows owner;
		private final long[] ids = new long[CAVERNS];
		private final boolean[] known = new boolean[CAVERNS];
		private final boolean[] lakes = new boolean[CAVERNS];
		private final TerrainField.Column scratch = new TerrainField.Column();
		private final BiomeClimate climate = new BiomeClimate();

		boolean lake(BiomeWorld world, int x, int y, int z) {
			Hollows hollows = world.hollows();
			if (owner != hollows) {
				// Another world: forget the columns and caverns of the last.
				owner = hollows;
				for (int i = 0; i < columns.length; i++) {
					columns[i] = new Hollows.Column();
				}
				Arrays.fill(known, false);
			}
			Hollows.Column column = columns[(x & 15) | (z & 15) << 4];
			if (!column.isAt(x, z)) {
				TerrainSurface ground = world.field().surface(world.scale());
				hollows.column(x, z, (px, pz) -> (int) Math.floor(ground.y(px, pz)), column);
			}
			Hollows.Chamber chamber = hollows.lakeAt(column, y);
			if (chamber == null) {
				return false;
			}
			int slot = (int) (chamber.id() ^ chamber.id() >>> 32) & (CAVERNS - 1);
			if (!known[slot] || ids[slot] != chamber.id()) {
				HollowBiomes.climate(world, chamber, scratch, climate);
				lakes[slot] = HollowBiomes.holdsLake(HollowBiomes.pick(chamber.kind(), chamber.id(), climate), climate);
				ids[slot] = chamber.id();
				known[slot] = true;
			}
			return lakes[slot];
		}
	}
}
