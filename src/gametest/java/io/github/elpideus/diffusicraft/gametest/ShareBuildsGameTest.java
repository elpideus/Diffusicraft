package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.client.lod.RemoteLods;
import io.github.elpideus.diffusicraft.client.lod.SeenChunks;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * What the far terrain a dedicated server sends shows of what players built ({@code shareBuilds}, see
 * {@code worldgen.lod.LodFeed} and {@code client.lod.SeenChunks}). Starts a server on the world of
 * {@link FarTerrainGameTest} with the setting off, as by default, and then with it on, and joins each over a real
 * connection. Each time, the player watches from {@value FarTerrainGameTest#TOWER_VIEW_DISTANCE} blocks north while
 * the server raises a tower of gold {@value FarTerrainGameTest#TOWER_DISTANCE} blocks south of the spawn, in chunks
 * the player never had.
 *
 * <p>Off, checks that the server sends the terrain as generated, without the tower, and then that once the player has
 * been to the tower and left, their client keeps it as they saw it. On, checks that the server sends the tower and
 * that the far terrain builds it again with no move of the player's. Saves a view at each step
 * ({@code diffusicraft-far-terrain-share-<off|on>-<step>.png}).
 *
 * <p>Needs the Terrain Diffusion models. Skipped where Distant Horizons or Voxy is installed, which turn the far
 * terrain off.
 */
public class ShareBuildsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft share builds test");
	/** How long the server gets to load the tower's chunks, and the far terrain to show the tower, at most. */
	private static final int TIMEOUT_TICKS = 3 * 60 * 20;
	private static final int POLL_TICKS = 10;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FarTerrainGameTest.prepare(this, context)) {
			return;
		}
		// What an earlier run's client saw of the same world would show the tower before this one has seen it.
		try {
			FileUtils.deleteDirectory(context.computeOnClient(mc -> mc.gameDirectory.toPath())
				.resolve("diffusicraft").resolve("seen").toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try {
			run(context, false);
			run(context, true);
		} finally {
			Resources.get().updateConfig(config -> config.shareBuilds = false);
		}
	}

	private static void run(ClientGameTestContext context, boolean share) {
		// The server reads it as it starts, in this JVM.
		Resources.get().updateConfig(config -> {
			config.serveFarTerrain = true;
			config.shareBuilds = share;
		});
		Properties properties = new Properties();
		properties.setProperty("view-distance", String.valueOf(FarTerrainGameTest.RENDER_DISTANCE));
		properties.setProperty("simulation-distance", "4");
		properties.setProperty("max-tick-time", "-1");
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> ui.setSeed(FarTerrainGameTest.SEED))
			.createServer(properties)) {
			BlockPos spawn = server.computeOnServer(s -> s.overworld().getRespawnData().pos());
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				RemoteLods.Feed feed = context.computeOnClient(mc -> RemoteLods.feed());
				if (feed == null || !feed.serves(Level.OVERWORLD)) {
					throw new AssertionError("The server did not offer the overworld's far terrain");
				}
				if (feed.sharesBuilds() != share) {
					throw new AssertionError("The server offered far terrain that "
						+ (share ? "does not share" : "shares") + " what players built");
				}
				VoxyGameTest.look(context, server);
				String name = "share-" + (share ? "on" : "off") + "-";
				// The server holds the tower's chunks from now on, and never sends them to the player.
				BlockPos at = spawn.south(FarTerrainGameTest.TOWER_DISTANCE);
				int radius = FarTerrainGameTest.TOWER_RADIUS;
				String area = String.format(Locale.ROOT, "%d %d %d %d", at.getX() - radius, at.getZ() - radius,
					at.getX() + radius, at.getZ() + radius);
				server.runCommand("forceload add " + area);
				waitUntil(context, () -> server.computeOnServer(s -> s.overworld().isLoaded(at.offset(-radius, 0,
					-radius)) && s.overworld().isLoaded(at.offset(radius, 0, -radius))
					&& s.overworld().isLoaded(at.offset(-radius, 0, radius))
					&& s.overworld().isLoaded(at.offset(radius, 0, radius))), "the tower's chunks loaded");
				int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.WORLD_SURFACE,
					at.getX(), at.getZ()));
				BlockPos top = at.atY(ground + FarTerrainGameTest.TOWER_HEIGHT);
				FarTerrainGameTest.viewTower(context, server, connection, top, name + "before");

				long before = FarTerrain.uploads();
				server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gold_block",
					at.getX() - radius, ground, at.getZ() - radius, at.getX() + radius, top.getY(),
					at.getZ() + radius));
				server.runOnServer(s -> s.saveEverything(true, true, true));
				server.runCommand("forceload remove " + area);
				LOGGER.info("Raised a tower of gold from {} to y={}", at.atY(ground), top.getY());
				if (share) {
					waitUntil(context, () -> shows(context, feed, null, top), "the server sent the tower");
					// Told that the tower's pages changed, the far terrain builds them again where it stands.
					context.waitFor(mc -> FarTerrain.uploads() > before, TIMEOUT_TICKS);
					FarTerrainGameTest.viewTower(context, server, connection, top, name + "after");
					return;
				}
				FarTerrainGameTest.viewTower(context, server, connection, top, name + "after");
				if (shows(context, feed, null, top)) {
					throw new AssertionError("The server sent the tower that another player raised, far away");
				}

				// There and back: the client lets go of the tower's chunks as it leaves.
				BlockPos near = top.below(FarTerrainGameTest.TOWER_HEIGHT / 2).north(radius * 4);
				server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d 0 0", near.getX(), near.getY(),
					near.getZ()));
				connection.waitForClientboundPackets();
				connection.waitForChunksRender();
				FarTerrainGameTest.viewTower(context, server, connection, top, name + "seen");
				LodStore seen = context.computeOnClient(mc -> SeenChunks.store(feed, Level.OVERWORLD));
				if (seen == null) {
					throw new AssertionError("The client keeps no seen chunks of a server that does not share builds");
				}
				waitUntil(context, () -> shows(context, feed, seen, top), "the client kept the tower it saw");
			}
		}
	}

	/**
	 * Whether the grid the server sends of the tower's page has gold at its top, with the chunks {@code seen} keeps
	 * laid over it if it is not null.
	 */
	private static boolean shows(ClientGameTestContext context, RemoteLods.Feed feed, @Nullable LodStore seen,
								 BlockPos top) {
		int minX = Math.floorDiv(top.getX(), LodStore.PAGE) * LodStore.PAGE;
		int minZ = Math.floorDiv(top.getZ(), LodStore.PAGE) * LodStore.PAGE;
		int ceiling = context.computeOnClient(mc -> mc.level.getMaxY() + 1);
		CompletableFuture<@Nullable LodGrid> reply = feed.fetch(Level.OVERWORLD, minX, minZ, LodStore.PAGE, 1, ceiling);
		// The reply is read on the client thread, which runs only while the test waits.
		context.waitFor(mc -> reply.isDone(), TIMEOUT_TICKS);
		LodGrid sent = reply.join();
		if (sent == null) {
			throw new AssertionError("The server sent no grid of the tower's page");
		}
		LodGrid grid = seen == null ? sent : seen.overlay(minX, minZ, LodStore.PAGE, 1, ceiling, () -> sent);
		BlockState[] states = new BlockState[1];
		grid.voxels((top.getZ() - minZ) * LodStore.PAGE + top.getX() - minX, top.getY(), 1, states, new byte[1]);
		return states[0] != null && states[0].is(Blocks.GOLD_BLOCK);
	}

	/** Checks {@code done} every {@value #POLL_TICKS} ticks, and fails if it has not held after a while. */
	private static void waitUntil(ClientGameTestContext context, BooleanSupplier done, String what) {
		for (int waited = 0; !done.getAsBoolean(); waited += POLL_TICKS) {
			if (waited >= TIMEOUT_TICKS) {
				throw new AssertionError("Timed out waiting until " + what);
			}
			context.waitTicks(POLL_TICKS);
		}
		LOGGER.info("Done waiting until {}", what);
	}
}
