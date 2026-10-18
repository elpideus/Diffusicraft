package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The material rule the noise settings were loaded with. Diffusicraft tells its own settings by that rule's key, but
 * TerraBlender makes {@link NoiseGeneratorSettings#materialRule()} return a new, unkeyed rule that puts the biome mods'
 * rules in front of it.
 */
@Mixin(NoiseGeneratorSettings.class)
public interface NoiseGeneratorSettingsAccessor {
	@Accessor("materialRule")
	Holder<MaterialRule> diffusicraft$materialRule();
}
