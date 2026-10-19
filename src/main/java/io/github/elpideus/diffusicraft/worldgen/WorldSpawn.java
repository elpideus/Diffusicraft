package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.mixin.PlayerSpawnFinderAccessor;
import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.terrain.geography.SpawnKind;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Places the world spawn of a Diffusicraft world in the kind of place the seed drew ({@link SpawnKind}), in place of
 * vanilla's search for the best climate within a few thousand blocks of the origin. {@link SpawnSearch} picks the
 * column; the spawn is the nearest column within {@value #RADIUS} blocks of it where a player can stand, as vanilla
 * judges it, or else the first such chunk of vanilla's own spiral around it.
 *
 * <p>For a spawn in an Ancient Grand Hollow it takes the nearest one under the land within {@value #HOLLOW_CELLS}
 * cells of the origin, and stands the player on its floor, between the lake in the middle and the wall. The bonus
 * chest goes beside the player there. As long as the world spawn lies underground, players without a bed respawn at
 * it rather than on the surface over it.
 */
public final class WorldSpawn {
	/** Blocks around the column {@link SpawnSearch} picks within which the spawn is looked for. */
	private static final int RADIUS = 48;
	/** Cells of the Ancient Grand Hollows around the origin's in which one is looked for. */
	private static final int HOLLOW_CELLS = 4;
	/** Meters of land over the middle of a Hollow for it to count as under the land. */
	private static final float LAND_OVER_HOLLOW = 50;
	/** Blocks around a spot on a Hollow's floor within which a place to stand is looked for. */
	private static final int FLOOR_RADIUS = 12;
	/** The floor is looked for this far out from the middle of the Hollow, as a share of its radius. */
	private static final double FLOOR_REACH = 0.75;
	/** A world spawn with ground this many blocks over it is underground. */
	private static final int UNDERGROUND = 16;
	/** Chunks either way of vanilla's spiral. */
	private static final int SPIRAL = 5;

	private WorldSpawn() {
	}

	/**
	 * Places the spawn of a new Diffusicraft world and its bonus chest.
	 *
	 * @return whether the world is Diffusicraft's; vanilla places the spawn of any other
	 */
	public static boolean place(ServerLevel level, ServerLevelData data, boolean bonusChest, LevelLoadListener listener) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
			return false;
		}
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale == null) {
			return false;
		}
		RandomState state = level.getChunkSource().randomState();
		TerrainField field = TerrainFields.forState(state);
		SpawnKind kind = field.spawnKind();
		listener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, 0);

		BlockPos spawn = kind == SpawnKind.HOLLOW ? inHollow(level, field, scale, settings, state, listener) : null;
		boolean underground = spawn != null;
		if (spawn == null) {
			SpawnSearch.Target target = SpawnSearch.find(field, scale, kind);
			listener.updateFocus(level.dimension(), ChunkPos.containing(new BlockPos(target.x(), 0, target.z())));
			spawn = onSurface(level, target.x(), target.z());
		}
		data.setSpawn(LevelData.RespawnData.of(level.dimension(), spawn, 0f, 0f));

		if (bonusChest) {
			BlockPos at = spawn;
			if (underground) {
				chestBeside(level, at);
			} else {
				level.registryAccess().lookup(Registries.FEATURE)
					.flatMap(registry -> registry.get(MiscOverworldFeatures.BONUS_CHEST))
					.ifPresent(feature -> feature.value().place(level, generator, level.getRandom(), at));
			}
		}
		listener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);
		return true;
	}

	/** Whether a spawn suggestion is the world spawn of a Diffusicraft world, which may lie underground. */
	public static boolean isWorldSpawn(ServerLevel level, BlockPos suggestion) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
			|| !DiffusicraftWorldgen.isDiffusicraft(generator.generatorSettings().value())) {
			return false;
		}
		LevelData.RespawnData respawn = level.getRespawnData();
		return respawn.dimension().equals(level.dimension()) && respawn.pos().equals(suggestion);
	}

	/**
	 * Where a player without a bed respawns at the world spawn ({@link #isWorldSpawn}), if it lies underground: on the
	 * ground at the spawn, as vanilla does in adventure mode. {@code null} on the surface, where vanilla spreads the
	 * players around it. The chunk of the spawn must be loaded.
	 */
	public static @Nullable Vec3 underground(ServerLevel level, BlockPos suggestion) {
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, suggestion.getX(), suggestion.getZ());
		if (ground <= suggestion.getY() + UNDERGROUND) {
			return null;
		}
		return PlayerSpawnFinderAccessor.diffusicraft$fixupSpawnHeight(level, suggestion);
	}

	/** The nearest column to (x, z) where a player can stand on the surface. */
	private static BlockPos onSurface(ServerLevel level, int x, int z) {
		for (int r = 0; r <= RADIUS; r++) {
			for (int dz = -r; dz <= r; dz++) {
				int step = Math.abs(dz) == r ? 1 : 2 * r;
				for (int dx = -r; dx <= r; dx += Math.max(1, step)) {
					BlockPos pos = PlayerSpawnFinderAccessor.diffusicraft$levelRespawnPos(level, x + dx, z + dz);
					if (pos != null) {
						return pos;
					}
				}
			}
		}
		// Vanilla's spiral of chunks around the column.
		ChunkPos centre = ChunkPos.containing(new BlockPos(x, 0, z));
		int cx = 0, cz = 0, dcx = 0, dcz = -1;
		for (int i = 0; i < Mth.square(2 * SPIRAL + 1); i++) {
			if (Math.abs(cx) <= SPIRAL && Math.abs(cz) <= SPIRAL) {
				BlockPos pos = PlayerSpawnFinder.getSpawnPosInChunk(level, new ChunkPos(centre.x() + cx, centre.z() + cz));
				if (pos != null) {
					return pos;
				}
			}
			if (cx == cz || cx < 0 && cx == -cz || cx > 0 && cx == 1 - cz) {
				int t = dcx;
				dcx = -dcz;
				dcz = t;
			}
			cx += dcx;
			cz += dcz;
		}
		return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
	}

	/** A place to stand on the floor of the nearest Ancient Grand Hollow under the land, or {@code null}. */
	private static @Nullable BlockPos inHollow(ServerLevel level, TerrainField field, WorldScale scale,
											   NoiseGeneratorSettings settings, RandomState state,
											   LevelLoadListener listener) {
		long seed = ((RandomStateAccessor) (Object) state).diffusicraft$random().fromHashOf(HollowsFunction.SEED_ID)
			.nextLong();
		Hollows hollows = Hollows.of(seed, settings.noiseSettings().minY());
		for (Hollows.Chamber chamber : hollows.chambers(Hollows.Kind.ANCIENT_GRAND_HOLLOW, 0, 0, HOLLOW_CELLS)) {
			int cx = (int) Math.floor(chamber.x()), cz = (int) Math.floor(chamber.z());
			if (field.coarseElevation(scale.toPixel(cz), scale.toPixel(cx)) < LAND_OVER_HOLLOW) {
				continue;
			}
			// Out along its axes, where the floor is flat: the basin takes the middle.
			double[][] ways = {
				{chamber.cos() * chamber.rx(), chamber.sin() * chamber.rx()},
				{-chamber.cos() * chamber.rx(), -chamber.sin() * chamber.rx()},
				{-chamber.sin() * chamber.rz(), chamber.cos() * chamber.rz()},
				{chamber.sin() * chamber.rz(), -chamber.cos() * chamber.rz()},
			};
			for (double[] way : ways) {
				int x = (int) Math.floor(chamber.x() + FLOOR_REACH * way[0]);
				int z = (int) Math.floor(chamber.z() + FLOOR_REACH * way[1]);
				listener.updateFocus(level.dimension(), ChunkPos.containing(new BlockPos(x, 0, z)));
				BlockPos floor = floorNear(level, chamber, x, z);
				if (floor != null) {
					return floor;
				}
			}
		}
		return null;
	}

	/** The nearest column to (x, z) where the open middle of a chamber comes down to a floor to stand on. */
	private static @Nullable BlockPos floorNear(ServerLevel level, Hollows.Chamber chamber, int x, int z) {
		int top = (int) Math.floor(chamber.y());
		int bottom = (int) Math.floor(chamber.y() - chamber.down() - chamber.basin()) - 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int r = 0; r <= FLOOR_RADIUS; r++) {
			for (int dz = -r; dz <= r; dz++) {
				int step = Math.abs(dz) == r ? 1 : 2 * r;
				for (int dx = -r; dx <= r; dx += Math.max(1, step)) {
					BlockPos floor = floorIn(level, pos, x + dx, z + dz, top, bottom);
					if (floor != null) {
						return floor;
					}
				}
			}
		}
		return null;
	}

	/** The ground under the open air at height {@code top} of a column, if it is dry and two blocks of air are over it. */
	private static @Nullable BlockPos floorIn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int top,
											  int bottom) {
		if (!open(level, pos.set(x, top, z)) || !open(level, pos.set(x, top - 1, z))) {
			return null;
		}
		for (int y = top - 2; y > bottom; y--) {
			pos.set(x, y, z);
			if (open(level, pos)) {
				continue;
			}
			BlockState ground = level.getBlockState(pos);
			if (!ground.getFluidState().isEmpty() || !Block.isFaceFull(ground.getCollisionShape(level, pos), Direction.UP)) {
				return null;
			}
			return new BlockPos(x, y + 1, z);
		}
		return null;
	}

	/** Whether a player could stand in a block: nothing solid and no fluid. */
	private static boolean open(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	/** The bonus chest on the floor beside the spawn, with torches round it, as vanilla places it on the surface. */
	private static void chestBeside(ServerLevel level, BlockPos spawn) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos pos = spawn.relative(direction);
			BlockState below = level.getBlockState(pos.below());
			if (!open(level, pos) || !Block.isFaceFull(below.getCollisionShape(level, pos.below()), Direction.UP)) {
				continue;
			}
			level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
			RandomizableContainer.setBlockEntityLootTable(level, level.getRandom(), pos, BuiltInLootTables.SPAWN_BONUS_CHEST);
			BlockState torch = Blocks.TORCH.defaultBlockState();
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos torchPos = pos.relative(side);
				if (!torchPos.equals(spawn) && level.isEmptyBlock(torchPos) && torch.canSurvive(level, torchPos)) {
					level.setBlock(torchPos, torch, Block.UPDATE_CLIENTS);
				}
			}
			return;
		}
	}
}
