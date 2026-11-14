package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.serialization.Lifecycle;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Diffusicraft's dimensions from counting as experimental. Vanilla says so of any overworld on a dimension type of
 * its own, which warns about every new world and offers a backup each time one is opened. Both are meant for settings a
 * player found in a data pack, not for the world type this mod is there to make.
 */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
	@ModifyReturnValue(method = "checkStability", at = @At("RETURN"))
	private static Lifecycle diffusicraft$stable(Lifecycle vanilla, ResourceKey<LevelStem> key, LevelStem dimension) {
		return dimension.generator() instanceof NoiseBasedChunkGenerator generator
			&& DiffusicraftWorldgen.isDiffusicraft(generator.generatorSettings().value()) ? Lifecycle.stable() : vanilla;
	}
}
