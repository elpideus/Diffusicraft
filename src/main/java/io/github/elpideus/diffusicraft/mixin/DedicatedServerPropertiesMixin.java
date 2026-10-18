package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Diffusicraft the default {@code level-type} of a new {@code server.properties}. A file that already names a
 * level type keeps it.
 */
@Mixin(DedicatedServerProperties.class)
abstract class DedicatedServerPropertiesMixin {
	@ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD",
		target = "Lnet/minecraft/world/level/levelgen/presets/WorldPresets;NORMAL:Lnet/minecraft/resources/ResourceKey;"))
	private ResourceKey<WorldPreset> diffusicraft$defaultLevelType(ResourceKey<WorldPreset> normal) {
		return DiffusicraftWorldgen.STANDARD_PRESET;
	}
}
