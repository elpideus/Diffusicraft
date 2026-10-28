package io.github.elpideus.diffusicraft.client.compat.iris;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;

/**
 * Gives a shader pack's programs for Distant Horizons the test of the far terrain's own shader, which leaves out the far
 * terrain wherever the world draws its sections, as the chunk mask of {@link FarTerrain} tells. The pack itself leaves
 * out Distant Horizons' terrain by its distance from the camera alone, within its {@code far}, the render distance: as
 * the player flies, the chunks ahead are loaded but still wait for their meshes, and the sky would show where they
 * are. With the test, the pack's own tests of the distance are disarmed ({@link OverdrawTests}) other than in its
 * shadows, it is told that {@code far} is next to nothing, and the far terrain stays until the world draws each
 * section.
 *
 * <p>The pack's {@code main}, as Iris patched it, is renamed and called from a new one. In the vertex shader, that hands
 * on where the vertex lies from the camera and the normal of its face, from {@code modelOffset}, {@code _vert_position}
 * and {@code _vert_normal}, the names Iris's patch for Distant Horizons gives them; in the fragment shader, it drops the
 * fragments in sections the world draws before the pack's own runs. A program with a stage between the two, or whose
 * {@code main} or those names cannot be found, is left as it is.
 */
final class ChunkMaskPatch {
	/**
	 * What the pack's programs are told {@code far} is, in blocks, once the test leaves out the chunks: near enough that
	 * what the pack keys on it, other than the tests {@link OverdrawTests} disarms, moves in to the camera, and far
	 * enough that the distances packs work out from it, such as Bliss's {@code far - 8}, keep their order.
	 */
	private static final float FAR = 16;
	private static final String SAMPLER = "diffusicraft_mask";
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
	private static final String PACK_MAIN = "void diffusicraft_packMain() {";
	private static final String VERTEX = """

		out vec3 diffusicraft_relative;
		flat out vec3 diffusicraft_normal;

		void main() {
		    diffusicraft_packMain();
		    diffusicraft_relative = modelOffset + _vert_position;
		    diffusicraft_normal = _vert_normal;
		}
		""";
	/** The test of {@code far_terrain.fsh}, with the values of its {@code FarTerrainInfo} in uniforms of their own. */
	private static final String FRAGMENT = """

		in vec3 diffusicraft_relative;
		flat in vec3 diffusicraft_normal;

		uniform sampler2D diffusicraft_mask;
		uniform ivec4 diffusicraft_columns;
		uniform ivec4 diffusicraft_rows;
		uniform ivec4 diffusicraft_slots;
		uniform float diffusicraft_reach;
		uniform ivec3 diffusicraft_cameraBlock;
		uniform vec3 diffusicraft_cameraInBlock;

		bool diffusicraft_withinReach(ivec3 section) {
		    if (diffusicraft_reach <= 0.0) {
		        return true;
		    }
		    vec3 low = vec3(section * 16 - diffusicraft_cameraBlock);
		    vec3 toNearest = clamp(diffusicraft_cameraInBlock, low, low + 16.0) - diffusicraft_cameraInBlock;
		    return dot(toNearest.xz, toNearest.xz) < diffusicraft_reach * diffusicraft_reach
		        && abs(toNearest.y) < diffusicraft_reach;
		}

		bool diffusicraft_drawn() {
		    vec3 inside = diffusicraft_relative + diffusicraft_cameraInBlock - diffusicraft_normal * 0.25;
		    ivec3 section = (diffusicraft_cameraBlock + ivec3(floor(inside))) >> 4;
		    ivec2 offset = section.xz - diffusicraft_columns.yz;
		    ivec2 beyond = max(abs(offset) - 1, ivec2(0));
		    if (beyond.x * beyond.x + beyond.y * beyond.y >= diffusicraft_columns.x * diffusicraft_columns.x
		        || section.y < diffusicraft_rows.x || section.y > diffusicraft_rows.y) {
		        return false;
		    }
		    ivec2 slot = (diffusicraft_slots.xy + offset + diffusicraft_columns.x) % diffusicraft_columns.w;
		    int row = (diffusicraft_slots.z + section.y - diffusicraft_rows.x) % diffusicraft_rows.z;
		    return texelFetch(diffusicraft_mask, ivec2(row * diffusicraft_columns.w + slot.x, slot.y), 0).r > 0.5
		        && diffusicraft_withinReach(section);
		}

		void main() {
		    if (diffusicraft_drawn()) {
		        discard;
		    }
		    diffusicraft_packMain();
		}
		""";

