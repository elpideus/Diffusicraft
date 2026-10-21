package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.MapCodec;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;

/**
 * {@code diffusicraft:diffusicraft_world}: places a feature only in a Diffusicraft world. For the features Diffusicraft
 * adds to vanilla's biomes, which other worlds keep as vanilla made them.
 */
public record DiffusicraftWorldFilter() implements PlacementFilter {
	public static final MapCodec<DiffusicraftWorldFilter> CODEC = MapCodec.unit(DiffusicraftWorldFilter::new);

	@Override
	public boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin) {
		return ((BiomeWorld.Carrier) (Object) context.getLevel().getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld() != null;
	}

	@Override
	public MapCodec<DiffusicraftWorldFilter> codec() {
		return CODEC;
	}
}
