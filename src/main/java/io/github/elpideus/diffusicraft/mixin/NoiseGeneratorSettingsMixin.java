package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Keeps what the noise settings were loaded with. Diffusicraft tells its own settings by their material rule's key
 * and builds their surface with it, but biome mods change what {@link NoiseGeneratorSettings#materialRule()} returns:
 * TerraBlender and FrozenLib wrap it whenever it is read, and Biolith replaces the field itself. Lithostitched
 * replaces the noise router and the aquifers with its mods' wrappers, by the key of the level, which a Diffusicraft
 * overworld shares with vanilla's; {@code RandomStateMixin} puts them back.
 */
@Mixin(NoiseGeneratorSettings.class)
abstract class NoiseGeneratorSettingsMixin implements DiffusicraftWorldgen.Loaded {
	@Shadow
	@Final
	@Mutable
	private Holder<MaterialRule> materialRule;
	@Shadow
	@Final
	@Mutable
	private NoiseRouter noiseRouter;
	@Shadow
	@Final
	@Mutable
	private Optional<Aquifer.Config> aquifers;
	@Unique
	private Holder<MaterialRule> diffusicraft$loadedRule;
	@Unique
	private NoiseRouter diffusicraft$loadedRouter;
	@Unique
	private Optional<Aquifer.Config> diffusicraft$loadedAquifers;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void diffusicraft$keepLoaded(CallbackInfo ci) {
		diffusicraft$loadedRule = materialRule;
		diffusicraft$loadedRouter = noiseRouter;
		diffusicraft$loadedAquifers = aquifers;
	}

	@Override
	public Holder<MaterialRule> diffusicraft$loadedRule() {
		return diffusicraft$loadedRule;
	}

	@Override
	public void diffusicraft$setMaterialRule(Holder<MaterialRule> rule) {
		materialRule = rule;
	}

	@Override
	public void diffusicraft$restoreTerrain() {
		noiseRouter = diffusicraft$loadedRouter;
		aquifers = diffusicraft$loadedAquifers;
	}
}