	private ChunkMaskPatch() {
	}

	/**
	 * The shaders of {@code patched}, as Iris patched them, given the test, and with the pack's own tests of the
	 * distance disarmed if {@code disarm}; or null where they cannot be.
	 */
	static @Nullable Map<PatchShaderType, String> apply(Map<PatchShaderType, String> patched, boolean disarm) {
		String vertex = patched.get(PatchShaderType.VERTEX);
		String fragment = patched.get(PatchShaderType.FRAGMENT);
		if (vertex == null || fragment == null || patched.get(PatchShaderType.TESS_CONTROL) != null
			|| patched.get(PatchShaderType.TESS_EVAL) != null || patched.get(PatchShaderType.GEOMETRY) != null
			|| !names(vertex, "modelOffset") || !names(vertex, "_vert_position") || !names(vertex, "_vert_normal")) {
			return null;
		}
		vertex = renameMain(vertex);
		fragment = renameMain(fragment);
		if (vertex == null || fragment == null) {
			return null;
		}
		Map<PatchShaderType, String> masked = new EnumMap<>(PatchShaderType.class);
		masked.put(PatchShaderType.VERTEX, vertex + VERTEX);
		masked.put(PatchShaderType.FRAGMENT, (disarm ? OverdrawTests.disarm(fragment, vertex) : fragment) + FRAGMENT);
		return masked;
	}

	private static boolean names(String source, String name) {
		return Pattern.compile("\\b" + name + "\\b").matcher(source).find();
	}

	/** {@code source} with its {@code main} renamed, or null unless it has exactly one. */
	private static @Nullable String renameMain(String source) {
		Matcher main = MAIN.matcher(source);
		if (!main.find()) {
			return null;
		}
		int start = main.start();
		int end = main.end();
		if (main.find()) {
			return null;
		}
		return source.substring(0, start) + PACK_MAIN + source.substring(end);
	}

	/** Has Iris bind the mask's texture to the program along with the pack's own samplers. */
	static void addSampler(SamplerHolder samplers) {
		samplers.addDynamicSampler(TextureType.TEXTURE_2D, ChunkMaskPatch::texture, () -> null, SAMPLER);
	}

	private static int texture() {
		return FarTerrain.chunkMask() instanceof GpuTextureView view && view.texture() instanceof GlTexture texture
			? texture.glId() : 0;
	}

	/** Where the test's uniforms are in a program, and {@code far}, which it overrides. */
	static final class Uniforms {
		private final int far;
		private final int columns;
		private final int rows;
		private final int slots;
		private final int reach;
		private final int cameraBlock;
		private final int cameraInBlock;
		private final int[] info = new int[16];

		Uniforms(int program) {
			far = GL20C.glGetUniformLocation(program, "far");
			columns = GL20C.glGetUniformLocation(program, "diffusicraft_columns");
			rows = GL20C.glGetUniformLocation(program, "diffusicraft_rows");
			slots = GL20C.glGetUniformLocation(program, "diffusicraft_slots");
			reach = GL20C.glGetUniformLocation(program, "diffusicraft_reach");
			cameraBlock = GL20C.glGetUniformLocation(program, "diffusicraft_cameraBlock");
			cameraInBlock = GL20C.glGetUniformLocation(program, "diffusicraft_cameraInBlock");
		}

		/**
		 * Sets the mask as it stands in this frame, for tiles placed from a camera at {@code (x, y, z)}, and {@code far}
		 * over Iris's own. With the program in use, after Iris has set its uniforms.
		 */
		void set(double x, double y, double z) {
			FarTerrain.chunkMaskInfo(info);
			GL20C.glUniform4i(columns, info[0], info[1], info[2], info[3]);
			GL20C.glUniform4i(rows, info[4], info[5], info[6], info[7]);
			GL20C.glUniform4i(slots, info[8], info[9], info[10], info[11]);
			GL20C.glUniform1f(reach, Float.intBitsToFloat(info[12]));
			int blockX = Mth.floor(x);
			int blockY = Mth.floor(y);
			int blockZ = Mth.floor(z);
			GL20C.glUniform3i(cameraBlock, blockX, blockY, blockZ);
			GL20C.glUniform3f(cameraInBlock, (float) (x - blockX), (float) (y - blockY), (float) (z - blockZ));
			if (far != -1) {
				GL20C.glUniform1f(far, FAR);
			}
		}
	}
}
