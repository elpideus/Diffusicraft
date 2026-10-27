package io.github.elpideus.diffusicraft.client.compat.iris;

import com.google.common.primitives.Ints;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/**
 * A shader pack's program for Distant Horizons' terrain, water or shadows, {@code dh_terrain}, {@code dh_water} or
 * {@code dh_shadow}, patched by Iris as it patches them for Distant Horizons, with the uniforms, samplers and blending
 * Iris gives them. The far terrain's vertices already follow Distant Horizons' layout: a position of four unsigned
 * shorts with the light in the fourth, a colour, and the material and the face's direction.
 */
final class FarProgram implements AutoCloseable {
	/** The pass a program draws in, which decides the buffers its samplers read. */
	enum Pass {
		TERRAIN, WATER, SHADOW
	}

	private final int id;
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final CustomUniforms customUniforms;
	private final @Nullable BlendModeOverride blend;
	private final BufferBlendOverride[] bufferBlends;
	private final int modelOffset;
	private final int microOffset;
	private final int worldYOffset;
	private final int clipDistance;
	private final int projection;
	private final int projectionInverse;
	private final int modelView;
	private final int modelViewInverse;
	private final int normalMatrix;

	private FarProgram(String name, ProgramSource source, Pass pass, IrisRenderingPipeline pipeline) {
		Map<PatchShaderType, String> patched = TransformPatcher.patchDHTerrain(name,
			source.getVertexSource().orElseThrow(), source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null), source.getGeometrySource().orElse(null),
			source.getFragmentSource().orElseThrow(), pipeline.getTextureMap(),
			pipeline.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW));
		id = GL20C.glCreateProgram();
		GL20C.glBindAttribLocation(id, 0, "vPosition");
		GL20C.glBindAttribLocation(id, 1, "iris_color");
		GL20C.glBindAttribLocation(id, 2, "irisExtra");
		List<GlShader> shaders = new ArrayList<>();
		try {
			shaders.add(new GlShader(ShaderType.VERTEX, name + ".vsh", patched.get(PatchShaderType.VERTEX)));
			attach(shaders, ShaderType.TESSELATION_CONTROL, name + ".tcs", patched.get(PatchShaderType.TESS_CONTROL));
			attach(shaders, ShaderType.TESSELATION_EVAL, name + ".tes", patched.get(PatchShaderType.TESS_EVAL));
			attach(shaders, ShaderType.GEOMETRY, name + ".gsh", patched.get(PatchShaderType.GEOMETRY));
			shaders.add(new GlShader(ShaderType.FRAGMENT, name + ".fsh", patched.get(PatchShaderType.FRAGMENT)));
			for (GlShader shader : shaders) {
				GL20C.glAttachShader(id, shader.getHandle());
			}
			GL20C.glLinkProgram(id);
			if (GL20C.glGetProgrami(id, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
				throw new IllegalStateException("Could not link " + name + ": " + GL20C.glGetProgramInfoLog(id));
			}
		} catch (RuntimeException e) {
			GL20C.glDeleteProgram(id);
			throw e;
		} finally {
			for (GlShader shader : shaders) {
				shader.destroy();
			}
		}
		GL20C.glUseProgram(id);
		ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(name, id);
		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		ProgramImages.Builder imageBuilder = ProgramImages.builder(id);
		customUniforms = pipeline.getCustomUniforms();
		CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
		customUniforms.assignTo(uniformBuilder);
		BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
		pipeline.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, switch (pass) {
			case TERRAIN -> pipeline::getFlippedAfterPrepare;
			case WATER -> pipeline::getFlippedAfterTranslucent;
			case SHADOW -> pipeline::getFlippedBeforeShadow;
		}, pass == Pass.SHADOW, false, true, false);
		customUniforms.mapholderToPass(uniformBuilder, this);
		uniforms = uniformBuilder.buildUniforms();
		samplers = samplerBuilder.build();
		images = imageBuilder.build();
		GL20C.glUseProgram(0);

		blend = source.getDirectives().getBlendModeOverride().orElse(null);
		int[] drawBuffers = source.getDirectives().getDrawBuffers();
		bufferBlends = source.getDirectives().getBufferBlendOverrides().stream()
			.filter(information -> Ints.indexOf(drawBuffers, information.index()) >= 0)
			.map(information -> new BufferBlendOverride(Ints.indexOf(drawBuffers, information.index()),
				information.blendMode()))
			.toArray(BufferBlendOverride[]::new);
		modelOffset = GL20C.glGetUniformLocation(id, "modelOffset");
		microOffset = GL20C.glGetUniformLocation(id, "mircoOffset");
		worldYOffset = GL20C.glGetUniformLocation(id, "worldYOffset");
		clipDistance = GL20C.glGetUniformLocation(id, "clipDistance");
		projection = GL20C.glGetUniformLocation(id, "iris_ProjectionMatrix");
		projectionInverse = GL20C.glGetUniformLocation(id, "iris_ProjectionMatrixInverse");
		modelView = GL20C.glGetUniformLocation(id, "iris_ModelViewMatrix");
		modelViewInverse = GL20C.glGetUniformLocation(id, "iris_ModelViewMatrixInverse");
		normalMatrix = GL20C.glGetUniformLocation(id, "iris_NormalMatrix");
	}

	/** Builds the pack's program from {@code source}, for {@code pass}. Throws if it does not build. */
	static FarProgram create(String name, ProgramSource source, Pass pass, IrisRenderingPipeline pipeline) {
		return new FarProgram(name, source, pass, pipeline);
	}

	private static void attach(List<GlShader> shaders, ShaderType type, String name, @Nullable String source) {
		if (source != null) {
			shaders.add(new GlShader(type, name, source));
		}
	}

	/**
	 * Uses the program for the far terrain of this frame, seen through {@code projection} and {@code modelView}, in a
	 * level whose lowest block is at {@code bottom}, drawn no nearer than {@code near}: sets its uniforms, binds its
	 * samplers and applies its blending. Leaves the blending to restore in {@link #unbind}.
	 */
	void bind(Matrix4fc projection, Matrix4fc modelView, int bottom, float near) {
		GL20C.glUseProgram(id);
		matrix(this.projection, projection);
		matrix(projectionInverse, projection.invert(new Matrix4f()));
		matrix(this.modelView, modelView);
		matrix(modelViewInverse, modelView.invert(new Matrix4f()));
		if (normalMatrix != -1) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer buffer = stack.mallocFloat(9);
				new Matrix4f(modelView).invert().transpose3x3(new Matrix3f()).get(buffer);
				IrisRenderSystem.uniformMatrix3fv(normalMatrix, false, buffer);
			}
		}
		if (microOffset != -1) {
			GL20C.glUniform1f(microOffset, 0.01f);
		}
		if (worldYOffset != -1) {
			// A float, as Distant Horizons sets it, so that packs written for it read it.
			GL20C.glUniform1f(worldYOffset, bottom);
		}
		if (clipDistance != -1) {
			GL20C.glUniform1f(clipDistance, near);
		}
		samplers.update();
		uniforms.update();
		customUniforms.push(this);
		images.update();
		if (blend != null) {
			blend.apply();
		}
		for (BufferBlendOverride override : bufferBlends) {
			override.apply();
		}
	}

	/** Places the next tile drawn {@code (x, y, z)} from the camera. */
	void offset(float x, float y, float z) {
		GL20C.glUniform3f(modelOffset, x, y, z);
	}

	void unbind() {
		GL20C.glUseProgram(0);
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		BlendModeOverride.restore();
	}

	private static void matrix(int location, Matrix4fc matrix) {
		if (location != -1) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer buffer = stack.mallocFloat(16);
				matrix.get(buffer);
				GL20C.glUniformMatrix4fv(location, false, buffer);
			}
		}
	}

	@Override
	public void close() {
		uniforms.removeListeners();
		samplers.removeListeners();
		GL20C.glDeleteProgram(id);
	}
}
