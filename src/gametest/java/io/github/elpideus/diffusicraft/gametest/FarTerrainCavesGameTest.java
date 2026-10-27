package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.runtime.Resources;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The far terrain seen from a cave that runs out of the chunks drawn. Digs a sealed tunnel {@value #DEPTH} blocks
 * under the spawn, running {@value #LENGTH} blocks south, past the {@value FarTerrainGameTest#RENDER_DISTANCE} chunks
 * drawn, and looks down it with night vision: where the chunks end the tunnel has to end in rock, not open onto the
 * sky and the undersides of the far terrain. Saves to {@code build/run/clientGameTest/screenshots} the view with the
 * far terrain on and off, and fails if no cave wall closes the tunnel.
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn it off.
 */
public class FarTerrainCavesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain caves test");
	/** How far under the ground the tunnel runs, how far south, and in how long pieces it is dug. */
	private static final int DEPTH = 40;
	private static final int LENGTH = 6 * 16 + 24;
	private static final int PIECE = 16;

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
			BlockPos start = probe.spawn().atY(probe.spawnSurface() - DEPTH);
			dig(context, server, connection, start);

			FarTerrainGameTest.view(context, server, connection, start.above(), 0f, 0f, "cave");
			int walls = context.computeOnClient(mc -> FarTerrain.caveWalls());
			LOGGER.info("{} faces of cave wall close the tunnel", walls);
			Resources.get().updateConfig(config -> config.farTerrain = false);
			context.waitTicks(20);
			context.takeScreenshot("diffusicraft-far-terrain-cave-off");
			Resources.get().updateConfig(config -> config.farTerrain = true);
			if (walls == 0) {
				throw new AssertionError("No cave wall closes the tunnel from " + start + " out of the chunks drawn");
			}
		}
	}

	/**
	 * Digs a tunnel three blocks wide and high south from {@code start}, its floor at {@code start}'s y, in a shell of
	 * stone that seals it from any cave it crosses, as far as the chunks loaded around the player reach.
	 */
	private static void dig(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
							BlockPos start) {
		server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d 0 0", start.getX(), start.getY() + 1,
			start.getZ()));
		connection.waitForClientboundPackets();
		connection.waitForChunksRender();
		int x = start.getX(), y = start.getY();
		for (int from = -2; from < LENGTH; from += PIECE) {
			int to = Math.min(LENGTH, from + PIECE - 1);
			server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 2, y - 1,
				start.getZ() + from, x + 2, y + 3, start.getZ() + to));
			server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 1, y,
				start.getZ() + Math.max(from, 0), x + 1, y + 2, start.getZ() + to));
		}
		LOGGER.info("Dug a tunnel from {} {} blocks south", start, LENGTH);
		context.waitTicks(40);
	}
}
