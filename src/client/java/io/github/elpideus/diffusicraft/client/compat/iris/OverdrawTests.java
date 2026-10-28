package io.github.elpideus.diffusicraft.client.compat.iris;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Disarms a shader pack's own tests that leave out Distant Horizons' terrain near the camera, its overdraw prevention,
 * once {@link ChunkMaskPatch} leaves out the sections the world draws. Packs drop the fragments within {@code far},
 * the render distance, but many no nearer than a floor of their own, whatever {@code far} is: Bliss drops the land
 * within 32 blocks and makes the water clear within 16, and Complementary fades both out by a dither. Those tests would
 * only leave holes where chunks still wait for their meshes.
 *
 * <p>A test is an {@code if} whose body starts by dropping the fragment, with {@code discard}, or by making it clear,
 * setting the alpha of its first output to zero, and whose condition reads {@code far} or a name set from it in either
 * shader, such as Bliss's {@code maxOverdrawDistance}. Each part of the condition joined to the rest by {@code ||} that
 * reads them becomes {@code false}; the others stay, such as the tests of the depth of what the chunks drew. Reads the
 * sources as Iris patched them: preprocessed, with no comments.
 */
final class OverdrawTests {
	private static final Pattern ASSIGNMENT = Pattern.compile(
		"\\b([A-Za-z_]\\w*)(?:\\s*\\.\\s*[A-Za-z]+)?\\s*[-+*/]?=(?!=)([^;]*)");
	private static final Pattern DISCARD = Pattern.compile("\\bdiscard\\s*;");
	private static final Pattern FIRST_OUTPUT = Pattern.compile(
		"\\blayout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+([A-Za-z_]\\w*)\\s*;");
	private static final Pattern NAME = Pattern.compile("(?<![.\\w])[A-Za-z_]\\w*");
	/** The uniform for the render distance, in blocks. */
	private static final String FAR = "far";

	private OverdrawTests() {
	}

	/** {@code fragment} with the tests disarmed, where {@code vertex} is the vertex shader it follows. */
	static String disarm(String fragment, String vertex) {
		Set<String> near = new HashSet<>();
		near.add(FAR);
		near.addAll(setFromFar(vertex));
		near.addAll(setFromFar(fragment));
		Matcher output = FIRST_OUTPUT.matcher(fragment);
		Pattern hide = output.find()
			? Pattern.compile(DISCARD.pattern() + "|\\b" + output.group(1) + "\\s*\\.\\s*a\\s*=\\s*0(?:\\.0*)?f?\\s*;")
			: DISCARD;
		List<int[]> conditions = new ArrayList<>();
		Matcher hidden = hide.matcher(fragment);
		while (hidden.find()) {
			int[] condition = guard(fragment, hidden.start());
			if (condition != null) {
				conditions.add(condition);
			}
		}
		StringBuilder disarmed = new StringBuilder(fragment);
		for (int k = conditions.size() - 1; k >= 0; k--) {
			int[] condition = conditions.get(k);
			disarmed.replace(condition[0], condition[1],
				disarm(fragment.substring(condition[0], condition[1]), near));
		}
		return disarmed.toString();
	}

	/** The names {@code source} sets from an expression that reads {@code far}. */
	private static Set<String> setFromFar(String source) {
		Set<String> names = new HashSet<>();
		Matcher assignment = ASSIGNMENT.matcher(source);
		while (assignment.find()) {
			if (reads(assignment.group(2), Set.of(FAR))) {
				names.add(assignment.group(1));
			}
		}
		return names;
	}

	/**
	 * Where the condition is of the {@code if} whose body starts with the statement at {@code at}, from after its
	 * opening bracket to its closing one, or null if the statement heads no {@code if}.
	 */
	private static int @Nullable [] guard(String source, int at) {
		int close = before(source, at);
		if (close >= 0 && source.charAt(close) == '{') {
			close = before(source, close);
		}
		if (close < 0 || source.charAt(close) != ')') {
			return null;
		}
		int depth = 0;
		int open = close;
		for (; open >= 0; open--) {
			char c = source.charAt(open);
			if (c == ')') {
				depth++;
			} else if (c == '(' && --depth == 0) {
				break;
			}
		}
		int keyword = before(source, Math.max(open, 0)) - 1;
		if (open < 0 || keyword < 0 || !source.startsWith("if", keyword)
			|| keyword > 0 && Character.isJavaIdentifierPart(source.charAt(keyword - 1))) {
			return null;
		}
		return new int[]{open + 1, close};
	}

	/** Where the last character before {@code at} that is not white space is, or -1. */
	private static int before(String source, int at) {
		int k = at - 1;
		while (k >= 0 && Character.isWhitespace(source.charAt(k))) {
			k--;
		}
		return k;
	}

	/** {@code condition} with each part joined by {@code ||} that reads one of {@code near} made false. */
	private static String disarm(String condition, Set<String> near) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int k = 0; k < condition.length(); k++) {
			char c = condition.charAt(k);
			if (c == '(' || c == '[') {
				depth++;
			} else if (c == ')' || c == ']') {
				depth--;
			} else if (depth == 0 && c == '?') {
				// A choice between two conditions: taken as a whole.
				return reads(condition, near) ? "false" : condition;
			} else if (depth == 0 && condition.startsWith("||", k)) {
				parts.add(condition.substring(from, k));
				from = k + 2;
				k++;
			}
		}
		parts.add(condition.substring(from));
		boolean disarmed = false;
		for (int k = 0; k < parts.size(); k++) {
			if (reads(parts.get(k), near)) {
				parts.set(k, "false");
				disarmed = true;
			}
		}
		return disarmed ? String.join(" || ", parts.stream().map(String::strip).toList()) : condition;
	}

	/** Whether {@code expression} reads one of {@code names}, other than as a field or a swizzle. */
	private static boolean reads(String expression, Set<String> names) {
		Matcher name = NAME.matcher(expression);
		while (name.find()) {
			if (names.contains(name.group())) {
				return true;
			}
		}
		return false;
	}
}
