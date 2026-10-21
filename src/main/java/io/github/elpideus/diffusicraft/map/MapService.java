package io.github.elpideus.diffusicraft.map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The relief map's web server: the page, its questions and the tiles, for each of the levels it shows. Everything
 * lives under a secret path, one per player who asked for a link, so that only they can open it, and the page shows
 * the position of that player alone.
 *
 * <p>Routes, under {@code /<token>/}: {@code ""} the page; {@code api/info} the levels and their biomes;
 * {@code api/state?since=r0,r1,..} the tiles that changed since those revisions, the player and the world spawn;
 * {@code api/point?level&x&z} a column of the full-resolution terrain; {@code api/search?level&biome&x&z} places of
 * a biome; {@code tile/<level>/<zoom>/<x>/<y>} a {@link MapRaster}, gzipped.
 */
final class MapService implements AutoCloseable {
	/** How long a tile may take, at most, before the request gives up: long enough for a slow CPU. */
	private static final long TILE_TIMEOUT_NS = TimeUnit.MINUTES.toNanos(3);
	/** How often a waiting request checks that its browser still waits too. */
	private static final long POLL_MS = 200;
	private static final long SEARCH_NS = TimeUnit.SECONDS.toNanos(3);
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
		+ "connect-src 'self'; img-src 'self' data: blob:; base-uri 'none'; form-action 'none'";

	/** What the page asks the game for: the player who owns a token, and the world spawn. */
	interface Game {
		/** The player's position, or {@code null} if they are not online. */
		@Nullable Position player(@Nullable UUID owner);

		@Nullable Position spawn();

		/** The player's name, or {@code null} for the server's console. */
		@Nullable String name(@Nullable UUID owner);
	}

	/** A position in one of the levels, by id. */
	record Position(String level, double x, double y, double z, float yaw) {
	}

	private final List<MapTiles> levels;
	private final Game game;
	private final LifoExecutor fine;
	private final MapHttpServer http;
	private final byte[] page;
	/** The owner of each token: a player, or none for the console. */
	private final Map<String, Optional<UUID>> tokens = new ConcurrentHashMap<>();
	private final Map<Optional<UUID>, String> tokenOf = new ConcurrentHashMap<>();

	/** Serves {@code levels} on {@code address} and {@code port}, any free port if 0. */
	MapService(List<MapTiles> levels, Game game, LifoExecutor fine, InetAddress address, int port) throws IOException {
		this.levels = levels;
		this.game = game;
		this.fine = fine;
		try (InputStream in = MapService.class.getResourceAsStream("/diffusicraft/map/index.html")) {
			if (in == null) {
				throw new IOException("The relief map's page is missing from the mod");
			}
			this.page = in.readAllBytes();
		}
		this.http = new MapHttpServer(address, port, this::handle);
	}

	int port() {
		return http.port();
	}

	/** The secret path of a player's map, {@code null} for the console's. The same player always gets the same one. */
	String token(@Nullable UUID owner) {
		return tokenOf.computeIfAbsent(Optional.ofNullable(owner), key -> {
			byte[] bytes = new byte[16];
			RANDOM.nextBytes(bytes);
			String token = HexFormat.of().formatHex(bytes);
			tokens.put(token, key);
			return token;
		});
	}

	/** The index of a level in the map, or -1. */
	int level(String id) {
		for (int n = 0; n < levels.size(); n++) {
			if (levels.get(n).level().id().equals(id)) {
				return n;
			}
		}
		return -1;
	}

