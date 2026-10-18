package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attaches the {@link BiomeWorld} to the random state of a Diffusicraft world, and passes it on to every climate
 * sampler the state creates, so the biome source knows which world it resolves biomes for.
 */
@Mixin(RandomState.class)
abstract class RandomStateMixin implements BiomeWorld.Carrier {
	@Unique
	private @Nullable BiomeWorld diffusicraft$biomeWorld;

	@Inject(method = "create(Lnet/minecraft/core/HolderGetter;JLnet/minecraft/world/level/levelgen/"
		+ "NoiseGeneratorSettings;)Lnet/minecraft/world/level/levelgen/RandomState;", at = @At("RETURN"))
	private static void diffusicraft$attachWorld(HolderGetter<NormalNoise> noises, long seed,
												 NoiseGeneratorSettings settings,
												 CallbackInfoReturnable<RandomState> cir) {
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale != null) {
			RandomState state = cir.getReturnValue();
			((BiomeWorld.Carrier) (Object) state).diffusicraft$setBiomeWorld(
				BiomeWorld.of(state, scale, settings.noiseSettings().minY()));
		}
	}

	@Inject(method = "createClimateSampler", at = @At("RETURN"))
	private void diffusicraft$passWorld(SamplerContext context, CallbackInfoReturnable<Climate.Sampler> cir) {
		if (diffusicraft$biomeWorld != null) {
			((BiomeWorld.Carrier) (Object) cir.getReturnValue()).diffusicraft$setBiomeWorld(diffusicraft$biomeWorld);
		}
	}

	@Override
	public @Nullable BiomeWorld diffusicraft$biomeWorld() {
		return diffusicraft$biomeWorld;
	}

	@Override
	public void diffusicraft$setBiomeWorld(@Nullable BiomeWorld world) {
		diffusicraft$biomeWorld = world;
	}
}
