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
 * Sends the far terrain of Diffusicraft worlds to the clients that draw it, so that Voxy shows it in multiplayer as
 * it does in singleplayer. A client with Diffusicraft hears as it joins how far the feed reaches and which levels it
 * serves ({@link LodPayloads.Offer}), then asks for grids ({@link LodPayloads.Request}) and gets each in a
 * {@link LodPayloads.Reply}, with no grid when the server refuses it.
 *
 * <p>The server refuses a request whose level is not the player's or not a Diffusicraft one, whose grid lies further
 * from the player than {@code farTerrainRadius}, or that would leave the player more than {@value #MAX_IN_FLIGHT}
 * requests waiting. Grids are sampled on {@value #THREADS} threads of their own, so they never hold up the ticks, and
 * the last {@value #CACHED_GRIDS} are kept for players near each other. Everything else runs on the server thread.
 */
public final class LodFeed {
	/** Threads sampling grids. Each grid may run the models over the tiles it covers, as chunks do. */
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
	private final Map<ResourceKey<Level>, Optional<LodSampler>> samplers = new HashMap<>();
	private final Map<UUID, Integer> inFlight = new HashMap<>();
	private final Map<GridKey, CompletableFuture<LodGrid>> grids = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<GridKey, CompletableFuture<LodGrid>> eldest) {
			return size() > CACHED_GRIDS;
		}
	};
	private @Nullable ExecutorService executor;
	private volatile boolean closed;
	private boolean warned;

	private LodFeed(MinecraftServer server, int radius) {
		this.server = server;
		this.radius = radius;
	}

	public static void init() {
		LodPayloads.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			DiffusicraftConfig config = Resources.get().config();
			feed = config.serveFarTerrain ? new LodFeed(server, config.farTerrainRadius) : null;
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
		return new LodPayloads.Offer(radius, MAX_IN_FLIGHT, levels);
	}

	private @Nullable LodSampler sampler(ServerLevel level) {
		return samplers.computeIfAbsent(level.dimension(), key -> Optional.ofNullable(LodSampler.of(level)))
			.orElse(null);
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
			request.ceiling());
		CompletableFuture<LodGrid> grid = grids.get(key);
		if (grid == null) {
			grid = CompletableFuture.supplyAsync(() -> {
				if (closed) {
					throw new CancellationException("The server is stopping");
				}
				return LodGrid.sample(sampler, key.minX, key.minZ, key.width, key.step, key.ceiling);
			}, executor());
			grids.put(key, grid);
		}
		CompletableFuture<LodGrid> future = grid;
		future.whenComplete((sampled, error) -> server.execute(() -> {
			if (closed) {
				return;
			}
			inFlight.computeIfPresent(id, (uuid, n) -> n > 1 ? n - 1 : null);
			if (error != null) {
				grids.remove(key, future);
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
		// From the player to the nearest column of the grid.
		long extent = (long) request.width() * step;
		double dx = Math.max(0, Math.max(request.minX() - player.getX(), player.getX() - (request.minX() + extent)));
		double dz = Math.max(0, Math.max(request.minZ() - player.getZ(), player.getZ() - (request.minZ() + extent)));
		double reach = (double) radius + MARGIN;
		return dx * dx + dz * dz > reach * reach ? null : sampler(level);
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

	private record GridKey(ResourceKey<Level> level, int minX, int minZ, int width, int step, int ceiling) {
	}
}
