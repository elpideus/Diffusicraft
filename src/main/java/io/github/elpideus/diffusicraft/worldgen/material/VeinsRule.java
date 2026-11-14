package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.mixin.MaterialRuleContextAccessor;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * Vanilla's ore veins over the band of heights they can reach, {@code minY} up to but not including {@code maxY}. A vein
 * rule fills two buffers of densities for the whole volume it is compiled on, which is made for vanilla's short chunks:
 * under a mountain it is megabytes a chunk, and the collector's largest allocations, for a rule that gives nothing above
 * a few dozen blocks. So {@code rule} is compiled on a context of its own that covers the band. The densities do not
 * depend on the volume, because the cells they are interpolated in sit at absolute positions.
 */
public record VeinsRule(int minY, int maxY, MaterialRule rule) implements MaterialRule {
	public static final MapCodec<VeinsRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.INT.fieldOf("min_y").forGetter(VeinsRule::minY),
		Codec.INT.fieldOf("max_y").forGetter(VeinsRule::maxY),
		MaterialRule.CODEC.fieldOf("rule").forGetter(VeinsRule::rule)
	).apply(i, VeinsRule::new));

	@Override
	public MapCodec<VeinsRule> codec() {
		return CODEC;
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		MaterialRuleContextAccessor world = (MaterialRuleContextAccessor) (Object) context;
		DensityVolume expected = world.diffusicraft$expectedVolume();
		if (expected.sizeY() <= maxY - minY) {
			// Nothing to save, as on the far terrain's single columns.
			return rule.compile(context);
		}
		DensityVolume band = new DensityVolume(expected.sizeX(), maxY - minY, expected.sizeZ(), expected.minBlockX(), minY,
			expected.minBlockZ());
		MaterialRuleContext veins = MaterialRuleContextAccessor.diffusicraft$create(world.diffusicraft$system(),
			world.diffusicraft$randomState(), band, world.diffusicraft$densitySamplers(), world.diffusicraft$biomeGetter(),
			world.diffusicraft$context(), context.possibleBiomes());
		MaterialRuleContextAccessor position = (MaterialRuleContextAccessor) (Object) veins;
		RuleEvaluator evaluator = rule.compile(veins);
		int[] column = {Integer.MIN_VALUE, Integer.MIN_VALUE};
		return (x, y, z) -> {
			if (y < minY || y >= maxY) {
				return null;
			}
			if (x != column[0] || z != column[1]) {
				column[0] = x;
				column[1] = z;
				position.diffusicraft$updateXZ(x, z, 0, 0);
			}
			position.diffusicraft$updateY(0, 0, 0, y);
			return evaluator.tryApply(x, y, z);
		};
	}
}
