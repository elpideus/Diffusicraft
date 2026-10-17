package io.github.elpideus.diffusicraft.runtime;

import java.util.Locale;

/** Operating system and CPU architecture, named like ONNX Runtime's native resource folders. */
public enum Platform {
	LINUX_X64("linux-x64"),
	LINUX_AARCH64("linux-aarch64"),
	WINDOWS_X64("win-x64"),
	MACOS_AARCH64("osx-aarch64"),
	UNKNOWN("unknown");

	private static final Platform CURRENT = detect();

	private final String id;

	Platform(String id) {
		this.id = id;
	}

	public static Platform current() {
		return CURRENT;
	}

	/** Key used in the runtime manifest, e.g. {@code linux-x64}. */
	public String id() {
		return id;
	}

	public boolean isWindows() {
		return this == WINDOWS_X64;
	}

	public boolean isLinux() {
		return this == LINUX_X64 || this == LINUX_AARCH64;
	}

	public boolean isMac() {
		return this == MACOS_AARCH64;
	}

	private static Platform detect() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
		if (os.contains("linux")) {
			return x64 ? LINUX_X64 : arm64 ? LINUX_AARCH64 : UNKNOWN;
		}
		if (os.contains("windows")) {
			return x64 ? WINDOWS_X64 : UNKNOWN;
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return arm64 ? MACOS_AARCH64 : UNKNOWN;
		}
		return UNKNOWN;
	}
}
