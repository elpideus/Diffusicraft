package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * The whole material rule of a Diffusicraft world. Biome mods put rules of their own in front of a world's rule:
 * TerraBlender and FrozenLib whenever it is read, Biolith when the world starts. Diffusicraft compiles its own rule
 * apart from what they made of it ({@link SplitRule}), and compiles theirs only for the biomes of other mods
 * ({@link OtherModsRule}), with this rule inside giving nothing.
 */
public record WorldRule(MaterialRule rule) implements MaterialRule {
	public static final MapCodec<WorldRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		MaterialRule.CODEC.fieldOf("rule").forGetter(WorldRule::rule)
	).apply(i, WorldRule::new));

	@Override
	public MapCodec<WorldRule> codec() {
		return CODEC;
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		return SplitRule.muted() ? SplitRule.NOTHING : rule.compile(context);
	}
}
