package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyLods;
import io.github.elpideus.diffusicraft.client.lod.RemoteLods;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;

/**
 * Voxy's far terrain on a dedicated server, filled from the grids the server sends (see {@code worldgen.lod.LodFeed}
 * and {@code client.lod.RemoteLods}). Starts a server on the world of {@link WorldGenerationGameTest}, joins it over
 * a real connection at a render distance of {@value VoxyGameTest#RENDER_DISTANCE} chunks and saves two views from
 * above the spawn ({@code diffusicraft-voxy-server-<view>.png}), each once no grid is on its way. Checks
 * that the client is not running the world itself, that the server offered its far terrain, that sections were filled
 * from it and that none failed, and logs how large a grid is on the wire.
 *
 * <p>Runs only with Voxy installed: {@code -PdevMods=voxy,sodium}. Needs the Terrain Diffusion models.
 */
public class VoxyServerGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft Voxy server test");
	/**
	 * How long the count of filled sections must stand still, with no grid on its way, before a view is taken, and how
	 * long to wait at most: longer than in singleplayer, as the server makes chunks with the same models while it
	 * samples the grids.
	 */
	private static final int SETTLED_TICKS = 45 * 20;
	private static final int FILL_TIMEOUT_TICKS = 15 * VoxyGameTest.TICKS_PER_MINUTE;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!VoxyGameTest.prepare(this, context)) {
			return;
		}
		Properties properties = new Properties();
		properties.setProperty("view-distance", String.valueOf(VoxyGameTest.RENDER_DISTANCE));
		properties.setProperty("simulation-distance", "4");
		// The server waits for the client every tick, and software rendering can take Voxy's frames past the watchdog.
		properties.setProperty("max-tick-time", "-1");
		// Fabric's consistent settings make a flat world.
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> ui.setSeed(VoxyGameTest.SEED))
			.createServer(properties)) {
			// Before the client joins: on a cold CPU these hold the server thread up longer than a connection waits.
			WorldGenerationGameTest.Probe probe = server
				.computeOnServer(s -> WorldGenerationGameTest.Probe.of(s.overworld()));
			LOGGER.info("Spawn {} at surface y={}", probe.spawn(), probe.spawnSurface());
			server.runOnServer(s -> logSizes(s, probe.spawn()));
			try (TestDedicatedServerConnection connection = server.connect()) {
				view(context, server, connection, probe);
			}
		}
	}

	private static void view(ClientGameTestContext context, TestDedicatedServerContext server,
							 TestDedicatedServerConnection connection, WorldGenerationGameTest.Probe probe) {
		connection.waitForChunksRender();
		if (context.computeOnClient(mc -> mc.getSingleplayerServer() != null)) {
			throw new AssertionError("The client runs the world itself");
		}
		RemoteLods.Feed feed = RemoteLods.feed();
		if (feed == null || !feed.serves(Level.OVERWORLD)) {
			throw new AssertionError("The server did not offer the overworld's far terrain");
		}

		long filled = VoxyLods.filled();
		long failed = VoxyLods.failed();
		VoxyGameTest.look(context, server);
		BlockPos above = probe.spawn().atY(probe.spawnSurface() + 80);
		// A grid can take longer than the settling time to sample, on the CPU.
		BooleanSupplier idle = () -> feed.pending() == 0;
		VoxyGameTest.view(context, server, connection, above, 0f, 10f, "server-south", SETTLED_TICKS,
			FILL_TIMEOUT_TICKS, idle);
		VoxyGameTest.view(context, server, connection, above.above(400), 90f, 30f, "server-high-west",
			SETTLED_TICKS, FILL_TIMEOUT_TICKS, idle);

		filled = VoxyLods.filled() - filled;
		failed = VoxyLods.failed() - failed;
		LOGGER.info("Voxy sections filled from the server's far terrain: {}, failed: {}", filled, failed);
		if (filled == 0) {
			throw new AssertionError("No section of Voxy's was filled from the server's far terrain");
		}
		if (failed != 0) {
			throw new AssertionError(failed + " of Voxy's sections could not be filled");
		}
	}

	/** Logs how many bytes the grids at the spawn take as the server sends them, before and after compression. */
	private static void logSizes(MinecraftServer server, BlockPos spawn) {
		LodSampler sampler = LodSampler.of(server.overworld());
		for (int step : new int[]{1, 4, 16}) {
			int extent = 32 * step;
			int minX = Math.floorDiv(spawn.getX(), extent) * extent;
			int minZ = Math.floorDiv(spawn.getZ(), extent) * extent;
			LodGrid grid = LodGrid.sample(sampler, minX, minZ, 32, step, 4096);
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
			LodGrid.STREAM_CODEC.encode(buf, grid);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			Deflater deflater = new Deflater();
			deflater.setInput(bytes);
			deflater.finish();
			byte[] out = new byte[bytes.length + 64];
			int compressed = 0;
			while (!deflater.finished()) {
				compressed += deflater.deflate(out);
			}
			deflater.end();
			LOGGER.info("Grid of 32x32 columns {} blocks apart at the spawn: {} bytes, {} compressed", step,
				bytes.length, compressed);
		}
	}
}
