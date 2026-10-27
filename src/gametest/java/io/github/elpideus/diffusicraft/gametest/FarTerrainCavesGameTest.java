package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.Sights;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodMesher;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The far terrain seen from underground, where its caves go on from the chunks drawn. Digs a sealed tunnel
 * {@value #DEPTH} blocks under the spawn, running {@value #LENGTH} blocks south, past the
 * {@value FarTerrainGameTest#RENDER_DISTANCE} chunks drawn, saves it, and looks down it with night vision: the tunnel
 * has to go on in the far terrain, which keeps it from the saved chunks, unlit by the sky. Then views the Ancient Grand
 * Hollow nearest the spawn, or the Grand Hollow if there is none near, from its floor towards its far side, which the
 * far terrain has to carve as the chunks do. Saves to {@code build/run/clientGameTest/screenshots} each view with the
 * far terrain on and off, logs what a tile of far terrain over the cavern costs, and fails if the far terrain closes
 * the tunnel or the cavern.
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn it off.
 */
public class FarTerrainCavesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain caves test");
	/** How far under the ground the tunnel runs, how far south, and in how long pieces it is dug. */
	private static final int DEPTH = 40;
	private static final int LENGTH = 6 * 16 + 24;
	private static final int PIECE = 16;
	/** Every how many blocks the far terrain's columns are checked in the cavern: the coarsest step that carves it. */
	private static final int CAVERN_STEP = 8;
	/** How far out from the cavern's middle, as a share of its smaller radius, its columns are checked. */
	private static final double CAVERN_REACH = 0.6;
	/** The share of the columns checked that must open somewhere between the cavern's floor and its ceiling. */
	private static final double CAVERN_OPEN = 0.5;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FarTerrainGameTest.prepare(this, context)) {
			return;
		}
		try (TestSingleplayerContext world = GameTests.createAnyway(context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(FarTerrainGameTest.SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			}))) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			WorldGenerationGameTest.Probe probe = server.computeOnServer(s -> WorldGenerationGameTest.Probe.of(s.overworld()));
			VoxyGameTest.look(context, server);
			server.runCommand("gamerule advance_time false");
			server.runCommand("effect give @a minecraft:night_vision infinite 0 true");
			ServerLevel level = server.computeOnServer(s -> s.overworld());
			LodSampler sampler = Objects.requireNonNull(LodSampler.of(level), "Diffusicraft generates no overworld");

			BlockPos start = probe.spawn().atY(probe.spawnSurface() - DEPTH);
			int dug = dig(context, server, connection, start);
			server.runOnServer(s -> s.saveEverything(true, true, true));
			LodStore store = Objects.requireNonNull(sampler.store(), "The overworld has no store of far terrain");
			context.waitFor(mc -> store.backlog() == 0, 60 * 20);
			FarTerrainGameTest.view(context, server, connection, start.above(), 0f, 0f, "cave");
			takeOff(context, "cave");
			String tunnel = tunnel(sampler, start.south(dug - 2));

			server.runCommand("gamemode spectator @a");
			Sights.Found hollow = server.computeOnServer(s -> hollow(s.overworld(), probe.spawn()));
			Hollows.Chamber chamber = hollow.chamber();
			LOGGER.info("Viewing the {} at {}, {} by {} blocks across", chamber.kind(), hollow.pos().toShortString(),
				(int) (2 * chamber.rx()), (int) (2 * chamber.rz()));
			double dx = chamber.x() - hollow.pos().getX(), dz = chamber.z() - hollow.pos().getZ();
			float yaw = Math.hypot(dx, dz) < 8 ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
			FarTerrainGameTest.view(context, server, connection, hollow.pos(), yaw, 5f, "hollow");
			takeOff(context, "hollow");
			String cavern = cavern(sampler, chamber);
			cost(sampler, chamber);

			if (tunnel != null || cavern != null) {
				throw new AssertionError(String.join("; ", Stream.of(tunnel, cavern).filter(Objects::nonNull).toList()));
			}
		}
	}

	/** Takes the view once more with the far terrain off, to compare. */
	private static void takeOff(ClientGameTestContext context, String name) {
		Resources.get().updateConfig(config -> config.farTerrain = false);
		context.waitTicks(20);
		context.takeScreenshot("diffusicraft-far-terrain-" + name + "-off");
		Resources.get().updateConfig(config -> config.farTerrain = true);
	}

	/**
	 * Digs a tunnel three blocks wide and high south from {@code start}, its floor at {@code start}'s y, in a shell of
	 * stone that seals it from any cave or sea it crosses, and at its end, as far as the chunks loaded around the
	 * player reach.
	 *
	 * @return how many blocks south it runs
	 */
	private static int dig(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
							BlockPos start) {
		server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d 0 0", start.getX(), start.getY() + 1,
			start.getZ()));
		connection.waitForClientboundPackets();
		connection.waitForChunksRender();
		int x = start.getX(), y = start.getY();
		for (int from = -2; from < LENGTH; from += PIECE) {
			int to = Math.min(LENGTH, from + PIECE - 1);
			// A block further, which closes the tunnel until the next piece digs through it.
			server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 2, y - 1,
				start.getZ() + from, x + 2, y + 3, start.getZ() + to + 1));
			server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 1, y,
				start.getZ() + Math.max(from, 0), x + 1, y + 2, start.getZ() + to));
		}
		context.waitTicks(40);
		int dug = server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			for (int length = LENGTH; length > 0; length--) {
				BlockPos end = start.south(length);
				if (level.hasChunkAt(end) && level.getBlockState(end).isAir()) {
					return length;
				}
			}
			return 0;
		});
		LOGGER.info("Dug a tunnel from {} {} blocks south", start, dug);
		return dug;
	}

	/**
	 * What is wrong with the far terrain's column at {@code at}, the floor of the tunnel near its end, or {@code null}:
	 * it has to keep the tunnel open from the saved chunks, three blocks high and unlit by the sky, and closed above.
	 */
	private static String tunnel(LodSampler sampler, BlockPos at) {
		LodGrid grid = LodGrid.sample(sampler, at.getX(), at.getZ(), 1, 1, Integer.MAX_VALUE);
		BlockState[] states = new BlockState[5];
		byte[] sky = new byte[5];
		grid.voxels(0, at.getY() - 1, 1, states, sky);
		LOGGER.info("The far terrain's column at {} from the tunnel's floor up: {}, sky light {}", at.toShortString(),
			Arrays.toString(states), Arrays.toString(sky));
		boolean open = states[1] == null && states[2] == null && states[3] == null;
		boolean dark = sky[1] == 0 && sky[2] == 0 && sky[3] == 0;
		if (!open || !dark || states[0] == null || states[4] == null) {
			return "the far terrain does not keep the tunnel at " + at.toShortString() + " open, dark and closed";
		}
		return null;
	}

	/** The Ancient Grand Hollow nearest {@code from}, or the Grand Hollow if there is none within reach. */
	private static Sights.Found hollow(ServerLevel level, BlockPos from) {
		Sights sights = Objects.requireNonNull(Sights.of(level));
		Sights.Found found = sights.nearest(Sights.Sight.ANCIENT_GRAND_HOLLOW, from.getX(), from.getZ());
		if (found == null || Math.hypot(found.pos().getX() - from.getX(), found.pos().getZ() - from.getZ()) > 4096) {
			found = sights.nearest(Sights.Sight.GRAND_HOLLOW, from.getX(), from.getZ());
		}
		return Objects.requireNonNull(found, "No Grand Hollow near the spawn");
	}

	/**
	 * What is wrong with the far terrain in the middle of a cavern, or {@code null}: the columns
	 * {@value #CAVERN_STEP} blocks apart within {@value #CAVERN_REACH} of its smaller radius of its middle have to
	 * open between its floor and its ceiling, most of them, into a cave the sky does not light.
	 */
	private static String cavern(LodSampler sampler, Hollows.Chamber chamber) {
		int reach = (int) (CAVERN_REACH * Math.min(chamber.rx(), chamber.rz()));
		int width = 2 * reach / CAVERN_STEP + 1;
		int minX = (int) chamber.x() - reach, minZ = (int) chamber.z() - reach;
		LodGrid grid = LodGrid.generate(sampler, minX, minZ, width, CAVERN_STEP, Integer.MAX_VALUE);
		int bottom = (int) Math.floor(chamber.y() - chamber.down() - chamber.basin());
		int height = (int) Math.ceil(chamber.y() + chamber.up()) - bottom;
		BlockState[] states = new BlockState[height];
		byte[] sky = new byte[height];
		int checked = 0, open = 0, lit = 0;
		for (int column = 0; column < width * width; column++) {
			int x = minX + column % width * CAVERN_STEP, z = minZ + column / width * CAVERN_STEP;
			if (Math.hypot(x - chamber.x(), z - chamber.z()) > reach) {
				continue;
			}
			checked++;
			grid.voxels(column, bottom, 1, states, sky);
			boolean opens = false;
			for (int i = 0; i < height; i++) {
				if (states[i] == null) {
					opens = true;
					lit += sky[i] != 0 ? 1 : 0;
				}
			}
			open += opens ? 1 : 0;
		}
		LOGGER.info("{} of the {} far columns checked in the {} open between y={} and y={}, {} blocks of them sky-lit",
			open, checked, chamber.kind(), bottom, bottom + height, lit);
		if (open < CAVERN_OPEN * checked || lit > 0) {
			return String.format(Locale.ROOT, "the far terrain opens %d of %d columns of the %s at %.0f %.0f %.0f, "
				+ "%d blocks of them lit by the sky", open, checked, chamber.kind(), chamber.x(), chamber.y(), chamber.z(),
				lit);
		}
		return null;
	}

	/**
	 * Logs what a tile of far terrain over the middle of a cavern costs, at the coarsest step that carves it: how long
	 * its grid takes to sample once the terrain under it is computed, and how many faces its mesh has, and how many of
	 * them the sky does not light.
	 */
	private static void cost(LodSampler sampler, Hollows.Chamber chamber) {
		// A block off the store's pages, which it would read back instead of sampling.
		int minX = (int) chamber.x() - LodStore.PAGE * CAVERN_STEP / 2 + 1;
		int minZ = (int) chamber.z() - LodStore.PAGE * CAVERN_STEP / 2 + 1;
		LodGrid.generate(sampler, minX, minZ, LodStore.PAGE, CAVERN_STEP, Integer.MAX_VALUE);
		long start = System.nanoTime();
		LodGrid grid = LodGrid.generate(sampler, minX, minZ, LodStore.PAGE, CAVERN_STEP, Integer.MAX_VALUE);
		long took = System.nanoTime() - start;
		int[] faces = new int[2];
		LodMesher.Faces count = (side, x0, y0, z0, x1, y1, z1, state, biome, sky) -> {
			faces[0]++;
			faces[1] += sky == 0 ? 1 : 0;
		};
		LodMesher.mesh(grid, CAVERN_STEP, count, count);
		LOGGER.info("A tile of far terrain {} blocks wide over the {} took {} ms to sample and has {} faces, {} of them "
				+ "in the dark", LodStore.PAGE * CAVERN_STEP, chamber.kind(), took / 1_000_000, faces[0], faces[1]);
	}
}
