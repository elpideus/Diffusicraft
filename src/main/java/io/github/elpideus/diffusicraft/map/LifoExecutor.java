package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Daemon threads running the newest task first: someone panning the map wants the tiles of where they are now before
 * those of where they were. Background tasks wait until nothing else is queued.
 */
final class LifoExecutor implements AutoCloseable {
	private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean closed;

	LifoExecutor(String name, int threads) {
		for (int i = 0; i < threads; i++) {
			Thread thread = Thread.ofPlatform().daemon().name(name + " " + (i + 1)).priority(Thread.MIN_PRIORITY)
				.unstarted(this::work);
			this.threads.add(thread);
			thread.start();
		}
	}

	/** Runs the task before every one queued so far. */
	void submit(Runnable task) {
		if (!closed) {
			queue.offerFirst(task);
		}
	}

	/** Runs the task after every one queued so far. */
	void submitLast(Runnable task) {
		if (!closed) {
			queue.offerLast(task);
		}
	}

	private void work() {
		while (!closed) {
			Runnable task;
			try {
				task = queue.takeFirst();
			} catch (InterruptedException e) {
				return;
			}
			try {
				task.run();
			} catch (Throwable t) {
				if (!closed) {
					Diffusicraft.LOGGER.warn("A relief map task failed", t);
				}
			}
		}
	}

	/**
	 * Drops the queued tasks and lets the threads end. A running task finishes first: interrupting it could fail the
	 * chunks waiting on the same terrain.
	 */
	@Override
	public void close() {
		closed = true;
		queue.clear();
		for (int i = 0; i < threads.size(); i++) {
			queue.offerLast(() -> {
			});
		}
	}
}
