package io.github.elpideus.diffusicraft.mixin;

import io.github.elpideus.diffusicraft.worldgen.feature.Springs;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves out the water springs of Diffusicraft worlds that would fall down a cliff onto dry ground ({@link Springs}),
 * once vanilla has found the spot fit for one.
 */
@Mixin(SpringFeature.class)
abstract class SpringFeatureMixin {
	@Inject(method = "place", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
		+ "WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private void diffusicraft$leaveOutCliffSprings(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
												   BlockPos origin, CallbackInfoReturnable<Boolean> info) {
		if (((SpringFeature) (Object) this).state().is(FluidTags.WATER) && Springs.leftOut(level, origin)) {
			info.setReturnValue(false);
		}
	}
}
