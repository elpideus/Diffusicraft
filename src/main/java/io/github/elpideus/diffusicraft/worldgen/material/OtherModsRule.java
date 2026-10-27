package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.Diffusicraft;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

import java.util.Set;

/**
 * The surface of other mods' biomes: the rules biome mods add to a Diffusicraft world's rule (see {@link SplitRule}),
 * then {@code rule}, which reads vanilla's rule as data packs such as Terralith's leave it. Everywhere else it gives
 * nothing, and the rest of the world's rule, which reads vanilla's own copies, has its way. The surface of other mods'
 * biomes still goes through Diffusicraft's slopes and soils ({@link StrataRule}).
 */
public record OtherModsRule(MaterialRule rule) implements MaterialRule {
	public static final MapCodec<OtherModsRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		MaterialRule.CODEC.fieldOf("rule").forGetter(OtherModsRule::rule)
	).apply(i, OtherModsRule::new));

	@Override
	public MapCodec<OtherModsRule> codec() {
		return CODEC;
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		Set<Holder<Biome>> possible = context.possibleBiomes();
		if (possible != null && possible.stream().noneMatch(OtherModsRule::foreign)) {
			return SplitRule.NOTHING;
		}
		RuleEvaluator additions = SplitRule.additions(context);
		RuleEvaluator vanilla = rule.compile(context);
		return (x, y, z) -> {
			if (!foreign(context.getBiome())) {
				return null;
			}
			BlockState state = additions == null ? null : additions.tryApply(x, y, z);
			return state != null ? state : vanilla.tryApply(x, y, z);
		};
	}

	/** Whether a biome is another mod's: neither vanilla's nor Diffusicraft's. */
	public static boolean foreign(Holder<Biome> biome) {
		if (!(biome instanceof Holder.Reference<Biome> reference)) {
			return false;
		}
		String namespace = reference.key().identifier().getNamespace();
		return !namespace.equals("minecraft") && !namespace.equals(Diffusicraft.MOD_ID);
	}
}
