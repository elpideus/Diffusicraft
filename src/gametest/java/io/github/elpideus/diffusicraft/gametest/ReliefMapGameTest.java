package io.github.elpideus.diffusicraft.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.map.ReliefMap;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Creates a Diffusicraft world of the Medium size and checks its relief map without opening a browser: it asks for
 * the player's link as {@code /diffusicraft map} does, then fetches the page, the levels and the tiles around the
 * player from the map's web server. It saves the colours of the tiles around the player, drawn from the coarse map
 * and in detail, to {@code build/run/clientGameTest/screenshots} as {@code diffusicraft-map-coarse.png} and
 * {@code diffusicraft-map-fine.png}. See docs/MAP.md.
 *
 * <p>With {@code -Pdiffusicraft.mapLink=<file>} it then writes the link to that file and keeps the world open until a
 * file of the same name with {@code .stop} appended appears, or for {@value #HOLD_MINUTES} minutes, so that the page
 * can be looked at in a browser. Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class ReliefMapGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft map test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final WorldScale SIZE = WorldScale.MEDIUM;
	private static final int TICKS_PER_MINUTE = 20 * 60;
	/** Zoom levels over the fine one at which the coarse map is fetched: each doubles the blocks per pixel. */
	private static final int COARSER = 4;
	/** Longest the world is kept open for a browser, in minutes. */
	private static final int HOLD_MINUTES = 20;
	/** A tile's side in pixels, and the bytes before its planes: as in {@code MapRaster}. */
	private static final int TILE = 256;
	private static final int HEADER = 8;
	private static final int PIXELS = TILE * TILE;
	/** The planes: the ground's Y in two bytes, the water's depth, the biome and the colour in three. */
	private static final int BYTES = HEADER + PIXELS * (2 + 1 + 1 + 3);

	private final HttpClient http = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the relief map test");
			return;
		}
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);

		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				WorldSizes.select(ui, SIZE);
			})
			.create()) {
			Link link = world.getServer().computeOnServer(server -> {
				check(ReliefMap.available(server), "the map is available in a Diffusicraft world");
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				try {
					return new Link(ReliefMap.link(server, player), Mth.floor(player.getX()),
						Mth.floor(player.getZ()));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			check(link.uri != null, "the map gives the player a link");
			LOGGER.info("The relief map is at {}", link.uri);
			check(loopback(link.uri.getHost()), "the map listens only on this machine by default");
			check(link.uri.getFragment() != null && link.uri.getFragment().endsWith("/" + link.x + "/" + link.z),
				"the link is centred on the player");

			String page = new String(get(link.base(), 30), StandardCharsets.UTF_8);
			check(page.toLowerCase(Locale.ROOT).contains("<html"), "the map's page is a web page");
			JsonObject info = JsonParser.parseString(new String(get(link.base().resolve("api/info"), 30),
				StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray levels = info.getAsJsonArray("levels");
			check(!levels.isEmpty(), "the map shows a level");
			JsonObject overworld = levels.get(0).getAsJsonObject();
			check(overworld.get("id").getAsString().equals("minecraft:overworld"), "the map opens on the overworld");
			int fine = overworld.get("fine").getAsInt(), max = overworld.get("max").getAsInt();
			LOGGER.info("The map's levels: {}; detailed up to zoom {}, coarse to {}", levels.size(), fine, max);

			saveTiles(link, Math.min(max, fine + COARSER), "coarse");
			saveTiles(link, fine, "fine");
			hold(context, link.uri);
		}
	}

	/** A player's link to the map, and where they stood. */
	private record Link(URI uri, int x, int z) {
		/** The link without the place in the map: the page, under which the rest is served. */
		URI base() {
			return URI.create(uri.toString().replaceFirst("#.*$", ""));
		}
	}

	/**
	 * Fetches the 2×2 tiles at {@code zoom} whose corner is nearest the player, checks that they show some relief and
	 * saves their colours as {@code diffusicraft-map-<name>.png}.
	 */
	private void saveTiles(Link link, int zoom, String name) {
		long span = (long) TILE << zoom;
		int tx = (int) Math.round(link.x / (double) span) - 1, ty = (int) Math.round(link.z / (double) span) - 1;
		BufferedImage image = new BufferedImage(2 * TILE, 2 * TILE, BufferedImage.TYPE_INT_RGB);
		int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
		long start = System.nanoTime();
		for (int j = 0; j < 2; j++) {
			for (int i = 0; i < 2; i++) {
				URI tile = link.base().resolve("tile/0/" + zoom + "/" + (tx + i) + "/" + (ty + j));
				byte[] raw = gunzip(get(tile, 600));
				check(raw.length == BYTES && raw[0] == 'D' && raw[1] == 'M' && raw[2] == 'A' && raw[3] == 'P',
					"a tile of the map is a DMAP raster of " + BYTES + " bytes, not " + raw.length);
				int rgb = HEADER + PIXELS * 4;
				for (int p = 0; p < PIXELS; p++) {
					int ground = (short) ((raw[HEADER + 2 * p] & 0xFF) | raw[HEADER + 2 * p + 1] << 8);
					lowest = Math.min(lowest, ground);
					highest = Math.max(highest, ground);
					int o = rgb + 3 * p;
					image.setRGB(i * TILE + p % TILE, j * TILE + p / TILE,
						(raw[o] & 0xFF) << 16 | (raw[o + 1] & 0xFF) << 8 | raw[o + 2] & 0xFF);
				}
			}
		}
		LOGGER.info("Map tiles at zoom {} ({} blocks a pixel) around {}, {}: ground from y = {} to {}, in {} s", zoom,
			1L << zoom, link.x, link.z, lowest, highest, (System.nanoTime() - start) / 1_000_000_000L);
		check(highest > lowest, "the map's " + name + " tiles show the ground's relief");
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		try {
			Files.createDirectories(dir);
			ImageIO.write(image, "png", dir.resolve("diffusicraft-map-" + name + ".png").toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** With {@code diffusicraft.mapLink} set, writes the link there and waits for the stop file. */
	private static void hold(ClientGameTestContext context, URI link) {
		String file = System.getProperty("diffusicraft.mapLink", "");
		if (file.isEmpty()) {
			return;
		}
		Path linkFile = Path.of(file), stop = Path.of(file + ".stop");
		try {
			Files.writeString(linkFile, link + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		LOGGER.info("Keeping the world open for the map until {} appears, or for {} minutes", stop, HOLD_MINUTES);
		try {
			context.waitFor(mc -> Files.exists(stop), HOLD_MINUTES * TICKS_PER_MINUTE);
		} catch (AssertionError e) {
			LOGGER.info("No {} after {} minutes; closing the world", stop, HOLD_MINUTES);
		}
	}

	/** The body of a GET, which must answer 200 within {@code seconds}; unzipped by the caller if gzipped. */
	private byte[] get(URI uri, int seconds) {
		try {
			HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(seconds))
				.GET()
				.build(), HttpResponse.BodyHandlers.ofByteArray());
			check(response.statusCode() == 200, uri.getPath() + " answers 200, not " + response.statusCode());
			byte[] body = response.body();
			return response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent()
				&& !uri.getPath().contains("/tile/") ? gunzip(body) : body;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while fetching " + uri.getPath(), e);
		}
	}

	private static boolean loopback(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (IOException e) {
			return false;
		}
	}

	private static byte[] gunzip(byte[] gzipped) {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Relief map: " + what);
		}
	}
}
