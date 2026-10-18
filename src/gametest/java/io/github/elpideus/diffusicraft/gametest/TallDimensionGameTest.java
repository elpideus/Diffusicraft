package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.height.SharedContainerData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Builds in a dimension 32,736 blocks tall (Y from −16,368 to 16,367), the most that extended height allows, and checks
 * everything vanilla's 12-bit Y would break: block packets both ways, chunk packets over 2 MiB, light, heightmaps, and
 * section Y beyond a byte in saves. Screenshots go to {@code build/run/clientGameTest/screenshots}.
 *
 * <p>The dimension comes from this mod's data pack ({@code data/diffusicraft-gametest/dimension}): a flat world with
 * bedrock and deepslate at the very bottom, and nothing else.
 */
public class TallDimensionGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");
	private static final ResourceKey<Level> TALL = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("diffusicraft-gametest", "tall"));
	private static final int MIN_Y = -16368;
	private static final int MAX_Y = 16367;
	/** Where the player stands near the top and near the bottom. Both are far outside a signed byte of sections. */
	private static final BlockPos TOP = new BlockPos(0, 16000, 0);
	private static final BlockPos BOTTOM = new BlockPos(0, -16000, 0);
	/** A chunk with 16 kinds of block in every section: 4 bits per block, over 4 MiB of chunk data. */
	private static final ChunkPos DENSE = new ChunkPos(64, 0);
	private static final Block[] PATTERN = {
		Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE,
		Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.COBBLESTONE,
		Blocks.MOSSY_COBBLESTONE, Blocks.BRICKS,
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.renderDistance().set(4));
		check(BlockPos.MAX_HORIZONTAL_COORDINATE == (1 << 23) - 1, "block positions pack 24 horizontal bits, not 26");
		check(DimensionType.MIN_Y == MIN_Y && DimensionType.MAX_Y == MAX_Y,
			"dimension types allow Y " + DimensionType.MIN_Y + " to " + DimensionType.MAX_Y);
		check(BlockPos.of(BlockPos.asLong(-1, MAX_Y, 1)).equals(new BlockPos(-1, MAX_Y, 1)), "block positions pack 16-bit Y");

		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			save = world.getWorldSave();
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksRender();
			LOGGER.info("Overworld: heap {} MiB", usedHeapMiB());
			server.runOnServer(TallDimensionGameTest::build);
			server.runCommand("gamemode creative @a");

			// The top: blocks and light come from chunk packets, then from block updates in the tall wire layout.
			enter(context, world, TOP, -45, 30);
			check(context.computeOnClient(mc -> mc.level.getMinY() == MIN_Y && mc.level.getHeight() == MAX_Y - MIN_Y + 1),
				"the client sees the tall dimension");
			checkBlock(context, TOP.below(), Blocks.GOLD_BLOCK);
			checkBlock(context, new BlockPos(4, MAX_Y, 4), Blocks.EMERALD_BLOCK);
			server.runOnServer(s -> {
				ServerLevel level = s.getLevel(TALL);
				level.setBlock(TOP.offset(1, 3, 1), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(BOTTOM.offset(-1, 3, -1), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
			});
			world.getConnection().waitForClientboundPackets();
			checkBlock(context, TOP.offset(1, 3, 1), Blocks.GLOWSTONE);
			checkBlock(context, BOTTOM.offset(-1, 3, -1), Blocks.SEA_LANTERN);
			// Where vanilla's 12-bit Y would have put them.
			checkBlock(context, new BlockPos(1, (TOP.getY() + 3) << 20 >> 20, 1), Blocks.AIR);
			checkLight(context, TOP.offset(2, 1, 2), LightLayer.BLOCK, 14);
			checkLight(context, TOP.offset(0, 10, 0), LightLayer.SKY, 15);
			server.runOnServer(s -> {
				ServerLevel level = s.getLevel(TALL);
				check(level.getHeight(Heightmap.Types.WORLD_SURFACE, 4, 4) == MAX_Y + 1, "heightmap at the top of the world");
				check(level.getHeight(Heightmap.Types.WORLD_SURFACE, 1, 1) == TOP.getY() + 4, "heightmap after a block update");
			});
			check(context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, 4, 4)) == MAX_Y + 1,
				"client heightmap at the top of the world");
			checkMemory(context, server);

			// The server reads positions from the client too: break a platform block next to the player.
			BlockPos broken = TOP.offset(1, -1, 0);
			context.runOnClient(mc -> mc.gameMode.startDestroyBlock(broken, Direction.UP));
			world.getConnection().waitForServerboundPackets();
			check(server.computeOnServer(s -> s.getLevel(TALL).getBlockState(broken).isAir()), "the server broke the block the client clicked");
			context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
			screenshot(context, "tall-top");

			// The bottom.
			enter(context, world, BOTTOM, 135, 20);
			checkBlock(context, BOTTOM.below(), Blocks.GOLD_BLOCK);
			checkLight(context, BOTTOM.offset(-2, 1, -2), LightLayer.BLOCK, 14);
			screenshot(context, "tall-bottom");

			// A chunk packet over 2 MiB, which vanilla rejects.
			int bytes = server.computeOnServer(s -> new ClientboundLevelChunkPacketData(s.getLevel(TALL).getChunk(DENSE.x(), DENSE.z())).getReadBuffer().readableBytes());
			LOGGER.info("Dense chunk: {} KiB of chunk data", bytes >> 10);
			check(bytes > 2 << 20, "the dense chunk needs more than vanilla's 2 MiB");
			server.runCommand("gamemode spectator @a");
			enter(context, world, new BlockPos(DENSE.getMiddleBlockX() + 20, 16340, DENSE.getMiddleBlockZ()), 90, 25);
			checkDense(context);
			screenshot(context, "tall-dense");
			server.runOnServer(TallDimensionGameTest::measureSaving);
		}

		// Section Y beyond a byte in the save, and the dense chunk on disk.
		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runOnServer(s -> {
				ServerLevel level = s.getLevel(TALL);
				check(level.getBlockState(TOP.below()).is(Blocks.GOLD_BLOCK), "the top platform was saved");
				check(level.getBlockState(TOP.offset(1, 3, 1)).is(Blocks.GLOWSTONE), "the top block update was saved");
				check(level.getBlockState(TOP.offset(1, -1, 0)).isAir(), "the broken block was saved");
				check(level.getBlockState(BOTTOM.offset(-1, 3, -1)).is(Blocks.SEA_LANTERN), "the bottom block update was saved");
				check(level.getBlockState(new BlockPos(4, MAX_Y, 4)).is(Blocks.EMERALD_BLOCK), "the block at the top of the world was saved");
				LevelChunk dense = level.getChunk(DENSE.x(), DENSE.z());
				LevelChunkSection[] sections = dense.getSections();
				for (int i = 0; i < sections.length; i++) {
					checkPattern(sections[i], i);
				}
			});
			checkDense(context);
			LOGGER.info("Tall dimension: saved and reloaded");
		}
	}

	/** Platforms with a light at the top and bottom, a block at the very top, and the dense chunk. */
	private static void build(MinecraftServer server) {
		ServerLevel level = server.getLevel(TALL);
		check(level != null, "the tall dimension is loaded");
		check(level.getMinY() == MIN_Y && level.getHeight() == MAX_Y - MIN_Y + 1, "the tall dimension is " + level.getHeight() + " blocks tall");
		for (BlockPos centre : new BlockPos[] {TOP, BOTTOM}) {
			for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-3, -1, -3), centre.offset(3, -1, 3))) {
				level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		level.setBlock(TOP.offset(2, 0, 2), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(BOTTOM.offset(-2, 0, -2), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(new BlockPos(4, MAX_Y, 4), Blocks.EMERALD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

		long start = System.nanoTime();
		LevelChunk dense = level.getChunk(DENSE.x(), DENSE.z());
		LevelChunkSection[] sections = dense.getSections();
		for (int i = 0; i < sections.length; i++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						sections[i].setBlockState(x, y, z, pattern(x, i * 16 + y, z), false);
					}
				}
			}
		}
		Heightmap.primeHeightmaps(dense, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING,
			Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR));
		dense.markUnsaved();
		LOGGER.info("Dense chunk: {} sections filled in {} ms", sections.length, (System.nanoTime() - start) / 1_000_000);
	}

	/** How long a chunk takes to serialise for saving, tall against vanilla height. */
	private static void measureSaving(MinecraftServer server) {
		for (ResourceKey<Level> dimension : List.of(Level.OVERWORLD, TALL)) {
			ServerLevel level = server.getLevel(dimension);
			for (ChunkPos pos : new ChunkPos[] {new ChunkPos(0, 0), DENSE}) {
				LevelChunk chunk = level.getChunk(pos.x(), pos.z());
				SerializableChunkData.copyOf(level, chunk).write();
				long start = System.nanoTime();
				CompoundTag tag = null;
				for (int i = 0; i < 5; i++) {
					tag = SerializableChunkData.copyOf(level, chunk).write();
				}
				LOGGER.info("Saving chunk {} of {}: {} ms, {} KiB of NBT (in-memory estimate)", pos, dimension.identifier(),
					(System.nanoTime() - start) / 5_000_000, tag.sizeInBytes() >> 10);
			}
		}
	}

	private static BlockState pattern(int x, int y, int z) {
		return PATTERN[(x + y + z) & 15].defaultBlockState();
	}

	private static void checkPattern(LevelChunkSection section, int index) {
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (section.getBlockState(x, y, z) != pattern(x, index * 16 + y, z)) {
						throw new AssertionError("Dense chunk: section " + index + " differs at " + x + " " + y + " " + z);
					}
				}
			}
		}
	}

	private static void checkDense(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			LevelChunk dense = mc.level.getChunk(DENSE.x(), DENSE.z());
			LevelChunkSection[] sections = dense.getSections();
			check(sections.length == (MAX_Y - MIN_Y + 1) / 16, "the client has every section of the dense chunk");
			for (int i = 0; i < sections.length; i++) {
				checkPattern(sections[i], i);
			}
		});
	}

	/** Teleports the player into the tall dimension and waits until everything around has rendered. */
	private static void enter(ClientGameTestContext context, TestSingleplayerContext world, BlockPos feet, float yaw, float pitch) {
		long start = System.nanoTime();
		world.getServer().runCommand("execute in " + TALL.identifier() + " run tp @a " + (feet.getX() + 0.5) + " " + feet.getY() + " "
			+ (feet.getZ() + 0.5) + " " + yaw + " " + pitch);
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == TALL && mc.player.blockPosition().closerThan(feet, 2));
		world.getConnection().waitForChunksRender();
		LOGGER.info("Tall dimension: {} loaded and rendered in {} ms (heap {} MiB)", feet,
			(System.nanoTime() - start) / 1_000_000, usedHeapMiB());
	}

	/** Empty sections share their data on both sides. */
	private static void checkMemory(ClientGameTestContext context, TestServerContext server) {
		int[] onServer = server.computeOnServer(s -> {
			ServerLevel level = s.getLevel(TALL);
			return countShared((x, z) -> level.getChunkSource().getChunkNow(x, z));
		});
		int[] onClient = context.computeOnClient(mc ->
			countShared((x, z) -> mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)));
		LOGGER.info("Tall dimension: {} of {} sections share their data on the server, {} of {} on the client",
			onServer[0], onServer[1], onClient[0], onClient[1]);
		check(onServer[0] > onServer[1] * 0.99 && onClient[0] > onClient[1] * 0.99, "empty sections share their data");
	}

	/** Sections whose blocks and biomes are both shared, and all sections, in the chunks around the origin. */
	private static int[] countShared(BiFunction<Integer, Integer, LevelChunk> chunks) {
		int[] counts = new int[2];
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				LevelChunk chunk = chunks.apply(x, z);
				if (chunk != null) {
					for (LevelChunkSection section : chunk.getSections()) {
						if (((SharedContainerData.Container) section.getStates()).diffusicraft$isShared()
							&& ((SharedContainerData.Container) section.getBiomes()).diffusicraft$isShared()) {
							counts[0]++;
						}
						counts[1]++;
					}
				}
			}
		}
		return counts;
	}

	private static void checkBlock(ClientGameTestContext context, BlockPos pos, Block expected) {
		Block actual = context.computeOnClient(mc -> mc.level.getBlockState(pos).getBlock());
		check(actual == expected, "client block at " + pos.toShortString() + " is " + actual + ", expected " + expected);
	}

	private static void checkLight(ClientGameTestContext context, BlockPos pos, LightLayer layer, int expected) {
		context.waitFor(mc -> mc.level.getBrightness(layer, pos) == expected, 100);
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		context.waitTicks(10);
		LOGGER.info("Screenshot: {}", context.takeScreenshot("diffusicraft-" + name).toAbsolutePath());
	}

	/** Heap in use after a collection, so that garbage does not count. */
	private static long usedHeapMiB() {
		System.gc();
		Runtime runtime = Runtime.getRuntime();
		return (runtime.totalMemory() - runtime.freeMemory()) >> 20;
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Tall dimension: " + what);
		}
	}
}
