package io.github.elpideus.diffusicraft.client.compat.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.client.lod.FarTerrainPipelines;
import java.io.IOException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

/**
 * The far terrain under a shader pack. Iris draws Distant Horizons' terrain with the pack's {@code dh_terrain} and
 * {@code dh_water} programs, into the pack's buffers and into depth textures of their own that the pack's later passes
 * read as {@code dhDepthTex0} and {@code dhDepthTex1}. Where Distant Horizons is not installed, Diffusicraft plays its
 * part: the pack is told that Distant Horizons draws ({@code DISTANT_HORIZONS} is defined, and Iris's {@code DHCompat}
 * reports the far terrain's planes, reach and depth textures), and the tiles of {@link FarTerrain} are drawn with those
 * programs, the land before the chunks, as Distant Horizons draws it, and the water before the translucent chunks.
 *
 * <p>The tiles cast no shadows yet. Load it only when Iris is installed. On the render thread.
 */
public final class IrisFarTerrain {
	/** How near the far terrain's projection begins, in blocks. */
	private static final float NEAR = 1;
	/** How many draw buffers Minecraft keeps the colour mask and the blending of. */
	private static final int TRACKED_BUFFERS = 8;

	/** Whether the pack in use was loaded with {@code DISTANT_HORIZONS} defined. */
	private static boolean defined;
	private static @Nullable Targets targets;

	private IrisFarTerrain() {
	}

	/** Whether to define {@code DISTANT_HORIZONS} for the pack being loaded: whether the far terrain is on. */
	public static boolean define() {
		defined = FarTerrain.configured();
		return defined;
	}

	/** Whether the pack in use was loaded believing that Distant Horizons draws. */
	public static boolean defined() {
		return defined;
	}

	/**
	 * Called by Iris as each frame begins, as it checks whether Distant Horizons was turned on or off: loads the pack
	 * again when the far terrain was, so that it sees {@code DISTANT_HORIZONS} defined or not. Returns whether it is.
	 */
	public static boolean checkFrame() {
		if (FarTerrain.configured() != defined && Iris.isPackInUseQuick()) {
			defined = !defined;
			try {
				Iris.reload();
			} catch (IOException e) {
				Diffusicraft.LOGGER.error("Could not load the shader pack again after the far terrain was turned {}",
					defined ? "on" : "off", e);
			}
		}
		return defined;
	}

	/** Whether a pack is in use, which draws the far terrain in the world's place. */
	public static boolean drawsInstead() {
		return Iris.isPackInUseQuick();
	}

	public static float nearPlane() {
		return NEAR;
	}

	/** Where the far terrain's projection ends: well past its reach, so that nothing it draws is clipped. */
	public static float farPlane() {
		return distance() * 2f;
	}

	/** How far the far terrain reaches, in blocks, as Distant Horizons reports its own. */
	public static int renderDistance() {
		return distance();
	}

	private static int distance() {
		int reach = FarTerrain.reach();
		return reach > 0 ? reach : Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
	}

	/** The projection the far terrain is drawn through: the world's, out to {@link #farPlane()}. */
	public static Matrix4f projection() {
		Matrix4fc world = CapturedRenderingState.INSTANCE.getGbufferProjection();
		return new Matrix4f().setPerspective(world.perspectiveFov(), world.m11() / world.m00(), nearPlane(), farPlane());
	}

	/** The far terrain's depth, its water included, or 0 while there is none. */
	public static int depthTexture() {
		Targets current = targets;
		return current != null && current.depth != null ? current.depth.getTextureId() : 0;
	}

	/** The far terrain's depth without its water, or 0 while there is none. */
	public static int depthTextureNoWater() {
		Targets current = targets;
		return current != null && current.depthNoWater != null ? current.depthNoWater.getTextureId() : 0;
	}

