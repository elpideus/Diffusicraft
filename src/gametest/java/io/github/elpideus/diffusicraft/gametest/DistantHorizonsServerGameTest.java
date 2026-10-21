package io.github.elpideus.diffusicraft.gametest;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Distant Horizons on a dedicated server, which makes its LODs from the terrain (see {@code compat.dh}) and sends them
 * to the client. Distant Horizons keeps one world per JVM, which a client joining a server in the same JVM takes over,
 * so this test joins a real server in a JVM of its own: start it with {@code ./gradlew runGameTestServer} and give the
 * test its port with {@code -Pdiffusicraft.testServerPort} (see docs/DEVELOPMENT.md). Waits until the client holds
 * LODs on rings round the spawn, well past the chunks it is sent, saves two views from above the spawn
 * ({@code diffusicraft-dh-server-<view>.png}) and then checks that it held them all. Checks, in the server's log, that
 * the server handed Distant Horizons Diffusicraft's generator for the overworld, and stops the server when done.
 *
 * <p>Runs only when named ({@code -Pdiffusicraft.gametests=DistantHorizonsServer}), with Distant Horizons installed
 * on both sides: {@code -PdevMods=distanthorizons} for both Gradle runs. The server needs the Terrain Diffusion models.
 * With Distant Horizons SeedGen on both sides, the server must not share its seed ({@code "shareSeedWithClients":
 * false} in its {@code config/seedgen.json}), or the client draws the far terrain itself instead of taking the
 * server's.
 */
