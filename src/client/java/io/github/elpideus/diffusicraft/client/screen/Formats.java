package io.github.elpideus.diffusicraft.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.Locale;

/**
 * Sizes, rates, durations and text clipping for the resource screens. Download and disk sizes use decimal units, like
 * browsers and file managers; GPU memory uses binary units, like GPU spec sheets.
 */
public final class Formats {
	private static final Component ELLIPSIS = Component.literal("…");

	private Formats() {
	}

	public static String bytes(long bytes) {
		if (bytes < 1_000_000) {
			return String.format(Locale.ROOT, "%d KB", Math.max(bytes > 0 ? 1 : 0, Math.round(bytes / 1e3)));
		}
		if (bytes < 1_000_000_000) {
			return String.format(Locale.ROOT, "%d MB", Math.round(bytes / 1e6));
		}
		return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
	}

	/** Memory in binary gigabytes, the way GPUs are labelled: a "12 GB" card has 12 GiB. */
	public static String memory(long bytes) {
		double gib = bytes / (double) (1L << 30);
		return gib == Math.rint(gib) ? String.format(Locale.ROOT, "%d GB", (long) gib) : String.format(Locale.ROOT, "%.1f GB", gib);
	}

	public static String rate(double bytesPerSecond) {
		return bytesPerSecond >= 1e6
			? String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1e6)
			: String.format(Locale.ROOT, "%d KB/s", Math.round(bytesPerSecond / 1e3));
	}

	/** {@code 0:42}, {@code 12:05} or {@code 1:02:03}. */
	public static String duration(long seconds) {
		long h = seconds / 3600;
		long m = seconds / 60 % 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	/** {@code text}, cut to {@code width} pixels with an ellipsis when it does not fit. */
	public static FormattedCharSequence clip(Font font, Component text, int width) {
		if (width <= 0) {
			return FormattedCharSequence.EMPTY;
		}
		if (font.width(text) <= width) {
			return text.getVisualOrderText();
		}
		FormattedText cut = font.substrByWidth(text, width - font.width(ELLIPSIS));
		return Language.getInstance().getVisualOrder(FormattedText.composite(cut, ELLIPSIS));
	}

	/** A one-line description of a failure for the UI: the innermost message, without the exception class. */
	public static String error(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return message == null || message.isBlank() ? root.getClass().getSimpleName() : message.strip();
	}
}
