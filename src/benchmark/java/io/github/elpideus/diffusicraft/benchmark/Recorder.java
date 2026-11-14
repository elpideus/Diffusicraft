package io.github.elpideus.diffusicraft.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * What the game did in one phase of the route: how long each frame and server tick took, and the slowest of them with
 * when they came. Allocates nothing per frame or tick, so that it does not show up in its own numbers.
 */
public final class Recorder {
	/** Frames and ticks a phase can hold: 30 minutes at 1,000 frames a second. */
	private static final int CAPACITY = 1 << 21;
	/** A frame longer than this is a stutter a player sees. */
	private static final long SPIKE_NANOS = 100_000_000;
	/** Spikes kept for each phase, with their times. */
	private static final int SPIKES = 256;

	private static volatile Recorder current;

	private final String name;
	private final long started = System.nanoTime();
	private final long[] frames = new long[CAPACITY];
	private final long[] ticks = new long[CAPACITY];
	/** The time between the starts of two ticks: unlike {@link #ticks}, it counts the server's tasks between them. */
	private final long[] tickGaps = new long[CAPACITY];
	private final long[] spikeTimes = new long[SPIKES];
	private final long[] spikeLengths = new long[SPIKES];
	private final long collectionsAtStart;
	private final long collectionMillisAtStart;
	private int frameCount;
	private int tickCount;
	private int tickGapCount;
	private int spikeCount;
	private long lastFrame;
	/** Starts at the start of the phase, so that a stall going on then counts. */
	private long tickStart = started;

	private Recorder(String name) {
		this.name = name;
		long[] collections = collections();
		collectionsAtStart = collections[0];
		collectionMillisAtStart = collections[1];
	}

	/** Starts recording a phase in place of the one before, which is returned. */
	static Recorder start(String name) {
		Recorder last = current;
		current = name == null ? null : new Recorder(name);
		return last;
	}

	/** The end of a frame. Render thread. */
	public static void frame() {
		Recorder recorder = current;
		if (recorder != null) {
			recorder.endFrame();
		}
	}

	private void endFrame() {
		long now = System.nanoTime();
		if (lastFrame != 0 && frameCount < CAPACITY) {
			long length = now - lastFrame;
			frames[frameCount++] = length;
			if (length >= SPIKE_NANOS && spikeCount < SPIKES) {
				spikeTimes[spikeCount] = lastFrame - started;
				spikeLengths[spikeCount++] = length;
			}
		}
		lastFrame = now;
	}

	/** The start of a server tick. Server thread. */
	static void tickStarted() {
		Recorder recorder = current;
		if (recorder != null) {
			long now = System.nanoTime();
			if (recorder.tickGapCount < CAPACITY) {
				recorder.tickGaps[recorder.tickGapCount++] = now - recorder.tickStart;
			}
			recorder.tickStart = now;
		}
	}

	/** The end of a server tick. Server thread. */
	static void tickEnded() {
		Recorder recorder = current;
		if (recorder != null && recorder.tickCount < CAPACITY) {
			recorder.ticks[recorder.tickCount++] = System.nanoTime() - recorder.tickStart;
		}
	}

	JsonObject report() {
		double seconds = (System.nanoTime() - started) / 1e9;
		JsonObject report = new JsonObject();
		report.addProperty("phase", name);
		report.addProperty("seconds", round(seconds));
		JsonObject frame = stats(frames, frameCount);
		frame.addProperty("fps", round(frameCount / seconds));
		frame.addProperty("over33ms", over(frames, frameCount, 33_000_000));
		frame.addProperty("over50ms", over(frames, frameCount, 50_000_000));
		frame.addProperty("over100ms", over(frames, frameCount, SPIKE_NANOS));
		frame.addProperty("over250ms", over(frames, frameCount, 250_000_000));
		report.add("frames", frame);
		JsonObject tick = stats(ticks, tickCount);
		tick.addProperty("tps", round(tickCount / seconds));
		tick.addProperty("over50ms", over(ticks, tickCount, 50_000_000));
		tick.addProperty("over250ms", over(ticks, tickCount, 250_000_000));
		tick.add("gaps", stats(tickGaps, tickGapCount));
		report.add("ticks", tick);
		long[] collections = collections();
		JsonObject memory = new JsonObject();
		memory.addProperty("collections", collections[0] - collectionsAtStart);
		memory.addProperty("collectionMillis", collections[1] - collectionMillisAtStart);
		Runtime runtime = Runtime.getRuntime();
		memory.addProperty("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
		memory.addProperty("heapCommittedMb", runtime.totalMemory() >> 20);
		report.add("memory", memory);
		JsonArray spikes = new JsonArray();
		for (int i = 0; i < spikeCount; i++) {
			JsonObject spike = new JsonObject();
			spike.addProperty("at", round(spikeTimes[i] / 1e9));
			spike.addProperty("ms", round(spikeLengths[i] / 1e6));
			spikes.add(spike);
		}
		report.add("spikes", spikes);
		return report;
	}

	private static JsonObject stats(long[] values, int count) {
		long[] sorted = Arrays.copyOf(values, count);
		Arrays.sort(sorted);
		JsonObject stats = new JsonObject();
		stats.addProperty("count", count);
		long sum = 0;
		for (long value : sorted) {
			sum += value;
		}
		stats.addProperty("meanMs", round(count == 0 ? 0 : sum / 1e6 / count));
		stats.addProperty("p50Ms", percentile(sorted, 0.5));
		stats.addProperty("p95Ms", percentile(sorted, 0.95));
		stats.addProperty("p99Ms", percentile(sorted, 0.99));
		stats.addProperty("p999Ms", percentile(sorted, 0.999));
		stats.addProperty("maxMs", count == 0 ? 0 : round(sorted[count - 1] / 1e6));
		return stats;
	}

	private static double percentile(long[] sorted, double fraction) {
		return sorted.length == 0 ? 0
			: round(sorted[Math.min(sorted.length - 1, (int) (sorted.length * fraction))] / 1e6);
	}

	private static int over(long[] values, int count, long nanos) {
		int over = 0;
		for (int i = 0; i < count; i++) {
			if (values[i] >= nanos) {
				over++;
			}
		}
		return over;
	}

	private static long[] collections() {
		long count = 0;
		long millis = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, bean.getCollectionCount());
			millis += Math.max(0, bean.getCollectionTime());
		}
		return new long[] {count, millis};
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
