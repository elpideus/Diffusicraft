package io.github.elpideus.diffusicraft.worldgen.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * A level that structures are placed in for a far view, without any chunk: it keeps the blocks their pieces put, and
 * answers for the rest from the far view's ground, solid up to its top and water over it where the sea or a river
 * covers it. The structures read it as they read a chunk being generated, to sink their foundations to the ground, lay
 * their paths along it and turn them to bridges over water. Their entities, block entities and ticks are dropped.
 *
 * <p>Not thread-safe: each grid places its structures in a sketch of its own.
 */
final class StructureSketch implements WorldGenLevel {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();

	/** The far view's ground under a column: its top block, and the top block of the water over it, if any. */
	record Land(int ground, int water) {
		/** The land of a dry column. */
		static Land dry(int ground) {
			return new Land(ground, Integer.MIN_VALUE);
		}
	}

	/** Where the far view's ground lies. */
	@FunctionalInterface
	interface Ground {
		Land at(int x, int z);
	}

	/** The highest two blocks a structure put in a column that show from afar. */
	static final class Column {
		int topY = Integer.MIN_VALUE;
		@Nullable BlockState top;
		int belowY = Integer.MIN_VALUE;
		@Nullable BlockState below;

		private void add(int y, BlockState state) {
			if (y > topY) {
				belowY = topY;
				below = top;
				topY = y;
				top = state;
			} else if (y > belowY) {
				belowY = y;
				below = state;
			}
		}
	}

	private final ServerLevel level;
	private final Ground ground;
	private final BlockState rock;
	private final BiomeResolver biomes;
	private final BiomeManager biomeManager;
	private final RandomSource random = new XoroshiroRandomSource(0L);
	/** The blocks placed, by {@link BlockPos#asLong}. */
	private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
	/** The land of the columns asked about, by {@link ChunkPos#pack}, which packs any two ints. */
	private final Long2ObjectOpenHashMap<Land> lands = new Long2ObjectOpenHashMap<>();
	private @Nullable ChunkAccess placeholder;

	/** A sketch over {@code ground}, made of {@code rock}, among {@code biomes}. */
	StructureSketch(ServerLevel level, Ground ground, BlockState rock, BiomeResolver biomes) {
		this.level = level;
		this.ground = ground;
		this.rock = rock;
		this.biomes = biomes;
		this.biomeManager = new BiomeManager(biomes, BiomeManager.obfuscateSeed(level.getSeed()));
	}

	/** The far view's land under column {@code (x, z)}. */
	Land land(int x, int z) {
		long key = ChunkPos.pack(x, z);
		Land land = lands.get(key);
		if (land == null) {
			land = ground.at(x, z);
			lands.put(key, land);
		}
		return land;
	}

	/**
	 * The columns the structures put blocks in that show from afar, those that stop a player, by {@link ChunkPos#pack}
	 * of their block X and Z. The water in a farm or a fountain is too small to show.
	 */
	Long2ObjectMap<Column> columns() {
		Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
		for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
			BlockState state = entry.getValue();
			if (state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP)) {
				long pos = entry.getLongKey();
				columns.computeIfAbsent(ChunkPos.pack(BlockPos.getX(pos), BlockPos.getZ(pos)), key -> new Column())
					.add(BlockPos.getY(pos), state);
			}
		}
		return columns;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState placed = blocks.get(pos.asLong());
		if (placed != null) {
			return placed;
		}
		Land land = land(pos.getX(), pos.getZ());
		return pos.getY() <= land.ground() ? rock : pos.getY() <= land.water() ? WATER : AIR;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(pos));
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		blocks.put(pos.asLong(), state);
		return true;
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
		return setBlock(pos, getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL);
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropResources, @Nullable Entity breaker, int recursionLeft) {
		return !getBlockState(pos).isAir() && removeBlock(pos, false);
	}

	/** Over the ground, or over the water too but for the floor of the ocean, as the chunks' heightmaps have it. */
	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		Land land = land(x, z);
		boolean floor = type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG;
		return (floor ? land.ground() : Math.max(land.ground(), land.water())) + 1;
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
		return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	/**
	 * A chunk below the world, which structures mark the blocks they want shaped again in: it ignores them all, as
	 * nothing is shaped again in a sketch.
	 */
	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean loadOrGenerate) {
		if (placeholder == null) {
			placeholder = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY,
				LevelHeightAccessor.create(level.getMinY() - 16, 16), level.palettedContainerFactory(), null);
		}
		return placeholder;
	}

	@Override
	public boolean hasChunk(int x, int z) {
		return true;
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
		return biomes.getNoiseBiome(quartX, quartY, quartZ);
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
		return biomes.getNoiseBiome(quartX, quartY, quartZ);
	}

	@Override
	public BiomeManager getBiomeManager() {
		return biomeManager;
	}

	@Override
	public ServerLevel getLevel() {
		return level;
	}

	@Override
	public long getSeed() {
		return level.getSeed();
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
		return new DifficultyInstance(level.getDifficulty(), 0L, 0L, 0f);
	}

	@Override
	public RandomSource getRandom() {
		return random;
	}

	@Override
	public int getMinY() {
		return level.getMinY();
	}

	@Override
	public int getHeight() {
		return level.getHeight();
	}

	@Override
	public int getSeaLevel() {
		return level.getSeaLevel();
	}

	@Override
	public DimensionType dimensionType() {
		return level.dimensionType();
	}

	@Override
	public RegistryAccess registryAccess() {
		return level.registryAccess();
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return level.enabledFeatures();
	}

	@Override
	public EnvironmentAttributeReader environmentAttributes() {
		return level.environmentAttributes();
	}

	@Override
	public LevelData getLevelData() {
		return level.getLevelData();
	}

	@Override
	public MinecraftServer getServer() {
		return level.getServer();
	}

	@Override
	public ChunkSource getChunkSource() {
		return level.getChunkSource();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}

	@Override
	public WorldBorder getWorldBorder() {
		return level.getWorldBorder();
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Override
	public int getSkyDarken() {
		return 0;
	}

	@Override
	public long nextSubTickCount() {
		return 0L;
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) {
		return new ScheduledTick<>(type, pos, delay, priority, 0L);
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
		return new ScheduledTick<>(type, pos, delay, 0L);
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
		return this;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
		return List.of();
	}

	@Override
	public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector) {
		return List.of();
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb,
												  Predicate<? super T> selector) {
		return List.of();
	}

	@Override
	public List<? extends Player> players() {
		return List.of();
	}

	@Override
	public void playSound(@Nullable Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
						  float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
	}

	@Override
	public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
	}
}
