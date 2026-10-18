package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.world.level.biome.Climate;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Lets a climate sampler carry the {@link BiomeWorld} it samples; see {@link RandomStateMixin}. */
@Mixin(Climate.Sampler.class)
abstract class ClimateSamplerMixin implements BiomeWorld.Carrier {
	@Unique
	private @Nullable BiomeWorld diffusicraft$biomeWorld;

	@Override
	public @Nullable BiomeWorld diffusicraft$biomeWorld() {
		return diffusicraft$biomeWorld;
	}

	@Override
	public void diffusicraft$setBiomeWorld(@Nullable BiomeWorld world) {
		diffusicraft$biomeWorld = world;
	}
}
