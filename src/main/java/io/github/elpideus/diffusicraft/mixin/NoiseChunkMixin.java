package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.biome.HollowLakes;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fills the lakes of the Grand Hollows (see {@link HollowLakes}) in Diffusicraft worlds, by putting them in front of
 * the aquifer of every noise chunk. The noise fill and the carvers both ask the aquifer what fills open space, so a
 * carver's hole under a lake fills too.
 */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkMixin {
	@Shadow
	@Final
	@Mutable
	private Aquifer aquifer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void diffusicraft$hollowLakes(RandomState randomState, @Nullable Beardifier beardifier,
										  NoiseGeneratorSettings settings, Aquifer.FluidPicker globalFluidPicker,
										  Blender blender, DensityVolume volume, CallbackInfo ci) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) randomState).diffusicraft$biomeWorld();
		if (world != null) {
			aquifer = HollowLakes.aquifer(world, aquifer);
		}
	}
}
