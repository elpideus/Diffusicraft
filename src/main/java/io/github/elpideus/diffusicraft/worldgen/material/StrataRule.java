package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import io.github.elpideus.diffusicraft.mixin.MaterialRuleContextAccessor;
import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Erosion;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.geology.Ore;
import io.github.elpideus.diffusicraft.worldgen.geology.Rock;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Fills the ground with the {@link Geology} model: strata, basement, the deep layers, the magma layer and ore bodies.
 * It replaces vanilla's underground rule, which only knows stone and deepslate.
 *
 * <p>The optional {@code surface} rule runs first, so biomes keep their soils, sand and terracotta. When it asks for
 * plain stone, as mountain biomes do under their surface, the local rock goes there instead, so cliffs show the
 * strata. Above the water, the slope has the last word ({@link Erosion}): where the ground is too steep to hold soil,
 * sand or snow, the rock shows through, and scree of gravel and broken rock lies on the slopes under the cliffs.
 *
 * <p>The ceilings of the Grand {@link Hollows} get patches of light. The rule is compiled for every chunk; the
 * per-world state is cached, so compiling costs a few hashes. Compiled inside {@link CoarseClimate#call}, it reads the
 * terrain from the coarse map.
 */
public record StrataRule(WorldScale scale, Optional<MaterialRule> surface) implements MaterialRule {
	public static final MapCodec<StrataRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(StrataRule::scale),
		MaterialRule.CODEC.optionalFieldOf("surface").forGetter(StrataRule::surface)
	).apply(i, StrataRule::new));

	/** The geology seed of a world is derived from the world seed and this name. */
	public static final Identifier SEED_ID = Diffusicraft.id("geology");

	private static final Map<Key, Geology> GEOLOGY = new ConcurrentHashMap<>();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
	private static final BlockState SHROOMLIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
	private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState COBBLED_DEEPSLATE = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
	/**
	 * What the biomes lay on the ground that slides off cliffs: the soils (dirt, grass, podzol, moss, mud and
	 * Diffusicraft's own, all under {@code #substrate_overworld}), sand, gravel and snow.
	 */
	private static final Predicate<BlockState> SOILS = state -> state.is(BlockTags.SUBSTRATE_OVERWORLD)
		|| state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)
		|| state.is(Blocks.POWDER_SNOW);

	private record Key(long seed, WorldScale scale, int minY) {
	}

	@Override
	public MapCodec<StrataRule> codec() {
		return CODEC;
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		RuleEvaluator surfaceRule = surface.map(rule -> rule.compile(context)).orElse(null);
		PositionalRandomFactory random = ((RandomStateAccessor) (Object)
			((MaterialRuleContextAccessor) (Object) context).diffusicraft$randomState()).diffusicraft$random();
		TerrainField field = TerrainFields.get(random.fromHashOf(TerrainFields.SEED_ID).nextLong());
		int minY = context.resolveAnchorY(VerticalAnchor.bottom());
		Key key = new Key(random.fromHashOf(SEED_ID).nextLong(), scale, minY);
		Geology geology = GEOLOGY.computeIfAbsent(key, k -> new Geology(k.seed, k.minY, k.scale.metersPerBlock(),
			k.scale.verticalMetersPerBlock()));
		// The same seed as the hollows density function's, so the light finds the ceilings it carved.
		Hollows hollows = Hollows.of(random.fromHashOf(HollowsFunction.SEED_ID).nextLong(), minY);
		return new Evaluator(context, scale, field, geology, new Erosion(key.seed), hollows, surfaceRule,
			CoarseClimate.active());
	}

	/** Forgets the cached geology of every world; called when the server stops. */
	public static void clearCache() {
		GEOLOGY.clear();
	}

	private static final class Evaluator implements RuleEvaluator {
		/** How far into a hollow's wall its density may be and still count: the world interpolates it. */
		private static final double HOLLOW_MARGIN = 1.0;

		private final MaterialRuleContext context;
		private final WorldScale scale;
		private final TerrainField field;
		private final Geology geology;
		private final Erosion erosion;
		private final Erosion.Surface ground;
		private final Hollows hollows;
		private final RuleEvaluator surface;
		private final boolean coarse;
		private final TerrainField.Column terrain = new TerrainField.Column();
		private final Geology.Column column = new Geology.Column();
		private final Hollows.Column hollowColumn = new Hollows.Column();
		private int columnX;
		private int columnZ;
		private boolean hasColumn;
		/** What covers the ground of the column, worked out the first time a block near the surface asks. */
		private Erosion.@Nullable Kind cover;

		Evaluator(MaterialRuleContext context, WorldScale scale, TerrainField field, Geology geology, Erosion erosion,
				  Hollows hollows, RuleEvaluator surface, boolean coarse) {
			this.context = context;
			this.scale = scale;
			this.field = field;
			this.geology = geology;
			this.erosion = erosion;
			this.hollows = hollows;
			this.surface = surface;
			this.coarse = coarse;
			double perBlock = scale.pixelsPerBlock();
			this.ground = coarse
				? (x, z) -> scale.surfaceY(field.coarseElevation(z * perBlock, x * perBlock))
				: (x, z) -> scale.surfaceY(field.elevation(z * perBlock, x * perBlock));
		}

		@Override
		public BlockState tryApply(int x, int y, int z) {
			BlockState state = surface != null ? surface.tryApply(x, y, z) : null;
			// The material system walks one column at a time, top down.
			if (!hasColumn || x != columnX || z != columnZ) {
				sampleColumn(x, z);
			}
			if (state != null && (state == STONE || SOILS.test(state)) && context.waterHeight() <= y) {
				// Soil, or the biome's bare stone, above the water: the slope decides what stays. Under a cliff's
				// rock or scree lies the rock, not the soil.
				Erosion.Kind kind = cover();
				if (kind == Erosion.Kind.SCREE && context.stoneDepthAbove() <= Erosion.SCREE_DEPTH
					&& y >= Mth.ceil(column.surfaceY) - Erosion.SCREE_DEPTH) {
					return erosion.gravel(x, y, z) ? GRAVEL : BlockTable.rubble(geology.rock(column, x, y, z));
				}
				if (kind == Erosion.Kind.SOIL && state != STONE) {
					return state;
				}
			} else if (state != null && state != STONE) {
				return state;
			}
			if (context.stoneDepthBelow() == 1) {
				Hollows.Kind lit = litHollowBelow(x, y, z);
				if (lit != null) {
					return lit == Hollows.Kind.ANCIENT_GRAND_HOLLOW ? SHROOMLIGHT : GLOWSTONE;
				}
			}
			Rock rock = geology.rock(column, x, y, z);
			Ore ore = geology.ore(column, x, y, z, rock);
			return ore != null ? BlockTable.ore(ore, rock.deep()) : BlockTable.rock(rock);
		}

		/**
		 * The lit hollow under a ceiling block (one right above air or fluid) in a patch of light, or {@code null}. The
		 * hollows are looked up only here, which is rare: a few blocks per column at most.
		 */
		private Hollows.Kind litHollowBelow(int x, int y, int z) {
			if (!hollows.glows(x, z)) {
				return null;
			}
			if (!hollowColumn.isAt(x, z)) {
				hollows.column(x, z, (bx, bz) -> (int) Math.floor(column.surfaceY), hollowColumn);
			}
			Hollows.Kind kind = hollows.kindNear(hollowColumn, y - 1, HOLLOW_MARGIN);
			return kind != null && kind.lit() ? kind : null;
		}

		private Erosion.Kind cover() {
			if (cover == null) {
				cover = erosion.kind(ground, columnX, columnZ);
			}
			return cover;
		}

		private void sampleColumn(int x, int z) {
			columnX = x;
			columnZ = z;
			hasColumn = true;
			cover = null;
			if (coarse) {
				field.coarseColumn(scale.toPixel(z), scale.toPixel(x), terrain);
			} else {
				field.column(scale.toPixel(z), scale.toPixel(x), terrain);
			}
			column.surfaceY = scale.surfaceY(terrain.elevation);
			column.meanSurfaceY = scale.surfaceY(terrain.meanElevation);
			column.meanElevation = terrain.meanElevation;
			column.relief = terrain.relief;
			column.temperature = terrain.temperature;
			column.precipitation = terrain.precipitation;
			geology.column(x, z, column);
		}
	}

	/** The block of every rock and ore, looked up once. */
	private static final class BlockTable {
		private static final BlockState[] ROCKS = new BlockState[Rock.values().length];
		private static final BlockState[] ORES = new BlockState[Ore.values().length];
		private static final BlockState[] DEEP_ORES = new BlockState[Ore.values().length];

		static {
			rock(Rock.SANDSTONE, Blocks.SANDSTONE);
			rock(Rock.RED_SANDSTONE, Blocks.RED_SANDSTONE);
			rock(Rock.SHALE, DiffusicraftBlocks.SHALE);
			// A coal seam's rock between the coal is shale.
			rock(Rock.COAL_SEAM, DiffusicraftBlocks.SHALE);
			rock(Rock.LIMESTONE, DiffusicraftBlocks.LIMESTONE);
			rock(Rock.CHALK, DiffusicraftBlocks.CHALK);
			rock(Rock.ROCK_SALT, DiffusicraftBlocks.ROCK_SALT);
			rock(Rock.SLATE, DiffusicraftBlocks.SLATE);
			rock(Rock.MARBLE, DiffusicraftBlocks.MARBLE);
			rock(Rock.SCHIST, DiffusicraftBlocks.SCHIST);
			rock(Rock.GNEISS, DiffusicraftBlocks.GNEISS);
			rock(Rock.STONE, Blocks.STONE);
			rock(Rock.GRANITE, Blocks.GRANITE);
			rock(Rock.DIORITE, Blocks.DIORITE);
			rock(Rock.ANDESITE, Blocks.ANDESITE);
			rock(Rock.TUFF, Blocks.TUFF);
			rock(Rock.DEEPSLATE, Blocks.DEEPSLATE);
			rock(Rock.GABBRO, DiffusicraftBlocks.GABBRO);
			rock(Rock.PERIDOTITE, DiffusicraftBlocks.PERIDOTITE);
			rock(Rock.KIMBERLITE, DiffusicraftBlocks.KIMBERLITE);
			rock(Rock.MAGMA, Blocks.MAGMA_BLOCK);
			rock(Rock.BLACKSTONE, Blocks.BLACKSTONE);
			rock(Rock.BASALT, Blocks.BASALT);

			ore(Ore.COAL, Blocks.COAL_ORE,
				Blocks.DEEPSLATE_COAL_ORE);
			ore(Ore.IRON, Blocks.IRON_ORE,
				Blocks.DEEPSLATE_IRON_ORE);
			ore(Ore.COPPER, Blocks.COPPER_ORE,
				Blocks.DEEPSLATE_COPPER_ORE);
			ore(Ore.GOLD, Blocks.GOLD_ORE,
				Blocks.DEEPSLATE_GOLD_ORE);
			ore(Ore.REDSTONE, Blocks.REDSTONE_ORE,
				Blocks.DEEPSLATE_REDSTONE_ORE);
			ore(Ore.LAPIS, Blocks.LAPIS_ORE,
				Blocks.DEEPSLATE_LAPIS_ORE);
			ore(Ore.EMERALD, Blocks.EMERALD_ORE,
				Blocks.DEEPSLATE_EMERALD_ORE);
			ore(Ore.DIAMOND, Blocks.DIAMOND_ORE,
				Blocks.DEEPSLATE_DIAMOND_ORE);
			ore(Ore.SULFUR, Blocks.SULFUR, Blocks.SULFUR);
			ore(Ore.CINNABAR, Blocks.CINNABAR,
				Blocks.CINNABAR);
			for (Rock rock : Rock.values()) {
				if (ROCKS[rock.ordinal()] == null) {
					throw new IllegalStateException("No block for rock " + rock);
				}
			}
			for (Ore ore : Ore.values()) {
				if (ORES[ore.ordinal()] == null) {
					throw new IllegalStateException("No block for ore " + ore);
				}
			}
		}

		static BlockState rock(Rock rock) {
			return ROCKS[rock.ordinal()];
		}

		/** The rock broken up, as it lies in scree. */
		static BlockState rubble(Rock rock) {
			return rock == Rock.STONE ? COBBLESTONE : rock == Rock.DEEPSLATE ? COBBLED_DEEPSLATE : rock(rock);
		}

		static BlockState ore(Ore ore, boolean deep) {
			return (deep ? DEEP_ORES : ORES)[ore.ordinal()];
		}

		private static void rock(Rock rock, Block block) {
			ROCKS[rock.ordinal()] = block.defaultBlockState();
		}

		private static void ore(Ore ore, Block block, Block deepBlock) {
			ORES[ore.ordinal()] = block.defaultBlockState();
			DEEP_ORES[ore.ordinal()] = deepBlock.defaultBlockState();
		}
	}
}
