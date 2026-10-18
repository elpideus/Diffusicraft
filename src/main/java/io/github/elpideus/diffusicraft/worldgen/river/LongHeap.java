package io.github.elpideus.diffusicraft.worldgen.river;

import java.util.Arrays;

/**
 * A binary min-heap of longs. Searches push {@link #key(float, int) keys} that order by a float cost, then by an
 * index, so equal costs always come out in the same order.
 */
final class LongHeap {
	private long[] heap;
	private int size;

	LongHeap(int capacity) {
		heap = new long[Math.max(capacity, 8)];
	}

	/** A key ordered by {@code cost}, then by {@code index}; {@code (int) key} gives the index back. */
	static long key(float cost, int index) {
		int bits = Float.floatToIntBits(cost);
		// Negative floats order backwards as ints; flipping their magnitude bits fixes that.
		bits ^= (bits >> 31) & 0x7fffffff;
		return ((long) bits << 32) | (index & 0xffffffffL);
	}

	/** The cost a key was made with. */
	static float cost(long key) {
		int bits = (int) (key >> 32);
		bits ^= (bits >> 31) & 0x7fffffff;
		return Float.intBitsToFloat(bits);
	}

	boolean isEmpty() {
		return size == 0;
	}

	void clear() {
		size = 0;
	}

	void push(long value) {
		if (size == heap.length) {
			heap = Arrays.copyOf(heap, size * 2);
		}
		int k = size++;
		while (k > 0) {
			int parent = (k - 1) >>> 1;
			if (heap[parent] <= value) {
				break;
			}
			heap[k] = heap[parent];
			k = parent;
		}
		heap[k] = value;
	}

	long pop() {
		long top = heap[0];
		long last = heap[--size];
		int k = 0, half = size >>> 1;
		while (k < half) {
			int child = 2 * k + 1;
			if (child + 1 < size && heap[child + 1] < heap[child]) {
				child++;
			}
			if (last <= heap[child]) {
				break;
			}
			heap[k] = heap[child];
			k = child;
		}
		heap[k] = last;
		return top;
	}
}
