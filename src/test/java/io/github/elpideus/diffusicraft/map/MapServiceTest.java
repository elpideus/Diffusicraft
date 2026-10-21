package io.github.elpideus.diffusicraft.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapServiceTest {
	private static final UUID PLAYER = new UUID(1, 2);

	private final LifoExecutor fine = new LifoExecutor("test fine", 1);
	private final LifoExecutor fast = new LifoExecutor("test fast", 1);
	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private MapService service;
	private String base;

	@BeforeEach
	void start() throws Exception {
		MapTiles tiles = new MapTiles(new FakeLevel(), null, fine, fast);
		service = new MapService(List.of(tiles), new MapService.Game() {
			@Override
			public MapService.@Nullable Position player(@Nullable UUID owner) {
				return PLAYER.equals(owner) ? new MapService.Position("test:flat", 10.5, 70, -3.5, 90) : null;
			}

			@Override
			public MapService.Position spawn() {
				return new MapService.Position("test:flat", 0.5, 64, 0.5, 0);
			}

			@Override
			public @Nullable String name(@Nullable UUID owner) {
				return PLAYER.equals(owner) ? "Tester" : null;
			}
		}, fine, InetAddress.getLoopbackAddress(), 0);
		base = "http://127.0.0.1:" + service.port() + "/" + service.token(PLAYER) + "/";
	}

	@AfterEach
	void stop() {
		service.close();
		fine.close();
		fast.close();
	}

	private HttpResponse<byte[]> get(String url) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private JsonObject json(String route) throws Exception {
		HttpResponse<byte[]> response = get(base + route);
		assertEquals(200, response.statusCode(), route);
		return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void servesThePageUnderItsToken() throws Exception {
		HttpResponse<byte[]> page = get(base);
		assertEquals(200, page.statusCode());
		assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("<canvas"));
		assertTrue(page.headers().firstValue("content-security-policy").orElseThrow().contains("default-src 'none'"));

		HttpResponse<byte[]> bare = get(base.substring(0, base.length() - 1));
		assertEquals(308, bare.statusCode());
		assertEquals("/" + service.token(PLAYER) + "/", bare.headers().firstValue("location").orElseThrow());

		assertEquals(404, get("http://127.0.0.1:" + service.port() + "/0123456789abcdef/").statusCode());
		assertEquals(404, get(base + "nothing").statusCode());
	}

	@Test
	void givesEachOwnerTheirOwnToken() {
		assertEquals(service.token(PLAYER), service.token(PLAYER));
		assertNotEquals(service.token(PLAYER), service.token(null));
		assertEquals(32, service.token(null).length());
	}

	@Test
	void describesTheLevels() throws Exception {
		JsonObject info = json("api/info");
		JsonObject level = info.getAsJsonArray("levels").get(0).getAsJsonObject();
		assertEquals("test:flat", level.get("id").getAsString());
		assertEquals(2, level.get("fine").getAsInt());
		assertEquals(5, level.get("max").getAsInt());
		assertEquals("Ocean", level.getAsJsonArray("biomeNames").get(1).getAsString());
		assertEquals("Tester", info.get("player").getAsString());
	}

	@Test
	void servesTilesGzipped() throws Exception {
		HttpResponse<byte[]> tile = get(base + "tile/0/2/0/0");
		assertEquals(200, tile.statusCode());
		assertEquals("gzip", tile.headers().firstValue("content-encoding").orElseThrow());
		MapRaster raster = MapRaster.decode(tile.body());
		assertEquals(FakeLevel.FINE_RGB, raster.rgb[0]);

		assertEquals(404, get(base + "tile/0/6/0/0").statusCode(), "past the coarsest zoom level");
		assertEquals(404, get(base + "tile/0/0/99999/0").statusCode(), "past the border");
		assertEquals(400, get(base + "tile/1/0/0/0").statusCode(), "no such level");
	}

	@Test
	void reportsTheTilesDrawnSinceARevision() throws Exception {
		JsonObject before = json("api/state").getAsJsonArray("levels").get(0).getAsJsonObject();
		assertEquals(0, before.get("rev").getAsLong());
		get(base + "tile/0/2/3/-1");
		JsonObject state = json("api/state?since=0");
		JsonObject level = state.getAsJsonArray("levels").get(0).getAsJsonObject();
		assertEquals(1, level.get("rev").getAsLong());
		JsonArray tile = level.getAsJsonArray("tiles").get(0).getAsJsonArray();
		assertEquals(3, tile.get(0).getAsInt());
		assertEquals(-1, tile.get(1).getAsInt());
		assertEquals(10.5, state.getAsJsonObject("player").get("x").getAsDouble());
		assertEquals(0, state.getAsJsonObject("player").get("level").getAsInt());
		assertEquals(0.5, state.getAsJsonObject("spawn").get("z").getAsDouble());
	}

	@Test
	void answersPointsAndSearches() throws Exception {
		JsonObject point = json("api/point?level=0&x=12&z=-7");
		assertEquals(12, point.get("x").getAsInt());
		assertEquals(70, point.get("ground").getAsInt());
		assertEquals(3, point.get("water").getAsInt());
		assertTrue(json("api/point?level=0&x=99999999&z=0").isEmpty(), "outside the world");

		JsonObject place = json("api/search?level=0&biome=1&x=0&z=0").getAsJsonArray("places").get(0)
			.getAsJsonObject();
		assertEquals(100, place.get("x").getAsInt());
		assertEquals(-50, place.get("z").getAsInt());

		assertEquals(400, get(base + "api/point?level=0&x=abc&z=0").statusCode());
		assertEquals(400, get(base + "api/point?level=3&x=0&z=0").statusCode());
	}

	@Test
	void refusesWhatItDoesNotServe() throws Exception {
		HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "api/info"))
			.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(405, post.statusCode());
		HttpResponse<String> head = client.send(HttpRequest.newBuilder(URI.create(base + "api/info"))
			.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, head.statusCode());
		assertEquals("", head.body());
	}
}