public class DistantHorizonsServerGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft Distant Horizons server test");
	private static final int TICKS_PER_MINUTE = 20 * 60;
	private static final int RENDER_DISTANCE = 8;
	/** How far from the spawn the client must hold LODs, in blocks: all past the chunks the server sends it. */
	private static final int[] RINGS = {320, 640, 1280};
	private static final int DIRECTIONS = 8;
	/** The server makes the chunks round the spawn before it lets anyone in. */
	private static final int JOIN_TIMEOUT_TICKS = 5 * TICKS_PER_MINUTE;
	private static final int LOD_TIMEOUT_TICKS = 20 * TICKS_PER_MINUTE;
	private static final int POLL_TICKS = 5 * 20;
	private static final int QUERY_TIMEOUT_TICKS = TICKS_PER_MINUTE;
	/** How long Distant Horizons gets to draw what it holds round a view before the screenshot. */
	private static final int DRAW_TICKS = 30 * 20;
	private static final String REGISTERED = "Distant Horizons LODs for minecraft:overworld come from the terrain";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Only when named: it needs a server started apart from the test.
		if (!GameTests.named(this)) {
			return;
		}
		String port = System.getProperty("diffusicraft.testServerPort");
		if (port == null) {
			LOGGER.info("Skipping DistantHorizonsServerGameTest: no -Pdiffusicraft.testServerPort; start the server "
				+ "with ./gradlew runGameTestServer");
			return;
		}
		if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			LOGGER.info("Skipping DistantHorizonsServerGameTest: Distant Horizons is not installed "
				+ "(-PdevMods=distanthorizons)");
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		// The LODs the client kept from an earlier run would stand in for those the server is to send.
		deleteTree(context.computeOnClient(mc -> mc.gameDirectory.toPath()).resolve("Distant_Horizons_server_data"));

		String address = "localhost:" + port;
		context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc,
			ServerAddress.parseString(address), new ServerData("Diffusicraft game test server", address,
				ServerData.Type.OTHER), false, null));
		context.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, JOIN_TIMEOUT_TICKS);
		try {
			view(context);
		} finally {
			stop(context);
		}
	}

	private static void view(ClientGameTestContext context) {
		checkLog();
		if (context.computeOnClient(mc -> mc.getSingleplayerServer() != null)) {
			throw new AssertionError("The client runs the world itself");
		}
		command(context, "gamemode spectator");
		try {
			context.waitFor(mc -> mc.player.isSpectator(), 10 * 20);
		} catch (AssertionError e) {
			throw new AssertionError(context.computeOnClient(mc -> mc.getUser().getName())
				+ " is not an operator on the test server, whose ops.json names Player0", e);
		}
		command(context, "weather clear");
		context.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
		BlockPos spawn = context.computeOnClient(mc -> {
			BlockPos at = mc.player.blockPosition();
			return at.atY(mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()));
		});
		LOGGER.info("Spawn {}", spawn);

		int points = RINGS.length * DIRECTIONS;
		int held = waitForLods(context, spawn, points);
		// The views come before the check, so that a run that fails shows what the client drew.
		BlockPos above = spawn.above(80);
		look(context, above, 0f, 10f, "south");
		look(context, above.above(400), 90f, 30f, "high-west");
		if (held < points) {
			throw new AssertionError("The client holds LODs at only " + held + " of " + points
				+ " points round the spawn");
		}
	}

	/**
	 * Waits until the client holds LODs at all {@code points} of {@link #RINGS} round {@code spawn}, or for
	 * {@link #LOD_TIMEOUT_TICKS}, and returns how many it holds.
	 */
	private static int waitForLods(ClientGameTestContext context, BlockPos spawn, int points) {
		long start = System.nanoTime();
		int held = 0;
		for (int waited = 0; waited < LOD_TIMEOUT_TICKS; waited += POLL_TICKS) {
			context.waitTicks(POLL_TICKS);
			// On a thread of its own: Distant Horizons calls Minecraft.getInstance(), which Fabric forbids on the test
			// thread, and its reads finish only as the client ticks, which it does in step with the test thread.
			CompletableFuture<Integer> query = CompletableFuture.supplyAsync(() -> heldLods(spawn));
			context.waitFor(mc -> query.isDone(), QUERY_TIMEOUT_TICKS);
			held = query.join();
			if (held == points) {
				break;
			}
		}
		LOGGER.info("The client holds LODs at {} of {} points round the spawn after {} s", held, points,
			(System.nanoTime() - start) / 1_000_000_000L);
		return held;
	}

	/** How many points of {@link #RINGS} round {@code spawn} the client holds an LOD with ground at. */
	private static int heldLods(BlockPos spawn) {
		IDhApiLevelWrapper level;
		try {
			level = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
		} catch (IllegalStateException e) {
			return 0;
		}
		if (level == null) {
			return 0;
		}
		IDhApiTerrainDataCache cache = DhApi.Delayed.terrainRepo.createSoftCache();
		int held = 0;
		StringBuilder tops = new StringBuilder();
		for (int ring : RINGS) {
			for (int i = 0; i < DIRECTIONS; i++) {
				double angle = 2 * Math.PI * i / DIRECTIONS;
				int x = spawn.getX() + (int) Math.round(ring * Math.cos(angle));
				int z = spawn.getZ() + (int) Math.round(ring * Math.sin(angle));
				int top = groundTop(DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(level, x, z, cache));
				tops.append(top == Integer.MIN_VALUE ? "-" : String.valueOf(top)).append(' ');
				if (top != Integer.MIN_VALUE) {
					held++;
				}
			}
			tops.append("| ");
		}
		LOGGER.info("Ground under the rings round the spawn, as the client's LODs hold it: {}", tops);
		return held;
	}

	/** The top of the highest block that is not air in a column, or {@link Integer#MIN_VALUE} if none is held. */
	private static int groundTop(DhApiResult<DhApiTerrainDataPoint[]> column) {
		int top = Integer.MIN_VALUE;
		if (column.success && column.payload != null) {
			for (DhApiTerrainDataPoint point : column.payload) {
				if (point != null && !point.blockStateWrapper.isAir()) {
					top = Math.max(top, point.topYBlockPos);
				}
			}
		}
		return top;
	}

	/** Teleports the player and takes a screenshot at noon once Distant Horizons has had time to draw. */
	private static void look(ClientGameTestContext context, BlockPos at, float yaw, float pitch, String name) {
		command(context, String.format(Locale.ROOT, "tp @s %d %d %d %.1f %.1f", at.getX(), at.getY(), at.getZ(),
			yaw, pitch));
		context.waitFor(mc -> mc.player.blockPosition().closerThan(at, 2) && mc.levelRenderer.hasRenderedAllSections(),
			2 * TICKS_PER_MINUTE);
		command(context, "time set noon");
		context.waitTicks(DRAW_TICKS);
		Path file = context.takeScreenshot("diffusicraft-dh-server-" + name);
		LOGGER.info("Screenshot: {}", file.toAbsolutePath());
	}

	private static void command(ClientGameTestContext context, String command) {
		context.runOnClient(mc -> mc.player.connection.sendCommand(command));
	}

	/** Checks that the server handed Distant Horizons Diffusicraft's generator for the overworld. */
	private static void checkLog() {
		Path log = Path.of(System.getProperty("diffusicraft.testServerDir"), "logs", "latest.log");
		String text;
		try {
			text = Files.readString(log);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the test server's log", e);
		}
		if (!text.contains(REGISTERED)) {
			throw new AssertionError("The server did not hand Distant Horizons Diffusicraft's generator: no \""
				+ REGISTERED + "\" in " + log);
		}
	}

	/** Has the server stop, which disconnects the client, and goes back to the title screen. */
	private static void stop(ClientGameTestContext context) {
		if (context.computeOnClient(mc -> mc.player != null)) {
			command(context, "stop");
			context.waitFor(mc -> mc.level == null, 2 * TICKS_PER_MINUTE);
		}
		context.setScreen(TitleScreen::new);
	}

	private static void deleteTree(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not delete " + root, e);
		}
	}
}
