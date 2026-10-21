package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The tiles of one level, drawn on demand and kept in memory and on disk.
 *
 * <p>Zoom levels up to {@link MapLevel#fineZoom} are drawn from the full-resolution terrain, which runs the models
 * over the land they show the first time. Coarser levels are drawn from the coarse map, which costs next to nothing,
 * and the finer tiles drawn so far are laid over it, shrunk: once someone has looked at a place closely, it stays sharp
 * as they zoom out. A tile with finer ones under it is a mix of the two. It changes each time one more is drawn, and
 * {@link #changes} tells the page which to fetch again.
 *
 * <p>A request whose browser gave up before its tile was started is dropped. A tile finer than
 * {@link MapLevel#fineZoom} also draws, once nothing else waits, the tile at that zoom level over it, so that zooming
 * out keeps it.
 */
final class MapTiles {
	private static final long MEMORY_BYTES = 48L << 20;
	/** Tiles drawn at the fine zoom level that {@link #changes} remembers. */
	private static final int CHANGES = 4096;

	enum Kind {
		FINE('f'), COARSE('c'), MIXED('m');

		final char letter;

		Kind(char letter) {
			this.letter = letter;
		}
	}

	record Key(Kind kind, int zoom, int x, int y) {
	}

	/** Tiles drawn at the fine zoom level since a revision, or {@code all} if more than are remembered. */
	record Changes(long revision, boolean all, List<int[]> tiles) {
	}

	/** A request's hold on a tile: the tile is dropped if every request gives up on it before it is started. */
	final class Ticket {
		final CompletableFuture<byte[]> tile;
		private final @Nullable Job job;
		private boolean released;

		private Ticket(CompletableFuture<byte[]> tile, @Nullable Job job) {
			this.tile = tile;
			this.job = job;
		}

		void release() {
			if (job != null) {
				synchronized (MapTiles.this) {
					if (!released) {
						released = true;
						job.interest--;
					}
				}
			}
		}
	}

	private static final class Job {
		final Key key;
		final CompletableFuture<byte[]> tile = new CompletableFuture<>();
		boolean background;
		boolean started;
		int interest;

		Job(Key key, boolean background) {
			this.key = key;
			this.background = background;
		}
	}

	private final MapLevel level;
	private final @Nullable Path dir;
	private final LifoExecutor fine;
	private final LifoExecutor fast;
	private final int fineZoom;
	private final int maxZoom;
	/** For each zoom level coarser than the fine one, the tiles with fine ones under them. */
	private final List<Set<Long>> detailed = new ArrayList<>();
	private final Map<Key, Job> jobs = new HashMap<>();
	private final Map<Key, CompletableFuture<byte[]>> running = new ConcurrentHashMap<>();
	/** The revision at which each mixed tile was last made stale. */
	private final Map<Key, Long> stale = new ConcurrentHashMap<>();
	private final LinkedHashMap<Key, byte[]> memory = new LinkedHashMap<>(256, 0.75F, true);
	private long memoryBytes;
	private long revision;
	private final ArrayDeque<long[]> log = new ArrayDeque<>();
	private boolean warned;

	/** Tiles of {@code level}, kept under {@code dir} if it is not null, drawn on the two executors. */
	MapTiles(MapLevel level, @Nullable Path dir, LifoExecutor fine, LifoExecutor fast) {
		this.level = level;
		this.dir = dir;
		this.fine = fine;
		this.fast = fast;
		this.fineZoom = level.fineZoom();
		this.maxZoom = level.maxZoom();
		for (int z = 0; z <= maxZoom; z++) {
			detailed.add(ConcurrentHashMap.newKeySet());
		}
		if (dir != null) {
			loadIndex(dir.resolve(String.valueOf(Kind.FINE.letter) + fineZoom));
		}
	}

	private void loadIndex(Path fineDir) {
		if (!Files.isDirectory(fineDir)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(fineDir, "*.dmap")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				int sep = name.indexOf('_');
				try {
					markDetailed(Integer.parseInt(name.substring(0, sep)),
						Integer.parseInt(name.substring(sep + 1, name.length() - 5)));
				} catch (RuntimeException e) {
					// Not one of ours.
				}
			}
		} catch (IOException e) {
			Diffusicraft.LOGGER.warn("Could not list the relief map's tiles in {}", fineDir, e);
		}
	}

	MapLevel level() {
		return level;
	}

	/** Asks for a tile. Release the ticket once the answer is sent or no longer wanted. */
	Ticket request(int zoom, int x, int y) {
		Key key = key(zoom, x, y);
		byte[] cached = cached(key);
		if (cached != null) {
			return new Ticket(CompletableFuture.completedFuture(cached), null);
		}
		synchronized (this) {
			Job job = jobs.get(key);
			if (job == null) {
				job = queue(key, false);
			} else if (job.background && !job.started) {
				// Someone waits for it now: draw it before the rest of the background.
				job.background = false;
				Job promoted = job;
				executor(key).submit(() -> run(promoted));
			}
			job.interest++;
			return new Ticket(job.tile, job);
		}
	}

	/** The tiles drawn at the fine zoom level since {@code since}. */
	synchronized Changes changes(long since) {
		if (since >= revision) {
			return new Changes(revision, false, List.of());
		}
		long[] oldest = log.peekFirst();
		if (oldest == null || since < oldest[0] - 1) {
			return new Changes(revision, true, List.of());
		}
		List<int[]> tiles = new ArrayList<>();
		for (Iterator<long[]> it = log.descendingIterator(); it.hasNext(); ) {
			long[] change = it.next();
			if (change[0] <= since) {
				break;
			}
			tiles.add(new int[]{(int) change[1], (int) change[2]});
		}
		return new Changes(revision, false, tiles);
	}

	private Key key(int zoom, int x, int y) {
		if (zoom <= fineZoom) {
			return new Key(Kind.FINE, zoom, x, y);
		}
		return new Key(detailed.get(zoom).contains(pack(x, y)) ? Kind.MIXED : Kind.COARSE, zoom, x, y);
	}

	private Job queue(Key key, boolean background) {
		Job job = new Job(key, background);
		jobs.put(key, job);
		if (background) {
			executor(key).submitLast(() -> run(job));
		} else {
			executor(key).submit(() -> run(job));
		}
		return job;
	}

	private LifoExecutor executor(Key key) {
		return key.kind == Kind.FINE ? fine : fast;
	}

	private void run(Job job) {
		synchronized (this) {
			if (job.started) {
				// Promoted, and run from the front of the queue already.
				return;
			}
			job.started = true;
			if (job.interest <= 0 && !job.background) {
				jobs.remove(job.key, job);
				job.tile.completeExceptionally(new CancellationException("Nobody waits for it"));
				return;
			}
		}
		try {
			job.tile.complete(compute(job.key));
		} catch (Throwable t) {
			job.tile.completeExceptionally(t);
		} finally {
			synchronized (this) {
				jobs.remove(job.key, job);
			}
		}
	}

	/** The tile, drawn on this thread unless it is kept or another thread is drawing it. */
	private byte[] compute(Key key) throws IOException {
		byte[] cached = cached(key);
		if (cached != null) {
			return cached;
		}
		CompletableFuture<byte[]> mine = new CompletableFuture<>();
		CompletableFuture<byte[]> other = running.putIfAbsent(key, mine);
		if (other != null) {
			return other.join();
		}
		try {
			long since;
			synchronized (this) {
				since = revision;
			}
			MapRaster raster = switch (key.kind) {
				case FINE -> level.render(key.zoom, key.x, key.y, false);
				case COARSE -> level.render(key.zoom, key.x, key.y, true);
				case MIXED -> mix(key);
			};
			byte[] bytes = raster.encode();
			// A mixed tile made stale while it was drawn misses the newest tile under it.
			if (key.kind != Kind.MIXED || stale.getOrDefault(key, Long.MIN_VALUE) <= since) {
				store(key, bytes);
			}
			mine.complete(bytes);
			if (key.kind == Kind.FINE) {
				drawn(key);
			}
			return bytes;
		} catch (Throwable t) {
			mine.completeExceptionally(t);
			throw t;
		} finally {
			running.remove(key, mine);
		}
	}

	/** The coarse tile with the finer ones drawn so far laid over its quarters. */
	private MapRaster mix(Key key) throws IOException {
		MapRaster tile = MapRaster.decode(compute(new Key(Kind.COARSE, key.zoom, key.x, key.y)));
		int child = key.zoom - 1;
		for (int q = 0; q < 4; q++) {
			int cx = 2 * key.x + (q & 1), cy = 2 * key.y + (q >> 1);
			byte[] bytes;
			if (child == fineZoom) {
				bytes = cached(new Key(Kind.FINE, child, cx, cy));
			} else {
				bytes = detailed.get(child).contains(pack(cx, cy)) ? compute(new Key(Kind.MIXED, child, cx, cy)) : null;
			}
			if (bytes != null) {
				tile.downsample(MapRaster.decode(bytes), q);
			}
		}
		return tile;
	}

	/** Records a fine tile: the coarser ones over it change, and a tile finer still draws the fine one over it. */
	private void drawn(Key key) {
		if (key.zoom == fineZoom) {
			long rev;
			synchronized (this) {
				rev = ++revision;
				log.addLast(new long[]{rev, key.x, key.y});
				if (log.size() > CHANGES) {
					log.removeFirst();
				}
			}
			markDetailed(key.x, key.y);
			for (int z = fineZoom + 1; z <= maxZoom; z++) {
				int shift = z - fineZoom;
				Key mixed = new Key(Kind.MIXED, z, key.x >> shift, key.y >> shift);
				stale.put(mixed, rev);
				forget(mixed);
			}
		} else {
			int shift = fineZoom - key.zoom;
			Key over = new Key(Kind.FINE, fineZoom, key.x >> shift, key.y >> shift);
			if (cached(over) == null) {
				synchronized (this) {
					if (!jobs.containsKey(over)) {
						queue(over, true);
					}
				}
			}
		}
	}

	private void markDetailed(int x, int y) {
		for (int z = fineZoom + 1; z <= maxZoom; z++) {
			int shift = z - fineZoom;
			detailed.get(z).add(pack(x >> shift, y >> shift));
		}
	}

	private static long pack(int x, int y) {
		return (long) x << 32 | (y & 0xffffffffL);
	}

	private @Nullable byte[] cached(Key key) {
		synchronized (memory) {
			byte[] bytes = memory.get(key);
			if (bytes != null) {
				return bytes;
			}
		}
		if (dir == null) {
			return null;
		}
		try {
			byte[] bytes = Files.readAllBytes(file(key));
			remember(key, bytes);
			return bytes;
		} catch (NoSuchFileException e) {
			return null;
		} catch (IOException e) {
			warn("read", e);
			return null;
		}
	}

	private void store(Key key, byte[] bytes) {
		remember(key, bytes);
		if (dir == null) {
			return;
		}
		Path file = file(key);
		try {
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			try {
				Files.write(temp, bytes);
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException e) {
			warn("write", e);
		}
	}

	private void forget(Key key) {
		synchronized (memory) {
			byte[] bytes = memory.remove(key);
			if (bytes != null) {
				memoryBytes -= bytes.length;
			}
		}
		if (dir != null) {
			try {
				Files.deleteIfExists(file(key));
			} catch (IOException e) {
				warn("delete", e);
			}
		}
	}

	private void remember(Key key, byte[] bytes) {
		synchronized (memory) {
			byte[] old = memory.put(key, bytes);
			memoryBytes += bytes.length - (old == null ? 0 : old.length);
			for (Iterator<byte[]> it = memory.values().iterator(); memoryBytes > MEMORY_BYTES && it.hasNext(); ) {
				memoryBytes -= it.next().length;
				it.remove();
			}
		}
	}

	private Path file(Key key) {
		return dir.resolve(String.valueOf(key.kind.letter) + key.zoom).resolve(key.x + "_" + key.y + ".dmap");
	}

	private void warn(String what, IOException e) {
		if (!warned) {
			warned = true;
			Diffusicraft.LOGGER.warn("Could not {} the relief map's tiles under {}", what, dir, e);
		}
	}
}
