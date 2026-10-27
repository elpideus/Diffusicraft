package io.github.elpideus.diffusicraft.mixin.biolith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Has Biolith get its overworld ready for a Diffusicraft world in the taller sizes too. Biolith does so for the level
 * whose dimension type is vanilla's overworld, and the taller sizes have dimension types of their own; without it,
 * Biolith's mods would place no biomes there. {@code ClimateBiomeSource.biolith$getDimensionType} does the same for
 * the biome source.
 */
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.BiomeCoordinator", remap = false)
abstract class BiomeCoordinatorMixin {
	@WrapOperation(method = "handleWorldStarting", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/Holder;unwrapKey()Ljava/util/Optional;"))
	private static Optional<ResourceKey<DimensionType>> diffusicraft$asOverworld(Holder<DimensionType> type,
		Operation<Optional<ResourceKey<DimensionType>>> original, @Local(argsOnly = true) ServerLevel level) {
		if (level.dimension() == Level.OVERWORLD && DiffusicraftWorldgen.usesModels(level.getChunkSource().getGenerator())) {
			return Optional.of(BuiltinDimensionTypes.OVERWORLD);
		}
		return original.call(type);
	}
}