	private MapHttpServer.@Nullable Response handle(MapHttpServer.Exchange exchange) throws Exception {
		String path = exchange.path;
		int slash = path.indexOf('/', 1);
		String token = slash < 0 ? path.substring(1) : path.substring(1, slash);
		Optional<UUID> owner = tokens.get(token);
		if (owner == null) {
			return MapHttpServer.Response.of(404, "text/html; charset=utf-8", GONE);
		}
		if (slash < 0) {
			return new MapHttpServer.Response(308, "text/plain", new byte[0], false, Map.of("Location",
				"/" + token + "/"));
		}
		String route = path.substring(slash + 1);
		if (route.isEmpty() || route.equals("index.html")) {
			return new MapHttpServer.Response(200, "text/html; charset=utf-8", page, false,
				Map.of("Content-Security-Policy", CSP));
		}
		if (route.startsWith("tile/")) {
			return tile(exchange, route.substring(5).split("/"));
		}
		return switch (route) {
			case "api/info" -> json(info(owner.orElse(null)));
			case "api/state" -> json(state(exchange, owner.orElse(null)));
			case "api/point" -> point(exchange);
			case "api/search" -> json(search(exchange));
			default -> MapHttpServer.Response.text(404, "No such page");
		};
	}

	private static MapHttpServer.Response json(JsonObject json) {
		return MapHttpServer.Response.json(GSON.toJson(json));
	}

	private MapTiles tiles(MapHttpServer.Exchange exchange) {
		int level = exchange.intParam("level");
		if (level < 0 || level >= levels.size()) {
			throw new IllegalArgumentException("No level " + level);
		}
		return levels.get(level);
	}

	private JsonObject info(@Nullable UUID owner) {
		JsonObject info = new JsonObject();
		JsonArray list = new JsonArray();
		for (MapTiles tiles : levels) {
			MapLevel level = tiles.level();
			JsonObject json = new JsonObject();
			json.addProperty("id", level.id());
			json.addProperty("fine", level.fineZoom());
			json.addProperty("max", level.maxZoom());
			json.addProperty("minY", level.minY());
			json.addProperty("maxY", level.maxY());
			json.addProperty("sea", level.seaLevel());
			json.addProperty("border", level.border());
			json.addProperty("metersPerBlock", level.metersPerBlock());
			JsonArray biomes = new JsonArray(), names = new JsonArray();
			level.biomes().forEach(biomes::add);
			level.biomeNames().forEach(names::add);
			json.add("biomes", biomes);
			json.add("biomeNames", names);
			list.add(json);
		}
		info.add("levels", list);
		info.addProperty("player", game.name(owner));
		info.addProperty("tile", MapRaster.SIZE);
		return info;
	}

	private JsonObject state(MapHttpServer.Exchange exchange, @Nullable UUID owner) {
		String[] since = exchange.param("since", "").split(",");
		JsonObject state = new JsonObject();
		JsonArray list = new JsonArray();
		for (int n = 0; n < levels.size(); n++) {
			long from = n < since.length && !since[n].isBlank() ? Long.parseLong(since[n].trim()) : Long.MAX_VALUE;
			MapTiles.Changes changes = levels.get(n).changes(from);
			JsonObject json = new JsonObject();
			json.addProperty("rev", changes.revision());
			json.addProperty("all", changes.all());
			JsonArray tiles = new JsonArray();
			for (int[] tile : changes.tiles()) {
				JsonArray pair = new JsonArray();
				pair.add(tile[0]);
				pair.add(tile[1]);
				tiles.add(pair);
			}
			json.add("tiles", tiles);
			list.add(json);
		}
		state.add("levels", list);
		state.add("player", position(game.player(owner)));
		state.add("spawn", position(game.spawn()));
		return state;
	}

	private @Nullable JsonObject position(@Nullable Position position) {
		if (position == null) {
			return null;
		}
		JsonObject json = new JsonObject();
		json.addProperty("level", level(position.level()));
		json.addProperty("x", position.x());
		json.addProperty("y", position.y());
		json.addProperty("z", position.z());
		json.addProperty("yaw", position.yaw());
		return json;
	}

