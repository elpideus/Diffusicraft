package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.runtime.DiffusicraftConfig;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The relief map of a world, in a web browser: {@code /diffusicraft map} answers with a link. The map shows every
 * level that Diffusicraft generates; its web server starts with the first link and stops with the world. See
 * docs/MAP.md.
 *
 * <p>Tiles are kept under {@code <world>/diffusicraft/relief-map}, which is emptied when the mod changes, for the
 * terrain may change with it. In a development environment they are kept in memory alone, unless the system property
 * {@code diffusicraft.mapCache} is {@code true}.
 */
public final class ReliefMap implements AutoCloseable {
	/** The version of the tiles kept on disk. */
	private static final String FORMAT = "1";
	private static @Nullable ReliefMap map;

	private final MinecraftServer server;
	private final LifoExecutor fine = new LifoExecutor("Diffusicraft relief map terrain", 2);
	private final LifoExecutor fast = new LifoExecutor("Diffusicraft relief map", 2);
	private final MapService service;
	private final String base;
	/** Whether only the server's machine can open the map. */
	private final boolean local;

	private ReliefMap(MinecraftServer server, List<LevelMap> levels, DiffusicraftConfig config) throws IOException {
		this.server = server;
		Path root = cacheRoot(server);
		List<MapTiles> tiles = new ArrayList<>();
		for (LevelMap level : levels) {
			Identifier id = Identifier.parse(level.id());
			Path dir = root == null ? null : root.resolve(id.getNamespace()).resolve(id.getPath());
			tiles.add(new MapTiles(level, dir, fine, fast));
		}
		InetAddress address = InetAddress.getByName(config.reliefMapAddress.isEmpty() ? "127.0.0.1"
			: config.reliefMapAddress);
		this.service = new MapService(tiles, new ServerGame(), fine, address, config.reliefMapPort);
		this.local = address.isLoopbackAddress();
		this.base = config.reliefMapUrl.isEmpty() ? "http://" + host(server, address) + ":" + service.port()
			: config.reliefMapUrl.replaceAll("/+$", "");
		Diffusicraft.LOGGER.info("The relief map is served at {}", base);
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
			Commands.literal(Diffusicraft.MOD_ID).requires(ReliefMap::allowed)
				.then(Commands.literal("map").executes(context -> command(context.getSource())))));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (map != null && map.server == server) {
				map.close();
				map = null;
			}
		});
	}

	/** Game masters, and the owner of a singleplayer world even without cheats: the map changes nothing. */
	private static boolean allowed(CommandSourceStack source) {
		if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
			return true;
		}
		ServerPlayer player = source.getPlayer();
		MinecraftServer server = source.getServer();
		return player != null && !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
	}

	private static int command(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		URI uri;
		try {
			uri = link(server, source.getPlayer());
		} catch (IOException | UncheckedIOException e) {
			Diffusicraft.LOGGER.warn("Could not start the relief map", e);
			source.sendFailure(Component.translatableWithFallback("diffusicraft.map.failed",
				"Could not start the relief map: %s", e.getMessage()));
			return 0;
		}
		if (uri == null) {
			source.sendFailure(Resources.get().config().reliefMap
				? Component.translatableWithFallback("diffusicraft.map.none",
					"No level of this world has Diffusicraft terrain to map.")
				: Component.translatableWithFallback("diffusicraft.map.off",
					"The relief map is turned off (reliefMap in config/diffusicraft.json)."));
			return 0;
		}
		String url = uri.toString();
		Component link = Component.literal(url).withStyle(style -> style.withColor(ChatFormatting.AQUA)
			.withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(uri))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("diffusicraft.map.open",
				"Open the relief map in your browser"))));
		Component copy = ComponentUtils.wrapInSquareBrackets(Component.translatableWithFallback("diffusicraft.map.copy",
			"Copy").withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.CopyToClipboard(url))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click")))));
		source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.map.link", "Relief map: %s %s", link,
			copy), false);
		if (map != null && map.local && server.isDedicatedServer()) {
			source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.map.local",
				"It opens on the server's own machine only: see reliefMapAddress in docs/CONFIG.md to share it.")
				.withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	/** Whether the map can show this server: it is not turned off, and some level has Diffusicraft terrain. */
	public static boolean available(MinecraftServer server) {
		if (!Resources.get().config().reliefMap) {
			return false;
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (LodSampler.of(level) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The link to a player's map, centred on them, starting the map's web server if it is not running: {@code null}
	 * for the console's, which is centred on the world spawn. Call it on the server thread. Null if there is nothing
	 * to map, or the map is turned off.
	 */
	public static @Nullable URI link(MinecraftServer server, @Nullable ServerPlayer player) throws IOException {
		ReliefMap relief = start(server);
		if (relief == null) {
			return null;
		}
		String token = relief.service.token(player == null ? null : player.getUUID());
		String at = "";
		if (player != null && relief.service.level(id(player.level())) >= 0) {
			at = "#" + id(player.level()) + "/" + Mth.floor(player.getX()) + "/" + Mth.floor(player.getZ());
		}
		return URI.create(relief.base + "/" + token + "/" + at);
	}

	private static @Nullable ReliefMap start(MinecraftServer server) throws IOException {
		if (map != null && map.server == server) {
			return map;
		}
		if (map != null) {
			map.close();
			map = null;
		}
		DiffusicraftConfig config = Resources.get().config();
		if (!config.reliefMap) {
			return null;
		}
		List<LevelMap> levels = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			LodSampler sampler = LodSampler.of(level);
			if (sampler != null) {
				LevelMap levelMap = new LevelMap(id(level), sampler);
				// The overworld first: the page opens on the first level.
				levels.add(level.dimension() == Level.OVERWORLD ? 0 : levels.size(), levelMap);
			}
		}
		if (levels.isEmpty()) {
			return null;
		}
		map = new ReliefMap(server, levels, config);
		return map;
	}

	private static String id(Level level) {
		return level.dimension().identifier().toString();
	}

	private static String host(MinecraftServer server, InetAddress address) {
		if (address.isAnyLocalAddress()) {
			String ip = server.getLocalIp();
			return ip == null || ip.isBlank() ? "localhost" : ip;
		}
		String host = address.getHostAddress();
		return address instanceof Inet6Address ? "[" + host + "]" : host;
	}

	/** Where the tiles are kept, emptied if they were drawn by another version of the mod; null to keep none. */
	private static @Nullable Path cacheRoot(MinecraftServer server) {
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isDevelopmentEnvironment() && !Boolean.getBoolean("diffusicraft.mapCache")) {
			return null;
		}
		Path root = server.getWorldPath(LevelResource.ROOT).resolve(Diffusicraft.MOD_ID).resolve("relief-map");
		String version = loader.getModContainer(Diffusicraft.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String format = FORMAT + " " + version;
		Path formatFile = root.resolve("format");
		try {
			if (Files.isDirectory(root) && !(Files.isRegularFile(formatFile)
				&& Files.readString(formatFile, StandardCharsets.UTF_8).strip().equals(format))) {
				try (Stream<Path> files = Files.walk(root)) {
					for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
						Files.delete(file);
					}
				}
			}
			Files.createDirectories(root);
			Files.writeString(formatFile, format + "\n", StandardCharsets.UTF_8);
			return root;
		} catch (IOException e) {
			Diffusicraft.LOGGER.warn("Keeping the relief map's tiles in memory alone: could not use {}", root, e);
			return null;
		}
	}

	@Override
	public void close() {
		service.close();
		fine.close();
		fast.close();
	}

	/** The players and the spawn, read on the server thread. */
	private final class ServerGame implements MapService.Game {
		@Override
		public MapService.@Nullable Position player(@Nullable UUID owner) {
			if (owner == null) {
				return null;
			}
			return onServer(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(owner);
				return player == null ? null : new MapService.Position(id(player.level()), player.getX(),
					player.getY(), player.getZ(), player.getYRot());
			});
		}

		@Override
		public MapService.@Nullable Position spawn() {
			return onServer(() -> {
				LevelData.RespawnData spawn = server.getRespawnData();
				BlockPos pos = spawn.pos();
				return new MapService.Position(spawn.dimension().identifier().toString(), pos.getX() + 0.5,
					pos.getY(), pos.getZ() + 0.5, spawn.yaw());
			});
		}

		@Override
		public @Nullable String name(@Nullable UUID owner) {
			if (owner == null) {
				return null;
			}
			return onServer(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(owner);
				return player == null ? null : player.getGameProfile().name();
			});
		}

		private <T> @Nullable T onServer(Supplier<@Nullable T> task) {
			try {
				return server.submit(task).get(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (ExecutionException | TimeoutException e) {
				return null;
			}
		}
	}
}
