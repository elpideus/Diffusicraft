package io.github.elpideus.diffusicraft.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A long-running resource task (download, install) whose progress the UI and the log follow. Progress is counted
 * in bytes transferred. The worker thread updates it; any thread may read it or {@link #cancel} it.
 */
public final class Job {
	public enum State {
		QUEUED, RUNNING, DONE, FAILED, CANCELLED;

		public boolean finished() {
			return ordinal() >= DONE.ordinal();
		}
	}

	/** Thrown inside a job's work once the job has been cancelled. */
	public static final class CancelledException extends CancellationException {
		CancelledException() {
			super("Cancelled");
		}
	}

	private static final long RATE_WINDOW_NANOS = 5_000_000_000L;
	private static final long SAMPLE_INTERVAL_NANOS = 100_000_000L;

	private final String id;
	private final String title;
	private final AtomicLong done = new AtomicLong();
	private final AtomicLong total = new AtomicLong();
	private final CompletableFuture<Job> completion = new CompletableFuture<>();
	private final ArrayDeque<long[]> samples = new ArrayDeque<>();
	private volatile State state = State.QUEUED;
	private volatile String detail = "";
	private volatile Throwable error;
	private volatile boolean cancelRequested;
	private volatile Closeable activeStream;

	/**
	 * @param id    stable identifier, e.g. {@code models} or {@code runtime:cuda}
	 * @param title human-readable name for logs; the UI derives its own translated title from the id
	 */
	public Job(String id, String title) {
		this.id = id;
		this.title = title;
	}

	public String id() {
		return id;
	}

	public String title() {
		return title;
	}

	public State state() {
		return state;
	}

	/** What the job is doing right now, e.g. the file being downloaded. */
	public String detail() {
		return detail;
	}

	public long done() {
		return done.get();
	}

	public long total() {
		return total.get();
	}

	/** Why the job failed, or {@code null}. */
	public Throwable error() {
		return error;
	}

	public double fraction() {
		long t = total.get();
		return t <= 0 ? 0 : Math.clamp(done.get() / (double) t, 0, 1);
	}

	/** Completes (normally) when the job reaches any finished state. */
	public CompletableFuture<Job> completion() {
		return completion;
	}

	/** Transfer rate over the last few seconds, in bytes per second. */
	public double bytesPerSecond() {
		synchronized (samples) {
			if (samples.size() < 2) {
				return 0;
			}
			long[] first = samples.peekFirst();
			long[] last = samples.peekLast();
			long dt = last[0] - first[0];
			return dt <= 0 ? 0 : (last[1] - first[1]) * 1e9 / dt;
		}
	}

	/** Estimated seconds until done, or -1 when unknown. */
	public long secondsLeft() {
		double rate = bytesPerSecond();
		long left = total.get() - done.get();
		return rate < 1 || left < 0 ? -1 : (long) Math.ceil(left / rate);
	}

	/** Requests cancellation. A blocked network read is interrupted by closing its stream. */
	public void cancel() {
		cancelRequested = true;
		Closeable stream = activeStream;
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ignored) {
				// The worker sees the cancel flag either way.
			}
		}
	}

	public boolean cancelRequested() {
		return cancelRequested;
	}

	/** Throws {@link CancelledException} if the job was cancelled. Workers call it between units of work. */
	public void checkCancelled() {
		if (cancelRequested) {
			throw new CancelledException();
		}
	}

	public void detail(String detail) {
		this.detail = detail;
	}

	public void addTotal(long bytes) {
		total.addAndGet(bytes);
	}

	/** Adds transferred bytes (negative when a corrupt partial file is thrown away). */
	public void progress(long bytes) {
		long now = done.addAndGet(bytes);
		long time = System.nanoTime();
		synchronized (samples) {
			long[] last = samples.peekLast();
			if (last != null && time - last[0] < SAMPLE_INTERVAL_NANOS) {
				last[1] = now;
			} else {
				samples.addLast(new long[]{time, now});
			}
			while (samples.size() > 2 && time - samples.peekFirst()[0] > RATE_WINDOW_NANOS) {
				samples.removeFirst();
			}
		}
	}

	/** Registers the stream the worker is reading so that {@link #cancel} can unblock it. */
	public <T extends Closeable> T track(T stream) {
		activeStream = stream;
		if (cancelRequested) {
			cancel();
		}
		return stream;
	}

	public void untrack() {
		activeStream = null;
	}

	void start() {
		state = State.RUNNING;
	}

	void finish(State finalState, Throwable failure) {
		error = failure;
		state = finalState;
		activeStream = null;
		synchronized (samples) {
			samples.clear();
		}
		completion.complete(this);
	}

	@Override
	public String toString() {
		return "Job[" + id + ", " + state + ", " + done.get() + "/" + total.get() + "]";
	}
}
