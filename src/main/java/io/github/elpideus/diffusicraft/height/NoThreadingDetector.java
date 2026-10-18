package io.github.elpideus.diffusicraft.height;

import net.minecraft.util.ThreadingDetector;

/**
 * The threading detector of every paletted container: one instance that checks nothing.
 *
 * <p>Vanilla gives each container a {@link ThreadingDetector} with a semaphore and a lock, about 128 bytes, which is
 * more than half of what an empty section costs. It never serialises anything: when two threads use a container at
 * once, it crashes the game with both stack traces. Lithium removes it by default for speed; Diffusicraft replaces it
 * so that tall dimensions fit in memory.
 */
public final class NoThreadingDetector extends ThreadingDetector {
	public static final ThreadingDetector INSTANCE = new NoThreadingDetector();

	private NoThreadingDetector() {
		super("PalettedContainer");
	}

	@Override
	public void checkAndLock() {
	}

	@Override
	public void checkAndUnlock() {
	}
}
