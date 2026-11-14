package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Gives the system back the native memory freed since the last trim. glibc keeps what a thread frees in that thread's
 * arena, and the models load on whichever thread first needs them: each world loads them again, and the gigabytes
 * the last load freed stayed behind, about 4 GB more for every world opened in one run of the game until the system
 * ran out. The JVM's {@code System.trim_native_heap} trims every arena, and does nothing where the C library is not
 * glibc.
 */
public final class NativeHeap {
	private static final Logger LOGGER = LoggerFactory.getLogger(NativeHeap.class);

	private NativeHeap() {
	}

	/** Trims the native heap. Takes a moment where much is free, so never call it on the render thread. */
	public static void trim() {
		try {
			Object result = ManagementFactory.getPlatformMBeanServer().invoke(
				new ObjectName("com.sun.management:type=DiagnosticCommand"), "systemTrimNativeHeap", new Object[0],
				new String[0]);
			LOGGER.info("{}", String.valueOf(result).strip());
		} catch (JMException | RuntimeException e) {
			LOGGER.debug("Could not trim the native heap: {}", e.toString());
		}
	}
}
