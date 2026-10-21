package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Leaves the vanilla features that Diffusicraft worlds do without out of their decoration, and adds their waterfalls
 * after vanilla's water springs; see {@link DiffusicraftWorldgen#leavesOut} and
 * {@link DiffusicraftWorldgen#waterfallsAfter}. Biomes are shared by every world, so their lists cannot drop or add
 * them. The features keep their places in the order, so the others are seeded as before.
 */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
	/** Whether the generator is a Diffusicraft world's: 0 until known, then 1 or -1. Its settings never change. */
	@Unique
	private byte diffusicraft$own;

	@WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
		+ "levelgen/placement/FeaturePlacer;placeWithBiomeCheck(Lnet/minecraft/world/level/levelgen/placement/"
		+ "PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
	private boolean diffusicraft$leaveOut(FeaturePlacer placer, PlacedFeature feature, RandomSource random,
										  BlockPos origin, Operation<Boolean> place,
										  @Local(argsOnly = true) WorldGenLevel level) {
		if (diffusicraft$own == 0) {
			diffusicraft$own = (Object) this instanceof NoiseBasedChunkGenerator generator
				&& DiffusicraftWorldgen.isDiffusicraft(generator.generatorSettings().value()) ? (byte) 1 : (byte) -1;
		}
		if (diffusicraft$own < 0) {
			return place.call(placer, feature, random, origin);
		}
		Registry<PlacedFeature> features = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
		Optional<ResourceKey<PlacedFeature>> key = features.getResourceKey(feature);
		if (DiffusicraftWorldgen.leavesOut(key)) {
			return false;
		}
		boolean placed = place.call(placer, feature, random, origin);
		if (DiffusicraftWorldgen.waterfallsAfter(key)) {
			PlacedFeature waterfalls = features.getValue(DiffusicraftWorldgen.WATERFALLS);
			if (waterfalls != null) {
				placed |= placer.place(waterfalls, random, origin);
			}
		}
		return placed;
	}
}
