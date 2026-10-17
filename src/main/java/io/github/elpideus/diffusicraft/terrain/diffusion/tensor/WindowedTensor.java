/*
 * Adapted from terrain-diffusion-mc by Alexander Goslin (MIT License),
 * https://github.com/xandergos/terrain-diffusion-mc. See THIRD_PARTY_NOTICES.md.
 */
package io.github.elpideus.diffusicraft.terrain.diffusion.tensor;

import io.github.elpideus.diffusicraft.terrain.diffusion.tensor.TileGrid.PixelRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A lazily computed, unbounded 2D tensor made of overlapping windows. Reading a slice computes every window
 * that intersects it (and, recursively, the dependency windows those need) and sums the overlapping window
 * contributions. Pipeline stages store {@code value * weight} plus a weight channel, so the sum is a blend.
 *
 * <h2>Concurrency</h2>
 * Many world-generation threads read concurrently. Each window has one cache entry holding a future:
 * <ol>
 *     <li>Under the lock, a reader claims every missing window it needs by inserting an incomplete entry,
 *     and collects the entries that already exist.</li>
 *     <li>Outside the lock, it computes the windows it claimed (loading them from the {@link WindowStore}
 *     if possible) and completes their futures, then waits for the windows claimed by other threads.</li>
 * </ol>
 * This cannot deadlock: tensors form a DAG, a thread only waits on windows of tensor {@code T} after
 * completing its own claims on {@code T}, and computing a window only waits on windows of tensors strictly
 * upstream of it. A failed computation removes its entries so a later read retries.
 * Readers keep strong references to the windows they use, so eviction never invalidates a slice in progress.
 */
public final class WindowedTensor {
	private static final Logger LOGGER = LoggerFactory.getLogger(WindowedTensor.class);

	/** Computes a batch of windows. {@code deps.get(d).get(b)} is the slice of dependency {@code d} for window {@code b}. */
	@FunctionalInterface
	public interface WindowFunction {
		List<Tile> compute(List<WindowIndex> windows, List<List<Tile>> deps);
	}

	public record WindowIndex(int i, int j) {
	}

	/** A dependency tensor, sliced with {@code grid}'s window {@code (i, j)} for this tensor's window {@code (i, j)}. */
	public record Dependency(WindowedTensor tensor, TileGrid grid) {
	}

	private final String id;
	private final int channels;
	private final TileGrid grid;
	private final List<Dependency> deps;
	private final WindowFunction function;
	private final int batchSize;
	private final long cacheLimitBytes;
	private final WindowStore store;

	private final Object lock = new Object();
	private final LinkedHashMap<Long, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
	private long cachedBytes;
	private final AtomicLong computedWindows = new AtomicLong();

	/**
	 * @param id              stable name, also the {@link WindowStore} directory
	 * @param channels        channel count of every window
	 * @param grid            output window layout
	 * @param deps            upstream tensors
	 * @param function        window computation
	 * @param batchSize       maximum windows per {@code function} call
	 * @param cacheLimitBytes soft in-memory limit; completed windows are evicted least-recently-used first
	 * @param store           persistent cache, or {@code null}
	 */
	public WindowedTensor(String id, int channels, TileGrid grid, List<Dependency> deps, WindowFunction function,
						  int batchSize, long cacheLimitBytes, WindowStore store) {
		this.id = id;
		this.channels = channels;
		this.grid = grid;
		this.deps = List.copyOf(deps);
		this.function = function;
		this.batchSize = Math.max(1, batchSize);
		this.cacheLimitBytes = cacheLimitBytes;
		this.store = store;
	}

	public String id() {
		return id;
	}

	public int channels() {
		return channels;
	}

	/** Number of windows computed by inference (not loaded from the store) since creation. */
	public long computedWindows() {
		return computedWindows.get();
	}

	/** Sum of all window contributions over {@code [i0, i1) x [j0, j1)}, shape {@code [channels][i1-i0][j1-j0]}. */
	public Tile slice(int i0, int j0, int i1, int j1) {
		PixelRange range = new PixelRange(i0, j0, i1, j1);
		return accumulate(ensure(List.of(range)), range);
	}

