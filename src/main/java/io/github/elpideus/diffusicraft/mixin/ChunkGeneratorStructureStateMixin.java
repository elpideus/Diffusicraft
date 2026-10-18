package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * Places stronghold rings with the coarse climate. Each of the 128 strongholds searches the biomes within 112 blocks
 * of its ring position, up to about 20 000 blocks out, before the first chunk generates. On the full model that is
 * minutes of inference over land nobody has visited. Other generators ignore the scope, so this changes nothing for
 * them.
 */
@Mixin(ChunkGeneratorStructureState.class)
abstract class ChunkGeneratorStructureStateMixin {
	@WrapOperation(method = "lambda$generateRingPositions$0", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/biome/BiomeSource;findBiomeHorizontal(IIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/RandomState;)Lcom/mojang/datafixers/util/Pair;"))
	private Pair<BlockPos, Holder<Biome>> diffusicraft$searchCoarsely(BiomeSource source, int x, int y, int z, int radius,
																	   Predicate<Holder<Biome>> allowed, RandomSource random,
																	   RandomState state,
																	   Operation<Pair<BlockPos, Holder<Biome>>> original) {
		return CoarseClimate.call(() -> original.call(source, x, y, z, radius, allowed, random, state));
	}
}
