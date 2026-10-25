package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.runtime.DiffusicraftConfig;
import io.github.elpideus.diffusicraft.runtime.Resources;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the far terrain of Diffusicraft worlds to the clients that draw it, so that their far terrain, or Voxy, shows
 * it in multiplayer as in singleplayer. A client with Diffusicraft hears as it joins how far the feed reaches, whether
 * it shares what players built and which levels it serves ({@link LodPayloads.Offer}), then asks for grids
 * ({@link LodPayloads.Request}) and gets each in a {@link LodPayloads.Reply}, with no grid when the server refuses it.
 * Diffusicraft's own far terrain asks for each grid rough first, and to refine it once it is drawn, so that it never
 * holds up the server's chunk generation ({@link LodGrid.Mode}); Voxy asks for it in full.
 *
 * <p>By default the grids are the terrain as generated ({@link LodGrid#generate}), and each client keeps what it saw
 * itself: a player cannot find from afar what others built. A server that shares what players built
 * ({@code shareBuilds}) sends the chunks as they were last saved where players have been ({@link LodGrid#sample}),
 * and tells the players near a page of them that changed ({@link LodPayloads.Changed}).
 *
 * <p>The server refuses a request whose level is not the player's or not a Diffusicraft one, whose grid lies further
 * from the player than {@code farTerrainRadius}, or that would leave the player more than {@value #MAX_IN_FLIGHT}
 * requests waiting. Grids are sampled on {@value #THREADS} threads of their own, so they never hold up the ticks, and
 * the last {@value #CACHED_GRIDS} that are not rough are kept for players near each other, until a chunk saved under
 * one changes it ({@link LodStore}) where the server shares what players built. Everything else runs on the server
 * thread.
 */
public final class LodFeed {
	/**
	 * Threads sampling grids. A grid asked for in full may run the models over the tiles it covers, as chunks do; one
	 * asked for rough runs none, and one to refine one tile at most, while they are idle ({@link LodGrid.Mode}).
	 */
	static final int THREADS = 2;
	/** Requests a player may have waiting at once. */
	static final int MAX_IN_FLIGHT = 32;
	private static final int CACHED_GRIDS = 128;
	/** Blocks beyond the radius a grid may reach, for a player who moved since asking. */
	private static final int MARGIN = 512;
	/** Widest step between columns a request may ask for: that of Voxy's coarsest sections and more. */
	private static final int MAX_STEP = 64;

	private static @Nullable LodFeed feed;

	private final MinecraftServer server;
	private final int radius;
	private final boolean shareBuilds;
	private final Map<ResourceKey<Level>, Optional<LodSampler>> samplers = new HashMap<>();
	private final Map<UUID, Integer> inFlight = new HashMap<>();
	private final Map<GridKey, CompletableFuture<@Nullable LodGrid>> grids = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<GridKey, CompletableFuture<@Nullable LodGrid>> eldest) {
			return size() > CACHED_GRIDS;
		}
	};
	private @Nullable ExecutorService executor;
	private volatile boolean closed;
	private boolean warned;

	private LodFeed(MinecraftServer server, int radius, boolean shareBuilds) {
		this.server = server;
		this.radius = radius;
		this.shareBuilds = shareBuilds;
	}

	public static void init() {
		LodPayloads.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			DiffusicraftConfig config = Resources.get().config();
			feed = config.serveFarTerrain ? new LodFeed(server, config.farTerrainRadius, config.shareBuilds) : null;
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (feed != null) {
				feed.close();
				feed = null;
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (feed != null && ServerPlayNetworking.canSend(handler.player, LodPayloads.Offer.TYPE)) {
				sender.sendPacket(feed.offer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (feed != null) {
				feed.inFlight.remove(handler.player.getUUID());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(LodPayloads.Request.TYPE, (request, context) -> {
			if (feed == null) {
				context.responseSender().sendPacket(new LodPayloads.Reply(request.id(), null));
			} else {
				feed.request(context.player(), request);
			}
		});
	}

	private LodPayloads.Offer offer() {
		List<LodPayloads.Served> levels = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			LodSampler sampler = sampler(level);
			if (sampler != null) {
				levels.add(new LodPayloads.Served(level.dimension(), sampler.minY()));
			}
		}
		long world = BiomeManager.obfuscateSeed(server.overworld().getSeed());
		return new LodPayloads.Offer(radius, MAX_IN_FLIGHT, shareBuilds, world, levels);
	}

	private @Nullable LodSampler sampler(ServerLevel level) {
		return samplers.computeIfAbsent(level.dimension(), key -> {
			LodSampler sampler = LodSampler.of(level);
			if (shareBuilds && sampler != null && sampler.store() != null) {
				sampler.store().listen((detail, pageX, pageZ) ->
					server.execute(() -> changed(key, detail, pageX, pageZ)));
			}
			return Optional.ofNullable(sampler);
		}).orElse(null);
	}

	/**
	 * Drops the cached grids that the page at {@code (pageX, pageZ)} of a level of detail lies under, and tells the
	 * players near it that it changed.
	 */
	private void changed(ResourceKey<Level> level, int detail, int pageX, int pageZ) {
		forget(level, detail, pageX, pageZ);
		ServerLevel serverLevel = server.getLevel(level);
		if (serverLevel == null) {
			return;
		}
		LodPayloads.Changed changed = new LodPayloads.Changed(level, detail, pageX, pageZ);
		long span = (long) LodStore.PAGE << detail;
		for (ServerPlayer player : serverLevel.players()) {
			if (near(player, pageX * span, pageZ * span, span)
				&& ServerPlayNetworking.canSend(player, LodPayloads.Changed.TYPE)) {
				ServerPlayNetworking.send(player, changed);
			}
		}
	}

	/** Drops the cached grids that the page at {@code (pageX, pageZ)} of a level of detail lies under. */
	private void forget(ResourceKey<Level> level, int detail, int pageX, int pageZ) {
		int step = 1 << detail;
		long span = (long) LodStore.PAGE * step;
		long minX = pageX * span;
		long minZ = pageZ * span;
		grids.keySet().removeIf(key -> key.level.equals(level) && key.step == step
			&& key.minX < minX + span && minX < key.minX + (long) key.width * step
			&& key.minZ < minZ + span && minZ < key.minZ + (long) key.width * step);
	}

	private void request(ServerPlayer player, LodPayloads.Request request) {
		UUID id = player.getUUID();
		int waiting = inFlight.getOrDefault(id, 0);
		LodSampler sampler = waiting < MAX_IN_FLIGHT ? accepts(player, request) : null;
		if (sampler == null) {
			ServerPlayNetworking.send(player, new LodPayloads.Reply(request.id(), null));
			return;
		}
		inFlight.put(id, waiting + 1);
		GridKey key = new GridKey(request.level(), request.minX(), request.minZ(), request.width(), request.step(),
			request.ceiling(), request.mode());
		CompletableFuture<@Nullable LodGrid> grid = grids.get(key);
		if (grid == null) {
			grid = CompletableFuture.supplyAsync(() -> {
				if (closed) {
					throw new CancellationException("The server is stopping");
				}
				return shareBuilds
					? LodGrid.sample(sampler, key.minX, key.minZ, key.width, key.step, key.ceiling, key.mode)
					: LodGrid.generate(sampler, key.minX, key.minZ, key.width, key.step, key.ceiling, key.mode);
			}, executor());
			grids.put(key, grid);
		}
		CompletableFuture<@Nullable LodGrid> future = grid;
		future.whenComplete((sampled, error) -> server.execute(() -> {
			if (closed) {
				return;
			}
			inFlight.computeIfPresent(id, (uuid, n) -> n > 1 ? n - 1 : null);
			// A rough grid, or none yet, is sampled again when asked for again: the terrain may be ready by then.
			if (error != null || sampled == null || sampled.rough()) {
				grids.remove(key, future);
			}
			if (error != null) {
				if (!warned) {
					warned = true;
					Diffusicraft.LOGGER.warn("Could not sample far terrain for {}", player.getScoreboardName(), error);
				}
			}
			if (!player.hasDisconnected()) {
				ServerPlayNetworking.send(player, new LodPayloads.Reply(request.id(), error == null ? sampled : null));
			}
		}));
	}

	/** The sampler of the request's level if the server grants it, or null. */
	private @Nullable LodSampler accepts(ServerPlayer player, LodPayloads.Request request) {
		int step = request.step();
		if (request.width() < 1 || request.width() > LodGrid.MAX_WIDTH || step < 1 || step > MAX_STEP
			|| Integer.bitCount(step) != 1) {
			return null;
		}
		ServerLevel level = player.level();
		if (!level.dimension().equals(request.level())) {
			return null;
		}
		return near(player, request.minX(), request.minZ(), (long) request.width() * step) ? sampler(level) : null;
	}

	/** Whether the square of {@code extent} blocks from {@code (minX, minZ)} reaches within the feed's radius. */
	private boolean near(ServerPlayer player, long minX, long minZ, long extent) {
		// From the player to the nearest column of the square.
		double dx = Math.max(0, Math.max(minX - player.getX(), player.getX() - (minX + extent)));
		double dz = Math.max(0, Math.max(minZ - player.getZ(), player.getZ() - (minZ + extent)));
		double reach = (double) radius + MARGIN;
		return dx * dx + dz * dz <= reach * reach;
	}

	private ExecutorService executor() {
		if (executor == null) {
			AtomicInteger threads = new AtomicInteger();
			executor = Executors.newFixedThreadPool(THREADS, task -> {
				Thread thread = new Thread(task, "Diffusicraft far terrain " + threads.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
		}
		return executor;
	}

	/**
	 * Stops sampling. A grid being sampled is finished, or fails if its models are closed meanwhile; interrupting it
	 * would close the files of the terrain's caches under it.
	 */
	private void close() {
		closed = true;
		if (executor != null) {
			executor.shutdown();
		}
	}

	private record GridKey(ResourceKey<Level> level, int minX, int minZ, int width, int step, int ceiling,
						   LodGrid.Mode mode) {
	}
}