	/**
	 * Draws the far terrain's land with the pack's {@code dh_terrain}, as the world's solid pass begins and before the
	 * chunks, which draw over it where they reach. Clears its depth first, so that the pack sees no far terrain where
	 * there is none, and keeps a copy of it before the water.
	 */
	public static void drawOpaque() {
		Targets current = drawable();
		if (current == null) {
			return;
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		current.resize(main.width, main.height);
		int read = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
		int draw = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
		current.attachDepth();
		current.terrainFramebuffer.bind();
		// The pass that has just begun sets its own scissor again as it first draws, as it sets the rest.
		GlStateManager._disableScissorTest();
		// Iris turns the depth of the pack's programs around: the far end is 0, and nearer is greater.
		boolean reverse = IrisApi.getInstance().isReverseZDuringShaders();
		GlStateManager._depthMask(true);
		GlStateManager._clearBuffer(reverse ? 0 : 1);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(reverse ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
		GlStateManager._disablePolygonOffset();
		GlStateManager._enableCull();
		for (int i = 0; i < current.terrainBuffers; i++) {
			GlStateManager._colorMask(i, 15);
			GlStateManager._disableBlend(i);
		}
		draw(current, current.terrain, false);
		DepthCopyStrategy.fastest(false).copy(current.terrainFramebuffer, current.depth.getTextureId(), null,
			current.depthNoWater.getTextureId(), current.width, current.height);
		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
	}

	/** Draws the far terrain's water with the pack's {@code dh_water}, before the translucent chunks. */
	public static void drawWater() {
		Targets current = drawable();
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		// Not before the land has been drawn into depth of the window's size.
		if (current == null || current.depth == null || current.width != main.width || current.height != main.height) {
			return;
		}
		int read = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
		int draw = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
		current.attachDepth();
		current.waterFramebuffer.bind();
		GlStateManager._disableScissorTest();
		boolean reverse = IrisApi.getInstance().isReverseZDuringShaders();
		GlStateManager._depthMask(true);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(reverse ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
		GlStateManager._disablePolygonOffset();
		GlStateManager._disableCull();
		// Blended as Distant Horizons blends its water, unless the pack says otherwise.
		GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE,
			GL11C.GL_ONE_MINUS_SRC_ALPHA);
		for (int i = 0; i < current.waterBuffers; i++) {
			GlStateManager._colorMask(i, 15);
			GlStateManager._enableBlend(i);
		}
		draw(current, current.water, true);
		for (int i = 0; i < current.waterBuffers; i++) {
			GlStateManager._disableBlend(i);
		}
		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
	}

	/** The pack's programs and buffers, if the far terrain is to be drawn with them in this pass. */
	private static @Nullable Targets drawable() {
		if (!defined || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
			release();
			return null;
		}
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return null;
		}
		if (targets == null || targets.pipeline != pipeline) {
			release();
			targets = new Targets(pipeline);
		}
		return targets.failed ? null : targets;
	}

	private static void release() {
		if (targets != null) {
			targets.close();
			targets = null;
		}
	}

	/** Draws the tiles in view, their land or their water, with {@code program} into the framebuffer bound. */
	private static void draw(Targets current, FarProgram program, boolean water) {
		int quads = FarTerrain.drawnQuads();
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		// The index buffer grows to what the far terrain asked for as the frame's passes begin.
		if (quads == 0 || !indices.hasStorage(quads * 6) || !(indices.getBuffer() instanceof GlBuffer indexBuffer)) {
			return;
		}
		int indexType = GlConst.toGl(indices.type());
		program.bind(projection(), CapturedRenderingState.INSTANCE.getGbufferModelView(), FarTerrain.bottom(), NEAR);
		GlStateManager._glBindVertexArray(current.vertexArray);
		GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.handle());
		FarTerrain.forEachDrawn(water, (vertices, firstVertex, count, x, y, z) -> {
			if (!(vertices instanceof GlBuffer buffer)) {
				return;
			}
			// FarTerrainPipelines.VERTEX, in the attributes Iris gives the programs for Distant Horizons.
			GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer.handle());
			GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, FarTerrainPipelines.VERTEX_SIZE, 0);
			GL20C.glVertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, FarTerrainPipelines.VERTEX_SIZE, 8);
			GL30C.glVertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, FarTerrainPipelines.VERTEX_SIZE, 12);
			program.offset(x, y, z);
			GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, count * 6, indexType, 0, firstVertex);
		});
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
		GlStateManager._glBindVertexArray(0);
		program.unbind();
	}

	/**
	 * What a pack's pipeline needs to draw the far terrain: its programs, framebuffers onto its buffers, and the depth
	 * textures. The framebuffers belong to the pipeline, which lets go of them with its buffers.
	 */
	private static final class Targets implements AutoCloseable {
		final IrisRenderingPipeline pipeline;
		@Nullable FarProgram terrain;
		@Nullable FarProgram water;
		@Nullable GlFramebuffer terrainFramebuffer;
		@Nullable GlFramebuffer waterFramebuffer;
		int terrainBuffers;
		int waterBuffers;
		@Nullable DepthTexture depth;
		@Nullable DepthTexture depthNoWater;
		int width;
		int height;
		int vertexArray;
		boolean failed;

		Targets(IrisRenderingPipeline pipeline) {
			this.pipeline = pipeline;
			ProgramSource terrainSource = pipeline.getDHTerrainShader().orElse(null);
			if (terrainSource == null) {
				Diffusicraft.LOGGER.warn("The shader pack has no dh_terrain program; it draws no far terrain");
				failed = true;
				return;
			}
			ProgramSource waterSource = pipeline.getDHWaterShader().orElse(terrainSource);
			try {
				terrain = FarProgram.create("dh_terrain", terrainSource, false, pipeline);
				water = FarProgram.create("dh_water", waterSource, true, pipeline);
			} catch (RuntimeException e) {
				Diffusicraft.LOGGER.error("Could not build the shader pack's programs for the far terrain; it draws none", e);
				failed = true;
				close();
				return;
			}
			terrainFramebuffer = pipeline.createDHFramebuffer(terrainSource, false);
			waterFramebuffer = pipeline.createDHFramebuffer(waterSource, true);
			terrainBuffers = Math.min(TRACKED_BUFFERS, terrainSource.getDirectives().getDrawBuffers().length);
			waterBuffers = Math.min(TRACKED_BUFFERS, waterSource.getDirectives().getDrawBuffers().length);
			vertexArray = GL30C.glGenVertexArrays();
			GlStateManager._glBindVertexArray(vertexArray);
			for (int i = 0; i < 3; i++) {
				GL20C.glEnableVertexAttribArray(i);
			}
			GlStateManager._glBindVertexArray(0);
		}

		void resize(int width, int height) {
			if (depth != null && this.width == width && this.height == height) {
				return;
			}
			closeDepth();
			depth = new DepthTexture("Diffusicraft far terrain depth", width, height, DepthBufferFormat.DEPTH32F);
			depthNoWater = new DepthTexture("Diffusicraft far terrain depth without water", width, height,
				DepthBufferFormat.DEPTH32F);
			this.width = width;
			this.height = height;
		}

		/** Attaches the depth to both framebuffers again, as Iris attaches the world's own when the window is resized. */
		void attachDepth() {
			terrainFramebuffer.addDepthAttachmentBypass(depth.getTextureId());
			waterFramebuffer.addDepthAttachmentBypass(depth.getTextureId());
		}

		private void closeDepth() {
			if (depth != null) {
				depth.destroy();
				depth = null;
			}
			if (depthNoWater != null) {
				depthNoWater.destroy();
				depthNoWater = null;
			}
		}

		@Override
		public void close() {
			if (terrain != null) {
				terrain.close();
				terrain = null;
			}
			if (water != null) {
				water.close();
				water = null;
			}
			closeDepth();
			if (vertexArray != 0) {
				GL30C.glDeleteVertexArrays(vertexArray);
				vertexArray = 0;
			}
		}
	}
}
