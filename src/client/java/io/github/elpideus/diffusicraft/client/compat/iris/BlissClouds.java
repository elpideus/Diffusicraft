package io.github.elpideus.diffusicraft.client.compat.iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raises Bliss's clouds with the dimension's, as Complementary's rise already. Bliss flies its three layers at heights
 * of its own, {@code CloudLayer0_height} to {@code CloudLayer2_height} (250, 500 and 2000 by default), whatever the
 * dimension, and a Diffusicraft world's land runs through them. Each height moves up by as much as the dimension raises
 * its clouds above vanilla's, which Iris's {@code cloudHeight} uniform tells as the pack runs; in vanilla's dimensions
 * that is nothing. The patch runs on each program's source once its options are set and its files included, before
 * Iris's preprocessor ({@code JcppProcessor}), which hoists {@code #version} above the uniform it declares.
 *
 * <p>A source that names {@code cloudHeight} itself, or that tests the heights in the preprocessor or folds them into
 * a constant, where a uniform cannot go, is left as it is.
 */
public final class BlissClouds {
	private static final Pattern HEIGHT =
		Pattern.compile("^([ \\t]*#[ \\t]*define[ \\t]+CloudLayer[012]_height[ \\t]+)([^\\s/]+)", Pattern.MULTILINE);
	private static final Pattern UNSAFE = Pattern.compile("\\bcloudHeight\\b"
		+ "|^[ \\t]*#[ \\t]*(?:el)?if\\b.*\\bCloudLayer[012]_height\\b"
		+ "|\\bconst\\b[^;{}]*\\bCloudLayer[012]_height\\b", Pattern.MULTILINE);
	/** What Iris's {@code cloudHeight} is under vanilla's clouds. */
	private static final String VANILLA_HEIGHT = "192.33";

	private BlissClouds() {
	}

	/** {@code source}, with Bliss's heights of the clouds moved with the dimension's, if it defines them. */
	public static String patch(String source) {
		Matcher height = HEIGHT.matcher(source);
		if (!height.find() || UNSAFE.matcher(source).find()) {
			return source;
		}
		return "uniform float cloudHeight;\n"
			+ height.replaceAll("$1(float($2) + cloudHeight - " + VANILLA_HEIGHT + ")");
	}
}
