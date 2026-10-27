package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Supplier;

/**
 * The features of each step of decoration, in the order the generator sorted them. A feature's place in its step seeds
 * its draws in every chunk; the far terrain follows them to find where the biomes plant their giant trees.
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
	@Accessor("featuresPerStep")
	Supplier<List<FeatureSorter.StepFeatureData>> diffusicraft$featuresPerStep();
}
