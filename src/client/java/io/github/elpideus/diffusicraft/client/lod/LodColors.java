package io.github.elpideus.diffusicraft.client.lod;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colour each block shows from afar, as Distant Horizons and Voxy colour theirs: towards each side, the average
 * of the textures its model shows there, weighted by how much of the side each one covers and leaving out the pixels
 * one sees through; and the parts the world tints, such as grass, leaves and water, tinted for the biome as the world
 * tints them.
 *
 * <p>The textures are read from the resource packs the first time a block is asked for, so ask off the render thread,
 * and make a new instance when the resource packs change. Thread-safe.
 */
public final class LodColors {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft far terrain colours");
	/** The colour of a texture that cannot be read: a grey that does not stand out. */
	private static final Sprite UNREADABLE = new Sprite(0.5f, 0.5f, 0.5f, 1);

	private final ResourceManager resources;
	private final BlockColors blockColors;
	private final Map<BlockState, Block> blocks = new ConcurrentHashMap<>();
	private final Map<Identifier, Sprite> sprites = new ConcurrentHashMap<>();
	private final ThreadLocal<Tints> tints = ThreadLocal.withInitial(Tints::new);

	/** The average colour of a texture's opaque pixels, and the share of its pixels that are opaque. */
	private record Sprite(float r, float g, float b, float cover) {
	}

	/**
	 * What a side of a block shows: the sum of the colours of its untinted textures and of its tinted ones, each
	 * weighted by the area it covers.
	 */
	private static final class Side {
		float r, g, b, weight;
		float tintedR, tintedG, tintedB, tintedWeight;
		@Nullable BlockTintSource tint;

		void add(Sprite sprite, float area, @Nullable BlockTintSource tint) {
			float w = area * sprite.cover();
			if (tint == null) {
				r += sprite.r() * w;
				g += sprite.g() * w;
				b += sprite.b() * w;
				weight += w;
			} else {
				tintedR += sprite.r() * w;
				tintedG += sprite.g() * w;
				tintedB += sprite.b() * w;
				tintedWeight += w;
				this.tint = tint;
			}
		}

		boolean isEmpty() {
			return weight + tintedWeight <= 0;
		}
	}

	/** The top, bottom and sides of a block. */
	private record Block(Side up, Side down, Side sides) {
		Side side(Direction direction) {
			return switch (direction) {
				case UP -> up;
				case DOWN -> down;
				default -> sides;
			};
		}
	}

	public LodColors(ResourceManager resources, BlockColors blockColors) {
		this.resources = resources;
		this.blockColors = blockColors;
	}

	/** Colours from the resource packs the game has loaded. */
	public static LodColors current() {
		Minecraft minecraft = Minecraft.getInstance();
		return new LodColors(minecraft.getResourceManager(), minecraft.getBlockColors());
	}

	/**
	 * The colour, as {@link ARGB}, that the side of {@code state} looking towards {@code side} shows in {@code biome}
	 * at block {@code (x, z)}; its alpha is opaque.
	 */
	public int color(BlockState state, Direction side, Holder<Biome> biome, int x, int z) {
		Side face = blocks.computeIfAbsent(state, this::block).side(side);
		float r = face.r;
		float g = face.g;
		float b = face.b;
		if (face.tint != null) {
			int tint = tints.get().tint(face.tint, state, biome, x, z);
			r += face.tintedR * ARGB.redFloat(tint);
			g += face.tintedG * ARGB.greenFloat(tint);
			b += face.tintedB * ARGB.blueFloat(tint);
		}
		float weight = face.weight + face.tintedWeight;
		return ARGB.colorFromFloat(1, r / weight, g / weight, b / weight);
	}

	private Block block(BlockState state) {
		Block block = new Block(new Side(), new Side(), new Side());
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty() && (state.is(Blocks.WATER) || state.is(Blocks.LAVA))) {
			FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid);
			Sprite sprite = sprite(model.stillMaterial().sprite());
			for (Side side : List.of(block.up(), block.down(), block.sides())) {
				side.add(sprite, 1, model.tintSource());
			}
			return block;
		}
		BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42), parts);
		for (BlockStateModelPart part : parts) {
			for (Direction cull : Direction.values()) {
				quads(state, part.getQuads(cull), block);
			}
			quads(state, part.getQuads(null), block);
		}
		// A side with nothing on it, as a block of fluid or one drawn by a block entity, shows the particle texture.
		TextureAtlasSprite particle = model.particleMaterial().sprite();
		List<BlockTintSource> tintSources = blockColors.getTintSources(state);
		BlockTintSource tint = tintSources.isEmpty() ? null : tintSources.getFirst();
		for (Side side : List.of(block.up(), block.down(), block.sides())) {
			if (side.isEmpty()) {
				side.add(sprite(particle), 1, tint);
			}
			if (side.isEmpty()) {
				side.add(UNREADABLE, 1, null);
			}
		}
		return block;
	}

	private void quads(BlockState state, List<BakedQuad> quads, Block block) {
		for (BakedQuad quad : quads) {
			BakedQuad.MaterialInfo material = quad.materialInfo();
			BlockTintSource tint = material.isTinted() ? blockColors.getTintSource(state, material.tintIndex()) : null;
			block.side(quad.direction()).add(sprite(material.sprite()), area(quad), tint);
		}
	}

	private static float area(BakedQuad quad) {
		Vector3fc origin = quad.position0();
		Vector3f across = quad.position1().sub(origin, new Vector3f());
		Vector3f along = quad.position3().sub(origin, new Vector3f());
		return across.cross(along).length();
	}

	private Sprite sprite(TextureAtlasSprite sprite) {
		return sprites.computeIfAbsent(sprite.contents().name(),
			name -> read(name, sprite.contents().width(), sprite.contents().height()));
	}

	/** The average of a texture's first frame, {@code width} by {@code height} pixels in its top left corner. */
	private Sprite read(Identifier name, int width, int height) {
		Identifier path = name.withPath(p -> "textures/" + p + ".png");
		Optional<Resource> resource = resources.getResource(path);
		if (resource.isEmpty()) {
			return UNREADABLE;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int w = Math.min(width, image.getWidth());
			int h = Math.min(height, image.getHeight());
			double r = 0;
			double g = 0;
			double b = 0;
			double alpha = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int pixel = image.getPixel(x, y);
					float a = ARGB.alphaFloat(pixel);
					r += ARGB.redFloat(pixel) * a;
					g += ARGB.greenFloat(pixel) * a;
					b += ARGB.blueFloat(pixel) * a;
					alpha += a;
				}
			}
			if (alpha <= 0) {
				return new Sprite(0, 0, 0, 0);
			}
			return new Sprite((float) (r / alpha), (float) (g / alpha), (float) (b / alpha),
				(float) (alpha / (w * h)));
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read {} for the colours of the far terrain", path, e);
			return UNREADABLE;
		}
	}

	/** Asks the world's tints for the colour of one biome, as a level of the far terrain would hold it. */
	private static final class Tints implements BlockAndTintGetter {
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private Holder<Biome> biome;

		int tint(BlockTintSource source, BlockState state, Holder<Biome> biome, int x, int z) {
			this.biome = biome;
			return source.colorInWorld(state, this, pos.set(x, 64, z));
		}

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver resolver) {
			return resolver.getColor(biome.value(), pos.getX(), pos.getZ());
		}

		@Override
		public CardinalLighting cardinalLighting() {
			return CardinalLighting.DEFAULT;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			throw new UnsupportedOperationException("The far terrain has no light engine");
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinY() {
			return -64;
		}
	}
}
