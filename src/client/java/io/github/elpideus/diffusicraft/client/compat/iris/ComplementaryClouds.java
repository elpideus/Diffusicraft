package io.github.elpideus.diffusicraft.client.compat.iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets Complementary's clouds be seen as far as they rise with the dimension's. Complementary flies them at the height
 * Iris's {@code cloudHeight} uniform tells, but draws none farther than 4000 blocks from the camera across the ground
 * ({@code thresholdF}), and fades them out towards there. Vanilla's clouds float 129 blocks above the sea, so they end
 * about 2° above the horizon; a Diffusicraft world's float up to 36 times as high, where the same 4000 blocks would cut
 * them off 49° up and fade the rest into the sky. The reach grows with their height above the sea, as vanilla's clouds
 * do in {@code CloudScale}, so that they end as close to the horizon and fade as they do in vanilla's dimensions. The
 * march through the layer takes as many steps per ray as it does under vanilla's clouds.
 *
 * <p>The patch runs where {@link BlissClouds}'s does, and only on sources that declare Complementary's
 * {@code cloudHeightM}; in vanilla's dimensions it changes nothing.
 */
public final class ComplementaryClouds {
	private static final Pattern REACH =
		Pattern.compile("(\\bfloat[ \\t]+thresholdF[ \\t]*=[ \\t]*)([0-9]+(?:\\.[0-9]*)?)([ \\t]*;)");
	/** How many times higher above the sea the dimension's clouds float than vanilla's, as {@code CloudScale} has it. */
	private static final String SCALE = "max(1.0, (cloudHeightM - 63.0) / (192.33 - 63.0))";

	private ComplementaryClouds() {
	}

	/** {@code source}, with the reach of Complementary's clouds grown with their height, if it sets it. */
	public static String patch(String source) {
		if (!source.contains("cloudHeightM")) {
			return source;
		}
		Matcher reach = REACH.matcher(source);
		if (!reach.find()) {
			return source;
		}
		return reach.replaceAll("$1$2 * " + SCALE + "$3");
	}
}
