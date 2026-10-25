package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.block.DiffusicraftBlocks;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * {@code diffusicraft:erratic}: now and then, a lone boulder on open ground in the cold lands, as the ice sheets left
 * them where they melted: of granite, gneiss or diorite, rock carried from far away rather than the ground's own, most
 * of them a few blocks across, and now and then one as big as a house.
 *
 * <p>The colder the climate, the likelier a chunk is to have one: {@code chance} of the chunks where the year's mean
 * temperature is {@value #COLD} °C or less, none from {@value #MILD} °C. They lie on level ground only, and where it
 * rains, moss grows on them. Only in Diffusicraft worlds, whose climate it reads.
 */
public record ErraticFeature(float chance) implements Feature {
	public static final MapCodec<ErraticFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(ErraticFeature::chance)
	).apply(instance, ErraticFeature::new));

	/** The year's mean temperature up to which erratics lie at their full chance, in °C. */
	private static final float COLD = 8f;
	/** The year's mean temperature from which none lie. */
	private static final float MILD = 13f;

	@Override
	public MapCodec<ErraticFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, @Nullable ChunkGenerator generator, RandomSource random,
						 BlockPos origin) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		if (world == null) {
			return false;
		}
		int x0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
		int z0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));
		TerrainField.Column column = ClimateColumn.at(world.field(), world.scale(), x0 + 8, z0 + 8).column;
		if (random.nextFloat() >= chance * Math.clamp((MILD - column.temperature) / (MILD - COLD), 0f, 1f)) {
			return false;
		}
		// Most are 3 to 5 blocks across, one in twenty 13 or more and the biggest some 25: as big as the giant trees,
		// they shrink with the world, though less than the trees.
		float u = random.nextFloat(), u3 = u * u * u;
		float radius = Math.max((1.25f + 2f * u3 + 6.5f * u3 * u3 * u3 * u3) * (float) Math.sqrt(GiantTree.size(
			world.scale())), 1.2f);
		Boulder boulder = Boulder.shape(radius, 0.5f + 0.4f * random.nextFloat(), random);
		return Boulders.setDown(level, Boulders.ground(level), boulder, x0 + random.nextInt(16),
			z0 + random.nextInt(16), Math.round(boulder.halfHeight), rock(random), Boulders.moss(column.precipitation),
			x0, z0, random);
	}

	/** The rock of an erratic: the hard crystalline rocks that the ice carried the farthest. */
	private static BlockState rock(RandomSource random) {
		float pick = random.nextFloat();
		return (pick < 0.4f ? Blocks.GRANITE : pick < 0.8f ? DiffusicraftBlocks.GNEISS : Blocks.DIORITE)
			.defaultBlockState();
	}
}