	/** Drops every cached window (the persistent store is untouched). */
	public void clearMemory() {
		synchronized (lock) {
			cache.entrySet().removeIf(e -> e.getValue().future.isDone());
			cachedBytes = 0;
			for (Entry entry : cache.values()) {
				cachedBytes += entry.bytes;
			}
		}
	}

	private static long key(int wi, int wj) {
		return ((long) wi << 32) | (wj & 0xFFFFFFFFL);
	}

	private static int keyI(long key) {
		return (int) (key >> 32);
	}

	private static int keyJ(long key) {
		return (int) key;
	}

	/** Returns every window intersecting any of {@code ranges}, computing missing ones. */
	Map<Long, Tile> ensure(List<PixelRange> ranges) {
		LinkedHashSet<Long> needed = new LinkedHashSet<>();
		for (PixelRange range : ranges) {
			int wi0 = grid.first(range.i0()), wi1 = grid.last(range.i1());
			int wj0 = grid.first(range.j0()), wj1 = grid.last(range.j1());
			for (int wi = wi0; wi <= wi1; wi++) {
				for (int wj = wj0; wj <= wj1; wj++) {
					needed.add(key(wi, wj));
				}
			}
		}

		Map<Long, Tile> result = HashMap.newHashMap(needed.size());
		LinkedHashMap<Long, Entry> claimed = new LinkedHashMap<>();
		List<Map.Entry<Long, Entry>> waiting = new ArrayList<>();
		synchronized (lock) {
			for (long key : needed) {
				Entry entry = cache.get(key);
				if (entry == null) {
					entry = new Entry();
					cache.put(key, entry);
					claimed.put(key, entry);
				} else if (entry.future.isDone() && !entry.future.isCompletedExceptionally()) {
					result.put(key, entry.future.join());
				} else {
					waiting.add(Map.entry(key, entry));
				}
			}
		}

		if (!claimed.isEmpty()) {
			computeClaimed(claimed, result);
		}
		for (Map.Entry<Long, Entry> wait : waiting) {
			result.put(wait.getKey(), await(wait.getValue().future));
		}
		return result;
	}

