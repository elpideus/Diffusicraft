package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.biome.HeightTemperature;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Scales vanilla's cooling with height by {@link HeightTemperature#factor()}. */
@Mixin(Biome.class)
abstract class BiomeMixin {
	@ModifyConstant(method = "getHeightAdjustedTemperature", constant = @Constant(floatValue = 0.05F))
	private float diffusicraft$scaleLapseRate(float rate) {
		return rate * HeightTemperature.factor();
	}
}
