package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the lava level of Diffusicraft worlds ten blocks above the bottom, where vanilla has it in its own height.
 * Vanilla fixes it at y=-54, which in a world that goes down to y=-256 would flood every cave in 200 blocks of lava.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
	@Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$lavaAboveBottom(NoiseGeneratorSettings settings,
													 CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
		if (!DiffusicraftWorldgen.isDiffusicraft(settings)) {
			return;
		}
		int lavaLevel = settings.noiseSettings().minY() + 10;
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
		int seaLevel = settings.seaLevel();
		Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
		Aquifer.FluidStatus empty = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
		int lavaBelow = Math.min(lavaLevel, seaLevel);
		cir.setReturnValue((x, y, z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? empty
			: y < lavaBelow ? lava : sea);
	}
}