	private void computeClaimed(LinkedHashMap<Long, Entry> claimed, Map<Long, Tile> result) {
		LinkedHashMap<Long, Entry> pending = new LinkedHashMap<>(claimed);
		try {
			if (store != null) {
				Iterator<Map.Entry<Long, Entry>> it = pending.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<Long, Entry> e = it.next();
					Tile stored = loadQuietly(keyI(e.getKey()), keyJ(e.getKey()));
					if (stored != null) {
						complete(e.getValue(), stored);
						result.put(e.getKey(), stored);
						it.remove();
					}
				}
			}
			if (pending.isEmpty()) {
				return;
			}

			List<Long> keys = new ArrayList<>(pending.keySet());
			// Resolve every dependency window for all pending windows at once so upstream stages can batch too.
			List<List<PixelRange>> depRanges = new ArrayList<>(deps.size());
			List<Map<Long, Tile>> depWindows = new ArrayList<>(deps.size());
			for (Dependency dep : deps) {
				List<PixelRange> ranges = new ArrayList<>(keys.size());
				for (long key : keys) {
					ranges.add(dep.grid().window(keyI(key), keyJ(key)));
				}
				depRanges.add(ranges);
				depWindows.add(dep.tensor().ensure(ranges));
			}

			for (int from = 0; from < keys.size(); from += batchSize) {
				int to = Math.min(from + batchSize, keys.size());
				List<WindowIndex> batch = new ArrayList<>(to - from);
				for (int k = from; k < to; k++) {
					batch.add(new WindowIndex(keyI(keys.get(k)), keyJ(keys.get(k))));
				}
				List<List<Tile>> args = new ArrayList<>(deps.size());
				for (int d = 0; d < deps.size(); d++) {
					WindowedTensor depTensor = deps.get(d).tensor();
					List<Tile> slices = new ArrayList<>(to - from);
					for (int k = from; k < to; k++) {
						slices.add(depTensor.accumulate(depWindows.get(d), depRanges.get(d).get(k)));
					}
					args.add(slices);
				}

				List<Tile> outputs = function.compute(batch, args);
				if (outputs.size() != batch.size()) {
					throw new IllegalStateException(id + ": function returned " + outputs.size() + " windows for a batch of " + batch.size());
				}
				for (int k = 0; k < outputs.size(); k++) {
					Tile tile = outputs.get(k);
					if (tile.channels != channels || tile.height != grid.size() || tile.width != grid.size()) {
						throw new IllegalStateException(id + ": function returned a " + tile.channels + "x" + tile.height + "x" + tile.width
							+ " window, expected " + channels + "x" + grid.size() + "x" + grid.size());
					}
					long key = keys.get(from + k);
					computedWindows.incrementAndGet();
					saveQuietly(keyI(key), keyJ(key), tile);
					complete(pending.remove(key), tile);
					result.put(key, tile);
				}
			}
		} catch (Throwable t) {
			fail(pending, t);
			throw t;
		}
	}

	private void complete(Entry entry, Tile tile) {
		synchronized (lock) {
			entry.bytes = tile.byteSize();
			cachedBytes += entry.bytes;
			evictIfNeeded();
		}
		entry.future.complete(tile);
	}

	private void fail(Map<Long, Entry> entries, Throwable cause) {
		synchronized (lock) {
			for (Map.Entry<Long, Entry> e : entries.entrySet()) {
				cache.remove(e.getKey(), e.getValue());
			}
		}
		for (Entry entry : entries.values()) {
			entry.future.completeExceptionally(cause);
		}
	}

	/** Must hold {@link #lock}. Never evicts in-flight entries. */
	private void evictIfNeeded() {
		if (cachedBytes <= cacheLimitBytes) {
			return;
		}
		Iterator<Entry> it = cache.values().iterator();
		while (cachedBytes > cacheLimitBytes && it.hasNext()) {
			Entry entry = it.next();
			if (entry.future.isDone()) {
				cachedBytes -= entry.bytes;
				it.remove();
			}
		}
	}

	private static Tile await(CompletableFuture<Tile> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}

	/** Sums the contributions of {@code windows} (which must cover {@code range}) into a new tile. */
	Tile accumulate(Map<Long, Tile> windows, PixelRange range) {
		int h = range.height(), w = range.width();
		int size = grid.size();
		int plane = size * size;
		Tile out = new Tile(channels, h, w);
		float[] dst = out.data;
		for (int wi = grid.first(range.i0()), wiEnd = grid.last(range.i1()); wi <= wiEnd; wi++) {
			int top = grid.start(wi);
			int r0 = Math.max(range.i0(), top), r1 = Math.min(range.i1(), top + size);
			for (int wj = grid.first(range.j0()), wjEnd = grid.last(range.j1()); wj <= wjEnd; wj++) {
				int left = grid.start(wj);
				int c0 = Math.max(range.j0(), left), c1 = Math.min(range.j1(), left + size);
				Tile window = windows.get(key(wi, wj));
				if (window == null) {
					throw new IllegalStateException(id + ": window (" + wi + ", " + wj + ") was not resolved");
				}
				float[] src = window.data;
				int len = c1 - c0;
				for (int ch = 0; ch < channels; ch++) {
					for (int r = r0; r < r1; r++) {
						int s = ch * plane + (r - top) * size + (c0 - left);
						int d = (ch * h + (r - range.i0())) * w + (c0 - range.j0());
						for (int k = 0; k < len; k++) {
							dst[d + k] += src[s + k];
						}
					}
				}
			}
		}
		return out;
	}

	private Tile loadQuietly(int wi, int wj) {
		try {
			return store.load(id, wi, wj, channels, grid.size());
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read cached window {} ({}, {}): {}", id, wi, wj, e.toString());
			return null;
		}
	}

	private void saveQuietly(int wi, int wj, Tile tile) {
		if (store == null) {
			return;
		}
		try {
			store.save(id, wi, wj, tile);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not write cached window {} ({}, {}): {}", id, wi, wj, e.toString());
		}
	}

	private static final class Entry {
		final CompletableFuture<Tile> future = new CompletableFuture<>();
		/** Guarded by the tensor lock; zero until completed. */
		long bytes;
	}
}
