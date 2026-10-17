package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What the machine offers for inference: GPUs (vendor, name, memory, driver), CPU threads and memory.
 * Probing is best effort and takes up to a few seconds ({@code nvidia-smi}); run it off the render thread.
 */
public record Hardware(List<Gpu> gpus, int cpuThreads, long memoryBytes) {
	private static final Logger LOGGER = LoggerFactory.getLogger(Hardware.class);

	public enum Vendor {
		NVIDIA, AMD, INTEL, APPLE, OTHER
	}

	/**
	 * @param vramBytes         dedicated memory, or 0 when unknown
	 * @param driver            driver version, or an empty string when unknown
	 * @param computeCapability NVIDIA compute capability times ten (86 for 8.6), or 0 when unknown or not NVIDIA
	 */
	public record Gpu(Vendor vendor, String name, long vramBytes, String driver, int computeCapability) {
		/** Leading number of the driver version, e.g. 580 for {@code 580.65.06}; 0 when unknown. */
		public int driverMajor() {
			int end = 0;
			while (end < driver.length() && Character.isDigit(driver.charAt(end))) {
				end++;
			}
			return end == 0 ? 0 : Integer.parseInt(driver.substring(0, end));
		}
	}

	public Optional<Gpu> first(Vendor vendor) {
		return gpus.stream().filter(g -> g.vendor() == vendor).findFirst();
	}

	public boolean has(Vendor vendor) {
		return first(vendor).isPresent();
	}

	public static Hardware probe(Platform platform) {
		List<Gpu> gpus = new ArrayList<>();
		try {
			gpus.addAll(nvidiaSmi(platform));
			if (platform.isLinux()) {
				gpus.addAll(linuxDrm(gpus.stream().anyMatch(g -> g.vendor() == Vendor.NVIDIA)));
			} else if (platform.isMac()) {
				gpus.add(new Gpu(Vendor.APPLE, "Apple Silicon", 0, "", 0));
			}
		} catch (RuntimeException e) {
			LOGGER.warn("GPU detection failed: {}", e.toString());
		}
		long memory = 0;
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			memory = os.getTotalMemorySize();
		}
		return new Hardware(List.copyOf(gpus), Runtime.getRuntime().availableProcessors(), memory);
	}

	private static List<Gpu> nvidiaSmi(Platform platform) {
		List<Gpu> gpus = nvidiaSmi(platform, "name,memory.total,driver_version,compute_cap");
		// Drivers older than R510 do not know compute_cap.
		return gpus.isEmpty() ? nvidiaSmi(platform, "name,memory.total,driver_version") : gpus;
	}

	private static List<Gpu> nvidiaSmi(Platform platform, String fields) {
		List<String> command = List.of(platform.isWindows() ? "nvidia-smi.exe" : "nvidia-smi",
			"--query-gpu=" + fields, "--format=csv,noheader,nounits");
		List<Gpu> result = new ArrayList<>();
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return result;
			}
			if (process.exitValue() != 0) {
				return result;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			for (String line : output.split("\\R")) {
				String[] parts = line.split(",");
				if (parts.length < 3) {
					continue;
				}
				long vramMb = parseLong(parts[1].strip());
				int computeCapability = parts.length > 3 ? parseComputeCapability(parts[3].strip()) : 0;
				result.add(new Gpu(Vendor.NVIDIA, parts[0].strip(), vramMb << 20, parts[2].strip(), computeCapability));
			}
		} catch (IOException e) {
			// No NVIDIA driver installed.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return result;
	}

	/** GPUs from {@code /sys/class/drm}; NVIDIA cards are skipped when nvidia-smi already listed them. */
	private static List<Gpu> linuxDrm(boolean haveNvidia) {
		List<Gpu> result = new ArrayList<>();
		Path drm = Path.of("/sys/class/drm");
		if (!Files.isDirectory(drm)) {
			return result;
		}
		Set<Path> seen = new HashSet<>();
		try (DirectoryStream<Path> cards = Files.newDirectoryStream(drm, "card[0-9]*")) {
			for (Path card : cards) {
				if (card.getFileName().toString().contains("-")) {
					continue;
				}
				Path device = card.resolve("device");
				try {
					if (!seen.add(device.toRealPath())) {
						continue;
					}
				} catch (IOException e) {
					continue;
				}
				String vendorId = read(device.resolve("vendor")).toLowerCase(Locale.ROOT);
				Vendor vendor = switch (vendorId) {
					case "0x10de" -> Vendor.NVIDIA;
					case "0x1002" -> Vendor.AMD;
					case "0x8086" -> Vendor.INTEL;
					default -> Vendor.OTHER;
				};
				if (vendor == Vendor.NVIDIA && haveNvidia) {
					continue;
				}
				long vram = parseLong(read(device.resolve("mem_info_vram_total")));
				String name = switch (vendor) {
					case NVIDIA -> "NVIDIA GPU";
					case AMD -> "AMD Radeon GPU";
					case INTEL -> "Intel GPU";
					default -> "GPU " + vendorId;
				};
				String driver = vendor == Vendor.NVIDIA ? nvidiaProcDriver() : "";
				result.add(new Gpu(vendor, name, vram, driver, 0));
			}
		} catch (IOException e) {
			LOGGER.debug("Could not list {}: {}", drm, e.toString());
		}
		return result;
	}

	/** Driver version from {@code /proc/driver/nvidia/version} when nvidia-smi is unavailable. */
	private static String nvidiaProcDriver() {
		String text = read(Path.of("/proc/driver/nvidia/version"));
		for (String token : text.split("\\s+")) {
			if (token.matches("\\d+\\.\\d+(\\.\\d+)?")) {
				return token;
			}
		}
		return "";
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8).strip();
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	/** {@code "8.6"} to 86; 0 when unparsable. */
	static int parseComputeCapability(String text) {
		int dot = text.indexOf('.');
		if (dot <= 0 || dot == text.length() - 1) {
			return 0;
		}
		long major = parseLong(text.substring(0, dot));
		long minor = parseLong(text.substring(dot + 1));
		return major <= 0 || minor < 0 || minor > 9 ? 0 : (int) (major * 10 + minor);
	}

	private static long parseLong(String text) {
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
