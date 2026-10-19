package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Computes terrain tiles before chunk generation asks for them.
 *
 * <p>A {@link TerrainField} tile takes seconds of inference, and every chunk that needs it waits until it is done:
 * without this, the world stops appearing at a tile's edge while the player flies on. Told where the players are and
 * how fast they move, the prefetcher plans the tiles their view will reach within {@value #HORIZON_SECONDS} s, the
 * earliest first, and one background thread computes them one at a time. The rivers and lakes of a tile need the
 * terrain of the eight tiles around it, so the plan takes in a ring of tiles around the view. A tile in a lake of the
 * coarse map needs all the lake's tiles, up to 16, which can lie beyond the ring: the prefetcher computes them right
 * after the tile. A tile that chunk generation needs before the prefetcher gets to it is computed by chunk generation
 * as before, and the prefetcher skips it.
 *
 * <p>Model runs take turns, so such a tile takes longer while a prefetch runs beside it. Planning the earliest tiles
 * first keeps that rare. A plan covers at most a quarter of each field's tile cache, so prefetching does not evict the
 * tiles in use; the lakes on it add their tiles, seldom more than a few.
 */
public final class TerrainPrefetcher implements AutoCloseable {
	/** How far ahead of the players to plan, in seconds. */
	static final double HORIZON_SECONDS = 20.0;
	/** Seconds between the points of a player's path that the plan looks around. */
	static final double STEP_SECONDS = 2.0;
	/** Faster than this, in blocks per second, counts as this fast: elytra with rockets fly at about 33. */
	static final double MAX_SPEED = 100.0;
	/** How long to leave a field alone after its source failed, as when the models are missing. */
	private static final long RETRY_NANOS = 60_000_000_000L;
	private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPrefetcher.class);
	private static final Comparator<Request> ORDER =
		Comparator.comparingDouble(Request::seconds).thenComparingDouble(Request::distance);

	/**
	 * A player, or anything else that generates chunks around it.
	 *
	 * @param x      block X
	 * @param z      block Z
	 * @param vx     blocks per second along X
	 * @param vz     blocks per second along Z
	 * @param radius blocks around it that chunk generation covers: the view distance and a margin
	 */
	public record Traveller(TerrainField field, WorldScale scale, double x, double z, double vx, double vz,
							double radius) {
	}

	/** A tile to compute, reached {@code seconds} from now at {@code distance} pixels from the traveller's path. */
	record Request(TerrainField field, int ti, int tj, double seconds, double distance) {
	}

	private final Object lock = new Object();
	private final Thread thread;
	private final LongAdder prefetched = new LongAdder();
	private final Map<TerrainField, Long> failedAt = new IdentityHashMap<>();
	/** Guarded by {@link #lock}. */
	private List<Request> plan = List.of();
	/** Guarded by {@link #lock}; changes with every new plan. */
	private long version;
	private volatile boolean closed;

	public TerrainPrefetcher() {
		thread = new Thread(this::run, "Diffusicraft terrain prefetch");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	/** Replaces the plan with one for these travellers. Cheap: call it as often as they move. */
	public void update(List<Traveller> travellers) {
		List<Request> next = plan(travellers);
		synchronized (lock) {
			if (!next.equals(plan)) {
				plan = next;
				version++;
				lock.notifyAll();
			}
		}
	}

	/** Tiles computed by this prefetcher so far. */
	public long prefetchedTiles() {
		return prefetched.sum();
	}

	/** Whether every tile of the current plan is in memory or being computed. */
	public boolean caughtUp() {
		synchronized (lock) {
			return plan.stream().allMatch(r -> r.field().hasTile(r.ti(), r.tj()));
		}
	}

	/** Stops planning. A tile being computed is finished, or fails if its models are closed meanwhile. */
	@Override
	public void close() {
		closed = true;
		synchronized (lock) {
			lock.notifyAll();
		}
	}

	/**
	 * The tiles the travellers' view will reach within {@value #HORIZON_SECONDS} s and the ring of tiles around them,
	 * the earliest first and, among those reached at the same time, the nearest. Each field gets at most a quarter of
	 * its tile cache.
	 */
	static List<Request> plan(List<Traveller> travellers) {
		Map<TerrainField, Map<Long, Request>> byField = new IdentityHashMap<>();
		for (Traveller t : travellers) {
			Map<Long, Request> tiles = byField.computeIfAbsent(t.field(), f -> new HashMap<>());
			double speed = Math.hypot(t.vx(), t.vz());
			double slow = speed > MAX_SPEED ? MAX_SPEED / speed : 1.0;
			double ppb = t.scale().pixelsPerBlock();
			double r = t.radius() * ppb;
			for (double s = 0; s <= HORIZON_SECONDS; s += STEP_SECONDS) {
				// Pixel i grows with Z and j with X.
				double pi = (t.z() + t.vz() * slow * s) * ppb;
				double pj = (t.x() + t.vx() * slow * s) * ppb;
				// The tiles in view, and the ring around them that their rivers and lakes need.
				for (int ti = TerrainField.tileOf(pi - r) - 1; ti <= TerrainField.tileOf(pi + r) + 1; ti++) {
					for (int tj = TerrainField.tileOf(pj - r) - 1; tj <= TerrainField.tileOf(pj + r) + 1; tj++) {
						double di = (ti + 0.5) * TerrainField.TILE - pi, dj = (tj + 0.5) * TerrainField.TILE - pj;
						Request request = new Request(t.field(), ti, tj, s, Math.hypot(di, dj));
						tiles.merge(((long) ti << 32) | (tj & 0xffffffffL), request,
							(a, b) -> ORDER.compare(a, b) <= 0 ? a : b);
					}
				}
			}
		}
		List<Request> plan = new ArrayList<>();
		for (Map.Entry<TerrainField, Map<Long, Request>> entry : byField.entrySet()) {
			int limit = Math.max(1, entry.getKey().maxTiles() / 4);
			entry.getValue().values().stream().sorted(ORDER).limit(limit).forEach(plan::add);
		}
		plan.sort(ORDER);
		return plan;
	}

	private void run() {
		long done = -1;
		while (!closed) {
			List<Request> requests;
			synchronized (lock) {
				while (!closed && version == done) {
					try {
						lock.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				requests = plan;
				done = version;
			}
			for (Request r : requests) {
				if (closed || stale(done)) {
					break;
				}
				if (failing(r.field())) {
					continue;
				}
				try {
					if (r.field().prefetch(r.ti(), r.tj())) {
						prefetched.increment();
					}
					// A lake of the coarse map on the tile needs all its tiles, which can lie beyond the ring.
					int[] lake = Rivers.of(r.field()).lakeTiles(r.ti(), r.tj());
					for (int c = 0; c < lake.length && !closed && !stale(done); c += 2) {
						if (r.field().prefetch(lake[c], lake[c + 1])) {
							prefetched.increment();
						}
					}
				} catch (RuntimeException e) {
					if (!closed) {
						LOGGER.info("Not generating terrain ahead for a minute: {}", e.getMessage());
					}
					failedAt.put(r.field(), System.nanoTime());
				}
			}
		}
	}

	private boolean stale(long done) {
		synchronized (lock) {
			return version != done;
		}
	}

	private boolean failing(TerrainField field) {
		Long at = failedAt.get(field);
		if (at == null || System.nanoTime() - at > RETRY_NANOS) {
			failedAt.remove(field);
			return false;
		}
		return true;
	}
}
