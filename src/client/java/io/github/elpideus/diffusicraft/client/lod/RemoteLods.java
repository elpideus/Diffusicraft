package io.github.elpideus.diffusicraft.client.lod;

import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The far terrain a server sends (see {@code worldgen.lod.LodFeed}), for a client that does not run the world itself.
 * Voxy's far terrain comes from it in multiplayer (see {@code client.compat.voxy}).
 */
public final class RemoteLods {
	/** How long to wait for a grid before giving up on it, in minutes: only a server that never answers takes so long. */
	private static final long TIMEOUT_MINUTES = 10;

	private static volatile @Nullable Feed feed;

	private RemoteLods() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(LodPayloads.Offer.TYPE, (offer, context) -> {
			close();
			feed = new Feed(offer, context.responseSender());
		});
		ClientPlayNetworking.registerGlobalReceiver(LodPayloads.Reply.TYPE, (reply, context) -> {
			Feed feed = RemoteLods.feed;
			if (feed != null) {
				feed.reply(reply);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Feed feed = RemoteLods.feed;
			LocalPlayer player = client.player;
			if (feed != null && player != null) {
				feed.position = new Position(player.level().dimension(), player.getX(), player.getZ());
			}
		});
	}

	/** The far terrain of the server the client is connected to, or null if it sends none. */
	public static @Nullable Feed feed() {
		return feed;
	}

	private static void close() {
		Feed feed = RemoteLods.feed;
		RemoteLods.feed = null;
		if (feed != null) {
			feed.close();
		}
	}

	/**
	 * The far terrain of one connection to a server. Asks for no more grids at once than the server allows, and keeps
	 * the rest waiting; the nearest to the player is sent first, so that the far terrain fills in from the player out.
	 * Thread-safe.
	 */
	public static final class Feed {
		private final int radius;
		private final int maxInFlight;
		private final Map<ResourceKey<Level>, Integer> minYs = new HashMap<>();
		private final PacketSender sender;
		private final AtomicInteger ids = new AtomicInteger();
		/** Guards everything below it. */
		private final Object lock = new Object();
		private final List<Ask> waiting = new ArrayList<>();
		private final Map<Integer, CompletableFuture<@Nullable LodGrid>> sent = new HashMap<>();
		private boolean closed;
		private volatile @Nullable Position position;

		private Feed(LodPayloads.Offer offer, PacketSender sender) {
			this.radius = offer.radius();
			this.maxInFlight = Math.max(1, offer.maxInFlight());
			for (LodPayloads.Served served : offer.levels()) {
				minYs.put(served.level(), served.minY());
			}
			this.sender = sender;
		}

		/** How many grids have been asked for that have not come yet, sent or waiting to be. */
		public int pending() {
			synchronized (lock) {
				return waiting.size() + sent.size();
			}
		}

		/** Whether the server sends the far terrain of {@code level}. */
		public boolean serves(ResourceKey<Level> level) {
			return minYs.containsKey(level);
		}

		/** The lowest block Y the grids of a level the server serves reach. */
		public int minY(ResourceKey<Level> level) {
			return minYs.get(level);
		}

		/**
		 * Asks the server for the grid {@link LodGrid#sample} samples from its {@code level} with these arguments.
		 * Completes with the grid, or with null if the server would refuse it, refuses it or does not answer in time,
		 * or the connection closes first. Never waits.
		 */
		public CompletableFuture<@Nullable LodGrid> fetch(ResourceKey<Level> level, int minX, int minZ, int width,
														 int step, int ceiling) {
			Ask ask = new Ask(new LodPayloads.Request(ids.getAndIncrement(), level, minX, minZ, width, step, ceiling),
				new CompletableFuture<>());
			if (!serves(level) || !inRange(position, ask.request)) {
				return CompletableFuture.completedFuture(null);
			}
			synchronized (lock) {
				if (closed) {
					return CompletableFuture.completedFuture(null);
				}
				waiting.add(ask);
			}
			send();
			return ask.reply;
		}

		/** Sends the requests waiting, as far as the server allows. */
		private void send() {
			while (true) {
				Position at = position;
				Ask ask;
				boolean inRange;
				synchronized (lock) {
					if (closed || sent.size() >= maxInFlight || waiting.isEmpty()) {
						return;
					}
					ask = takeNearest(at);
					// The player may have gone on since it was asked for.
					inRange = inRange(at, ask.request);
					if (inRange) {
						sent.put(ask.request.id(), ask.reply);
					}
				}
				CompletableFuture<@Nullable LodGrid> reply = ask.reply;
				if (!inRange) {
					reply.complete(null);
					continue;
				}
				int id = ask.request.id();
				reply.orTimeout(TIMEOUT_MINUTES, TimeUnit.MINUTES).whenComplete((grid, error) -> {
					boolean freed;
					synchronized (lock) {
						freed = sent.remove(id, reply);
					}
					if (freed) {
						send();
					}
				});
				// Closed meanwhile.
				if (!reply.isDone()) {
					sender.sendPacket(ask.request);
				}
			}
		}

		/** Takes the request waiting whose grid is nearest to the player. Holds {@link #lock}; none may be empty. */
		private Ask takeNearest(@Nullable Position at) {
			int nearest = 0;
			if (at != null) {
				double least = Double.POSITIVE_INFINITY;
				for (int i = 0; i < waiting.size(); i++) {
					double distance = distanceSquared(at, waiting.get(i).request);
					if (distance < least) {
						least = distance;
						nearest = i;
					}
				}
			}
			Ask ask = waiting.get(nearest);
			Ask last = waiting.removeLast();
			if (nearest < waiting.size()) {
				waiting.set(nearest, last);
			}
			return ask;
		}

		/** Whether a request's grid is within the server's radius of the player, as far as the client can tell. */
		private boolean inRange(@Nullable Position at, LodPayloads.Request request) {
			return at == null || distanceSquared(at, request) <= (double) radius * radius;
		}

		/** The square of the distance from the player to the nearest column of a request's grid. */
		private static double distanceSquared(Position at, LodPayloads.Request request) {
			if (!at.level.equals(request.level())) {
				return Double.POSITIVE_INFINITY;
			}
			long extent = (long) request.width() * request.step();
			double dx = Math.max(0, Math.max(request.minX() - at.x, at.x - (request.minX() + extent)));
			double dz = Math.max(0, Math.max(request.minZ() - at.z, at.z - (request.minZ() + extent)));
			return dx * dx + dz * dz;
		}

		private void reply(LodPayloads.Reply reply) {
			CompletableFuture<@Nullable LodGrid> future;
			synchronized (lock) {
				future = sent.get(reply.id());
			}
			if (future != null) {
				future.complete(reply.grid());
			}
		}

		private void close() {
			List<CompletableFuture<@Nullable LodGrid>> open = new ArrayList<>();
			synchronized (lock) {
				closed = true;
				open.addAll(sent.values());
				for (Ask ask : waiting) {
					open.add(ask.reply);
				}
				waiting.clear();
			}
			for (CompletableFuture<@Nullable LodGrid> future : open) {
				future.complete(null);
			}
		}
	}

	/** A request and the future its reply completes. */
	private record Ask(LodPayloads.Request request, CompletableFuture<@Nullable LodGrid> reply) {
	}

	/** Where the player was at the last tick. */
	private record Position(ResourceKey<Level> level, double x, double z) {
	}
}
