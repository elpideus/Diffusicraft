package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.SharedContainerData;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shares the biomes of sections with a single biome, see {@link SharedContainerData}. New biomes start from the old
 * container's first biome, so a section filled with one other biome ends up with two in its palette.
 */
@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionMixin {
	@Shadow private PalettedContainerRO<Holder<Biome>> biomes;

	// Moonrise overwrites this method; the injection still applies to its version.
	@Inject(method = "fillBiomesFromNoise", at = @At("RETURN"))
	private void diffusicraft$shareUniformBiomes(CallbackInfo ci) {
		if (biomes instanceof SharedContainerData.Container container) {
			container.diffusicraft$shareIfUniform();
		}
	}
}
