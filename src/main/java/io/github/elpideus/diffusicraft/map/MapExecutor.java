package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daemon threads running the map's tasks: what someone clicked first, then the tiles in the order they were asked for,
 * since the page asks for the nearest first and drops those that leave the screen, and background tasks once nothing
 * else waits.
 */
final class MapExecutor implements AutoCloseable {
	private static final int NOW = 0;
	private static final int IN_TURN = 1;
	private static final int LAST = 2;

	private record Task(int rank, long order, Runnable run) implements Comparable<Task> {
		@Override
		public int compareTo(Task other) {
			return rank != other.rank ? Integer.compare(rank, other.rank) : Long.compare(order, other.order);
		}
	}

	private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
	private final AtomicLong order = new AtomicLong();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean closed;

	MapExecutor(String name, int threads) {
		for (int i = 0; i < threads; i++) {
			Thread thread = Thread.ofPlatform().daemon().name(name + " " + (i + 1)).priority(Thread.MIN_PRIORITY)
				.unstarted(this::work);
			this.threads.add(thread);
			thread.start();
		}
	}

	/** Runs the task before every other one that waits, but for those submitted this way earlier. */
	void submitNow(Runnable task) {
		add(NOW, task);
	}

	/** Runs the task after those asked for so far, and before the background ones. */
	void submit(Runnable task) {
		add(IN_TURN, task);
	}

	/** Runs the task after every one queued so far, and every one submitted later but for other background tasks. */
	void submitLast(Runnable task) {
		add(LAST, task);
	}

	private void add(int rank, Runnable task) {
		if (!closed) {
			queue.offer(new Task(rank, order.getAndIncrement(), task));
		}
	}

	private void work() {
		while (!closed) {
			Runnable task;
			try {
				task = queue.take().run;
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
			queue.offer(new Task(NOW, order.getAndIncrement(), () -> {
			}));
		}
	}
}
