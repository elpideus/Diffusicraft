package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.mixin.MaterialRuleContextAccessor;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * Fills the channels of the {@link Rivers}, and their lakes, with water and lines them with a bed of sand, gravel or
 * clay: mostly sand and clay in the lowlands, where rivers run slowly, mostly gravel above, and mostly clay in the still
 * water of lakes. The density leaves a channel solid up to the water, as only solid blocks reach material rules; this
 * rule turns it to water. It runs before every rule but the bedrock floor. Compiled inside {@link CoarseClimate#call}, it does nothing: the
 * coarse map knows no rivers.
 */
public record RiverRule(WorldScale scale) implements MaterialRule {
	public static final MapCodec<RiverRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(RiverRule::scale)
	).apply(i, RiverRule::new));

	/** Blocks of sediment under the water. */
	private static final int BED_THICKNESS = 2;
	/** Rivers below this level, in meters, run slowly enough to leave sand and clay; above it, mostly gravel. */
	private static final float LOWLAND = 150f;
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState SAND = Blocks.SAND.defaultBlockState();
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
	private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();

	@Override
	public MapCodec<RiverRule> codec() {
		return CODEC;
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		if (CoarseClimate.active()) {
			return (x, y, z) -> null;
		}
		MaterialRuleContextAccessor accessor = (MaterialRuleContextAccessor) (Object) context;
		TerrainField field = TerrainFields.forState(accessor.diffusicraft$randomState());
		Rivers rivers = Rivers.of(field);
		long seed = field.seed();
		WorldScale scale = this.scale;
		return (x, y, z) -> {
			RiverColumn column = rivers.column(scale, x, z);
			if (!column.channel || y > column.waterTop || y <= column.bed - BED_THICKNESS) {
				return null;
			}
			return y > column.bed ? WATER : bed(seed, column, x, z);
		};
	}

	private static BlockState bed(long seed, RiverColumn column, int x, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		int roll = (int) ((h >>> 33) % 100);
		if (column.lake) {
			return roll < 45 ? CLAY : roll < 80 ? SAND : GRAVEL;
		}
		if (column.level < LOWLAND) {
			return roll < 45 ? SAND : roll < 60 ? CLAY : GRAVEL;
		}
		return roll < 70 ? GRAVEL : SAND;
	}
}
