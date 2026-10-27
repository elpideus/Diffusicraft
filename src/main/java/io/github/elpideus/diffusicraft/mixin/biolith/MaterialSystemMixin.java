package io.github.elpideus.diffusicraft.mixin.biolith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.material.OtherModsRule;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Biolith's surface builders to other mods' biomes in a Diffusicraft world. Biolith runs them around the first
 * two biome checks of {@code buildSurface} (eroded badlands and frozen ocean), for every biome a builder accepts. This
 * wraps those checks outside Biolith's wrappers (a higher priority wraps outside) and calls past them for vanilla's
 * and Diffusicraft's biomes, so their ground stays as Diffusicraft builds it.
 */
@Mixin(value = MaterialSystem.class, priority = 1100)
abstract class MaterialSystemMixin {
	@WrapOperation(method = "buildSurface", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z", ordinal = 0))
	private boolean diffusicraft$surfaceBuilders(Holder<Biome> biome, ResourceKey<Biome> key,
		Operation<Boolean> original, @Local(argsOnly = true) RandomState randomState) {
		return ownBiome(randomState, biome) ? biome.is(key) : original.call(biome, key);
	}

	@WrapOperation(method = "buildSurface", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z", ordinal = 1))
	private boolean diffusicraft$lateSurfaceBuilders(Holder<Biome> biome, ResourceKey<Biome> key,
		Operation<Boolean> original, @Local(argsOnly = true) RandomState randomState) {
		return ownBiome(randomState, biome) ? biome.is(key) : original.call(biome, key);
	}

	private static boolean ownBiome(RandomState randomState, Holder<Biome> biome) {
		return ((BiomeWorld.Carrier) (Object) randomState).diffusicraft$biomeWorld() != null
			&& !OtherModsRule.foreign(biome);
	}
}
