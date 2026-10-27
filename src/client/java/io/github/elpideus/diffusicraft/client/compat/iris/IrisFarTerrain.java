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
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.FrustumIntersection;
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
 * Where the pack has a {@code dh_shadow} program and a shadow map, the land of the tiles is drawn into its shadow map as
 * well, as Iris's shadow pass calls back, even where the pack keeps Distant Horizons out of it: within the shadow's
 * reach the far terrain stands where the chunks would, and a pack that lights it from the shadow map would light a cave
 * there as if nothing stood over it.
 *
 * <p>Load it only when Iris is installed. On the render thread.
 */
public final class IrisFarTerrain {
	/** How near the far terrain's projection begins, in blocks. */
	private static final float NEAR = 1;
	/** How many draw buffers Minecraft keeps the colour mask and the blending of. */
	private static final int TRACKED_BUFFERS = 8;

	/** Whether the pack in use was loaded with {@code DISTANT_HORIZONS} defined. */
	private static boolean defined;
	private static boolean shadowsCalled;
	private static @Nullable Targets targets;

	private IrisFarTerrain() {
	}

	/** Whether to define {@code DISTANT_HORIZONS} for the pack being loaded: whether the far terrain is on. */
	public static boolean define() {
		defined = FarTerrain.configured();
		if (!shadowsCalled) {
			shadowsCalled = true;
			IrisApi.getInstance().registerShadowRenderCallback(IrisFarTerrain::drawShadows);
		}
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
		draw(current, current.terrain, projection(), false);
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
		draw(current, current.water, projection(), true);
		for (int i = 0; i < current.waterBuffers; i++) {
			GlStateManager._disableBlend(i);
		}
		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
	}

	/**
	 * Draws the land of the far terrain into the shadow map with the pack's {@code dh_shadow}, as Iris's shadow pass
	 * calls back after the chunks' solid terrain, through the shadow's {@code modelView} and {@code projection} from the
	 * camera at {@code (x, y, z)}. Every tile the shadow map sees casts, out of view too. The land only: what is drawn
	 * here also lands in the depth the pack reads without the translucent blocks.
	 */
	private static void drawShadows(Matrix4f modelView, Matrix4f projection, double x, double y, double z,
									float tickDelta) {
		if (!ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return;
		}
		Targets current = targets(false);
		if (current == null || current.shadow == null || current.shadowFramebuffer == null) {
			return;
		}
		// Iris's own programs for Distant Horizons see the shadow's depth from -1 to 1.
		Matrix4f clip = MatrixUtils.toMinusOneToOne(projection);
		FrustumIntersection frustum = new FrustumIntersection(clip.mul(modelView, new Matrix4f()), false);
		int read = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
		int draw = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
		current.shadowFramebuffer.bind();
		GlStateManager._viewport(0, 0, ShadowRenderer.RESOLUTION, ShadowRenderer.RESOLUTION);
		GlStateManager._disableScissorTest();
		// The shadow pass does not turn the depth around.
		GlStateManager._depthMask(true);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11C.GL_LEQUAL);
		GlStateManager._disablePolygonOffset();
		// As Iris draws the chunks into the shadow map: the sun may see a face from behind.
		GlStateManager._disableCull();
		for (int i = 0; i < current.shadowBuffers; i++) {
			GlStateManager._colorMask(i, 15);
			GlStateManager._disableBlend(i);
		}
		draw(current, current.shadow, clip, modelView, FarTerrain.casterQuads(),
			tiles -> FarTerrain.forEachCaster(x, y, z, frustum::testAab, tiles));
		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
	}

	/** The pack's programs and buffers, if the far terrain is to be drawn with them in this pass. */
	private static @Nullable Targets drawable() {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? null : targets(true);
	}

	/** The pack's programs and buffers for the pipeline in use, made if {@code make} and there are none yet. */
	private static @Nullable Targets targets(boolean make) {
		if (!defined || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
			release();
			return null;
		}
		if (!make && (targets == null || targets.pipeline != pipeline)) {
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
	private static void draw(Targets current, FarProgram program, Matrix4fc projection, boolean water) {
		draw(current, program, projection, CapturedRenderingState.INSTANCE.getGbufferModelView(),
			FarTerrain.drawnQuads(), tiles -> FarTerrain.forEachDrawn(water, tiles));
	}

	/** Some of the far terrain's tiles, of at most {@code quads} quads each, handed to {@code tiles}. */
	private interface Source {
		void forEach(FarTerrain.Tiles tiles);
	}

	/**
	 * Draws the tiles of {@code source}, of at most {@code quads} quads each, with {@code program} into the framebuffer
	 * bound, through {@code projection} and {@code modelView}.
	 */
	private static void draw(Targets current, FarProgram program, Matrix4fc projection, Matrix4fc modelView, int quads,
							 Source source) {
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		// The index buffer grows to what the far terrain asked for as the frame's passes begin.
		if (quads == 0 || !indices.hasStorage(quads * 6) || !(indices.getBuffer() instanceof GlBuffer indexBuffer)) {
			return;
		}
		int indexType = GlConst.toGl(indices.type());
		program.bind(projection, modelView, FarTerrain.bottom(), NEAR);
		GlStateManager._glBindVertexArray(current.vertexArray);
		GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.handle());
		source.forEach((vertices, firstVertex, count, x, y, z) -> {
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
		/** The pack's {@code dh_shadow}, if it has one and a shadow map. */
		@Nullable FarProgram shadow;
		@Nullable GlFramebuffer terrainFramebuffer;
		@Nullable GlFramebuffer waterFramebuffer;
		@Nullable GlFramebuffer shadowFramebuffer;
		int terrainBuffers;
		int waterBuffers;
		int shadowBuffers;
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
			// Whether or not the pack lets Distant Horizons cast (dhShadow.enabled): where it does not, Iris keeps the
			// shadow's box to the pack's own reach, and the far terrain within it casts as the chunks there would.
			ProgramSource shadowSource = pipeline.hasShadowRenderTargets()
				? pipeline.getDHShadowShader().orElse(null) : null;
			try {
				terrain = FarProgram.create("dh_terrain", terrainSource, FarProgram.Pass.TERRAIN, pipeline);
				water = FarProgram.create("dh_water", waterSource, FarProgram.Pass.WATER, pipeline);
				if (shadowSource != null) {
					shadow = FarProgram.create("dh_shadow", shadowSource, FarProgram.Pass.SHADOW, pipeline);
				}
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
			if (shadow != null) {
				shadowFramebuffer = pipeline.createDHFramebufferShadow(shadowSource);
				// shadowcolor0 and shadowcolor1, as Iris gives Distant Horizons.
				shadowBuffers = 2;
			}
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
			if (shadow != null) {
				shadow.close();
				shadow = null;
			}
			closeDepth();
			if (vertexArray != 0) {
				GL30C.glDeleteVertexArrays(vertexArray);
				vertexArray = 0;
			}
		}
	}
}
