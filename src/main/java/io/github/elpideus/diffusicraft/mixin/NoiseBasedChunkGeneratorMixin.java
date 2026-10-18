package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.worldgen.CarverRoof;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Puts the lava level of Diffusicraft worlds ten blocks above the bottom, where vanilla has it in its own height.
 * Vanilla fixes it at y=-54, which in a world that goes down to y=-256 would flood every cave in 200 blocks of lava.
 * Also keeps carvers under a roof where their holes would fill with water; see {@link CarverRoof}.
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

	@WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/"
		+ "NoiseBasedChunkGenerator;applyCarvingMask(Lnet/minecraft/world/level/chunk/ChunkAccess;"
		+ "Lnet/minecraft/world/level/chunk/CarvingMask;Lnet/minecraft/world/level/levelgen/RandomState;"
		+ "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;"
		+ "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
		+ "Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/CarvingMask$Filter;)V"))
	private void diffusicraft$roofCarvers(NoiseBasedChunkGenerator generator, ChunkAccess chunk, CarvingMask mask,
										  RandomState randomState, MaterialRule materialRule,
										  WorldGenerationContext context, NoiseChunk noiseChunk,
										  Function<BlockPos, Holder<Biome>> biomes, CarvingMask.Filter filter,
										  Operation<Void> original) {
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale != null) {
			Rivers rivers = Rivers.of(TerrainFields.forState(randomState));
			filter = CarverRoof.filter(chunk, scale, rivers, settings.seaLevel(), filter);
		}
		original.call(generator, chunk, mask, randomState, materialRule, context, noiseChunk, biomes, filter);
	}
}
