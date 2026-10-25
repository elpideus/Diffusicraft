package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import io.github.elpideus.diffusicraft.Diffusicraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The render pipelines of the far terrain and the layout of its vertices. The shaders are
 * {@code assets/diffusicraft/shaders/core/far_terrain}. They read the uniforms vanilla terrain reads, so the far terrain
 * moves, fogs and lights as the chunks around it do, plus {@code FarTerrainInfo} and the {@code ChunkMask} of
 * {@link ChunkMask}.
 */
public final class FarTerrainPipelines {
	/**
	 * A vertex, 16 bytes: its position from the tile's corner, X and Z in blocks and Y from the bottom of the level, with
	 * its light as the sky light plus 16 times the block light; its colour; and, for shader packs, the Distant Horizons
	 * material of its block ({@link FarMaterials}) and the {@link net.minecraft.core.Direction} its face looks to.
	 */
	public static final VertexFormat VERTEX = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGBA16_UINT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("Extra", GpuFormat.RGBA8_UINT)
		.build();
	public static final int VERTEX_SIZE = 16;
	static final BindGroupLayout FAR_TERRAIN = BindGroupLayout.builder()
		.withUniform("FarTerrainInfo", UniformType.UNIFORM_BUFFER)
		.withUniform("ChunkMask", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(Diffusicraft.MOD_ID, "core/far_terrain");
	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withBindGroupLayout(BindGroupLayouts.FOG)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
		.withBindGroupLayout(BindGroupLayouts.PROJECTION)
		.withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
		.withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
		.withBindGroupLayout(FAR_TERRAIN)
		.withVertexBinding(0, VERTEX)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.withVertexShader(SHADER)
		.withFragmentShader(SHADER)
		.buildSnippet();
	// Optional, so that a shader that fails to compile turns the far terrain off rather than stopping the game.
	public static final RenderPipeline SOLID = RenderPipelines.registerOptional(RenderPipeline.builder(SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath(Diffusicraft.MOD_ID, "pipeline/far_terrain"))
		.withColorTargetState(ColorTargetState.DEFAULT)
		.build());
	public static final RenderPipeline WATER = RenderPipelines.registerOptional(RenderPipeline.builder(SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath(Diffusicraft.MOD_ID, "pipeline/far_water"))
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.build());

	private FarTerrainPipelines() {
	}

	/** Registers the pipelines, so that the game compiles them with its own as it loads its resources. */
	public static void init() {
		// Loading the class registers them.
	}
}
