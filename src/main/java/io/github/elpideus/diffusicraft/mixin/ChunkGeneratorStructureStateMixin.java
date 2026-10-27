package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Pair;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Places stronghold rings with the coarse climate, and spreads the villages and pillager outposts of a Diffusicraft
 * world wider.
 *
 * <p>Each of the 128 strongholds searches the biomes within 112 blocks of its ring position, up to about 20 000 blocks
 * out, before the first chunk generates. On the full model that is minutes of inference over land nobody has visited.
 * Other generators ignore the scope, so this changes nothing for them.
 *
 * <p>A Diffusicraft world's plains and taigas run on for thousands of blocks, where vanilla's spread puts a village in
 * every cell of 34 by 34 chunks, one every 400 blocks. In a Diffusicraft world, vanilla's sets take the placements of
 * Diffusicraft's sets of the same names, which list no structures of their own: every village that vanilla, a datapack
 * or a mod lists still generates, further apart. Other worlds keep vanilla's.
 */
@Mixin(ChunkGeneratorStructureState.class)
abstract class ChunkGeneratorStructureStateMixin {
	@Unique
	private static final Map<ResourceKey<StructureSet>, ResourceKey<StructureSet>> SPREADS = Map.of(
		BuiltinStructureSets.VILLAGES, ResourceKey.create(Registries.STRUCTURE_SET, Diffusicraft.id("villages")),
		BuiltinStructureSets.PILLAGER_OUTPOSTS,
		ResourceKey.create(Registries.STRUCTURE_SET, Diffusicraft.id("pillager_outposts")));

	@WrapOperation(method = "lambda$generateRingPositions$0", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/biome/BiomeSource;findBiomeHorizontal(IIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/RandomState;)Lcom/mojang/datafixers/util/Pair;"))
	private Pair<BlockPos, Holder<Biome>> diffusicraft$searchCoarsely(BiomeSource source, int x, int y, int z, int radius,
																	   Predicate<Holder<Biome>> allowed, RandomSource random,
																	   RandomState state,
																	   Operation<Pair<BlockPos, Holder<Biome>>> original) {
		return CoarseClimate.call(() -> original.call(source, x, y, z, radius, allowed, random, state));
	}

	@SuppressWarnings("unchecked")
	@ModifyExpressionValue(method = "createForNormal", at = @At(value = "INVOKE",
		target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
	private static Object diffusicraft$spreadVillages(Object sets, @Local(argsOnly = true) RandomState state,
													  @Local(argsOnly = true) HolderLookup<StructureSet> registry) {
		if (((BiomeWorld.Carrier) (Object) state).diffusicraft$biomeWorld() == null) {
			return sets;
		}
		return ((List<Holder<StructureSet>>) sets).stream()
			.map(set -> set.unwrapKey().map(SPREADS::get).flatMap(registry::get)
				.<Holder<StructureSet>>map(spread -> Holder.direct(
					new StructureSet(set.value().structures(), spread.value().placement())))
				.orElse(set))
			.toList();
	}
}
