package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;
import java.util.function.Function;

/**
 * Lets the strata rule find the world's random state, and through it the terrain field the chunk was built from. The
 * invokers let the LOD sampler run the world's material rules on single columns, without a chunk; see
 * {@link io.github.elpideus.diffusicraft.worldgen.lod.LodSampler}.
 */
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccessor {
	@Accessor("randomState")
	RandomState diffusicraft$randomState();

	@Invoker("<init>")
	static MaterialRuleContext diffusicraft$create(MaterialSystem system, RandomState randomState,
												   DensityVolume expectedVolume, DensitySamplerSet densitySamplers,
												   Function<BlockPos, Holder<Biome>> biomeGetter,
												   WorldGenerationContext context,
												   @Nullable Set<Holder<Biome>> possibleBiomes) {
		throw new AssertionError();
	}

	@Invoker("updateXZ")
	void diffusicraft$updateXZ(int blockX, int blockZ, int surfaceGradientX, int surfaceGradientZ);

	@Invoker("updateY")
	void diffusicraft$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockY);
}