	private MapHttpServer.@Nullable Response point(MapHttpServer.Exchange exchange) throws Exception {
		MapLevel level = tiles(exchange).level();
		int x = exchange.intParam("x"), z = exchange.intParam("z");
		CompletableFuture<MapLevel.@Nullable Point> future = new CompletableFuture<>();
		fine.submit(() -> {
			try {
				future.complete(level.point(x, z));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		MapLevel.Point point = await(exchange, future, () -> {
		});
		if (point == null && !future.isDone()) {
			return null;
		}
		JsonObject json = new JsonObject();
		if (point != null) {
			json.addProperty("x", point.x());
			json.addProperty("z", point.z());
			json.addProperty("ground", point.ground());
			json.addProperty("water", point.water());
			json.addProperty("biome", point.biome());
		}
		return json(json);
	}

	private JsonObject search(MapHttpServer.Exchange exchange) {
		MapLevel level = tiles(exchange).level();
		int biome = exchange.intParam("biome");
		List<MapLevel.Point> points = level.search(biome, exchange.intParam("x"), exchange.intParam("z"),
			System.nanoTime() + SEARCH_NS);
		JsonArray list = new JsonArray();
		for (MapLevel.Point point : points) {
			JsonObject json = new JsonObject();
			json.addProperty("x", point.x());
			json.addProperty("z", point.z());
			json.addProperty("ground", point.ground());
			json.addProperty("water", point.water());
			list.add(json);
		}
		JsonObject result = new JsonObject();
		result.add("places", list);
		return result;
	}

	private MapHttpServer.@Nullable Response tile(MapHttpServer.Exchange exchange, String[] parts) throws Exception {
		if (parts.length != 4) {
			throw new IllegalArgumentException("A tile is tile/<level>/<zoom>/<x>/<y>");
		}
		int level = Integer.parseInt(parts[0]);
		int zoom = Integer.parseInt(parts[1]), x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]);
		if (level < 0 || level >= levels.size()) {
			throw new IllegalArgumentException("No level " + level);
		}
		MapTiles tiles = levels.get(level);
		MapLevel map = tiles.level();
		long reach = ((long) map.border() >> zoom) / MapRaster.SIZE + 1;
		if (zoom < 0 || zoom > map.maxZoom() || Math.abs((long) x) > reach || Math.abs((long) y) > reach) {
			return MapHttpServer.Response.text(404, "No such tile");
		}
		MapTiles.Ticket ticket = tiles.request(zoom, x, y);
		byte[] bytes = await(exchange, ticket.tile, ticket::release);
		if (bytes == null) {
			return null;
		}
		return new MapHttpServer.Response(200, "application/octet-stream", bytes, true, Map.of());
	}

	/**
	 * The future's value once it is done, or {@code null} if the browser gave up first. {@code release} runs once the
	 * request no longer waits, whatever the outcome.
	 */
	private static <T> @Nullable T await(MapHttpServer.Exchange exchange, CompletableFuture<T> future,
										  Runnable release) throws Exception {
		long start = System.nanoTime();
		try {
			while (true) {
				try {
					return future.get(POLL_MS, TimeUnit.MILLISECONDS);
				} catch (TimeoutException e) {
					if (!exchange.alive()) {
						return null;
					}
					if (System.nanoTime() - start > TILE_TIMEOUT_NS) {
						throw new IOException("Gave up waiting after " + TimeUnit.NANOSECONDS.toSeconds(TILE_TIMEOUT_NS)
							+ " s");
					}
				} catch (CancellationException e) {
					// Dropped: nobody waited for it when its turn came.
					return null;
				} catch (ExecutionException e) {
					throw e.getCause() instanceof Exception cause ? cause : e;
				}
			}
		} finally {
			release.run();
		}
	}

	@Override
	public void close() {
		http.close();
	}

	private static final byte[] GONE = """
		<!doctype html><meta charset="utf-8"><title>Relief map</title>
		<style>body{font:16px system-ui,sans-serif;background:#15181c;color:#dde3ea;display:grid;place-items:center;
		height:100vh;margin:0}code{background:#262b31;padding:2px 6px;border-radius:4px}</style>
		<p>This link is out of date. Run <code>/diffusicraft map</code> in the game for a new one.</p>
		""".getBytes(StandardCharsets.UTF_8);
}
