package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds in a Diffusicraft world of the default size (1:1), saves it and checks that its far terrain keeps what was
 * built ({@link LodStore}): a pillar of gold in the middle of the spawn's chunk, among the columns of the finest level
 * and of the level 16 blocks apart, and in the grids {@link LodGrid#sample} hands the far terrain. Opens the world
 * anew, takes the top off the pillar and checks the stored columns follow.
 *
 * <p>Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class LodCaptureGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int PILLAR = 40;
	private static final int CUT = 10;
	private static final BlockState GOLD = Blocks.GOLD_BLOCK.defaultBlockState();

	/** The highest block of a stored column and what it is. */
	private record Top(int y, @Nullable BlockState state) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the far terrain test");
			return;
		}
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);

		TestWorldSave save;
		BlockPos pillar;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, WorldScale.ULTRA);
			})
			.create()) {
			save = world.getWorldSave();
			pillar = world.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				ChunkPos chunk = ChunkPos.containing(server.getPlayerList().getPlayers().getFirst().blockPosition());
				int x = chunk.getMinBlockX() + 8;
				int z = chunk.getMinBlockZ() + 8;
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = ground; y < ground + PILLAR; y++) {
					level.setBlockAndUpdate(new BlockPos(x, y, z), GOLD);
				}
				return new BlockPos(x, ground + PILLAR - 1, z);
			});
			LOGGER.info("Built a pillar of gold up to {}", pillar);
			saved(context, world);
			expect(world, pillar, "after the first save");
		}

		LOGGER.info("Opening the world again");
		try (TestSingleplayerContext world = save.open()) {
			expect(world, pillar, "read back from disk");
			world.getServer().runOnServer(server -> {
				for (int y = pillar.getY() - CUT + 1; y <= pillar.getY(); y++) {
					server.overworld().setBlockAndUpdate(pillar.atY(y), Blocks.AIR.defaultBlockState());
				}
			});
			saved(context, world);
			expect(world, pillar.below(CUT), "with its top taken off");
		}
	}

	/**
	 * Lets the light settle and saves every chunk, as {@code /save-all flush} does on a dedicated server, which has
	 * the command.
	 */
	private static void saved(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitTicks(20);
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
	}

	/** Checks that the stored columns at the pillar and the far terrain's grids there top out at {@code top}. */
	private static void expect(TestSingleplayerContext world, BlockPos top, String when) {
		LodStore store = world.getServer().computeOnServer(server -> LodStore.of(server.overworld()));
		LodSampler sampler = world.getServer().computeOnServer(server -> LodSampler.of(server.overworld()));
		if (store == null || sampler == null) {
			throw new AssertionError("The overworld has no far terrain store");
		}
		int x = top.getX();
		int z = top.getZ();
		int pageX = Math.floorDiv(x, LodStore.PAGE);
		int pageZ = Math.floorDiv(z, LodStore.PAGE);
		LodStore.Page fine = store.page(0, pageX, pageZ).join();
		int column = (z - pageZ * LodStore.PAGE) * LodStore.PAGE + x - pageX * LodStore.PAGE;
		if (fine == null || !fine.has(column)) {
			throw new AssertionError("The finest level holds no column at the pillar " + when);
		}
		check("the finest level's column", top(fine.grid(), column, top.getY()), top, when);

		// 16 blocks apart, the middle of a chunk is a column: the pillar's is the first of a page of that level.
		int span = LodStore.PAGE * 16;
		LodStore.Page coarse = store.page(4, Math.floorDiv(x, span), Math.floorDiv(z, span)).join();
		int coarseColumn = Math.floorMod(z, span) / 16 * LodStore.PAGE + Math.floorMod(x, span) / 16;
		if (coarse == null || !coarse.has(coarseColumn)) {
			throw new AssertionError("The level 16 blocks apart holds no column at the pillar " + when);
		}
		check("the column 16 blocks apart", top(coarse.grid(), coarseColumn, top.getY()), top, when);

		// The grids the far terrain asks for: the pillar's chunk block by block, and four columns 16 blocks apart.
		LodGrid near = LodGrid.sample(sampler, x - 8, z - 8, 16, 1, Integer.MAX_VALUE);
		check("the sampled chunk", top(near, 8 * 16 + 8, top.getY()), top, when);
		LodGrid far = LodGrid.sample(sampler, x - 8, z - 8, 4, 16, Integer.MAX_VALUE);
		check("the sampled grid 16 blocks apart", top(far, 0, top.getY()), top, when);
		LOGGER.info("The far terrain shows the pillar up to y = {} {}", top.getY(), when);
	}

	private static void check(String what, Top found, BlockPos top, String when) {
		if (found.y() != top.getY() || found.state() != GOLD) {
			throw new AssertionError(what + " tops out with " + found.state() + " at y = " + found.y() + " " + when
				+ ", not with gold at y = " + top.getY());
		}
	}

	/** The highest block of a column from a little above {@code near} down to well below it. */
	private static Top top(LodGrid grid, int column, int near) {
		int bottom = near - 2 * PILLAR;
		BlockState[] states = new BlockState[3 * PILLAR];
		grid.voxels(column, bottom, 1, states, new byte[states.length]);
		for (int i = states.length - 1; i >= 0; i--) {
			if (states[i] != null && !states[i].isAir()) {
				return new Top(bottom + i, states[i]);
			}
		}
		return new Top(Integer.MIN_VALUE, null);
	}
}
