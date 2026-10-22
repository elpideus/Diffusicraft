package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * {@code diffusicraft:rockfall}: now and then, a few boulders at the foot of a rock face in the chunk, fallen from it:
 * of the face's own rock, as big as the face is high, and the biggest rolled the farthest out. A cliff of soil, or one
 * lower than {@code min_rise} blocks, sheds none. {@link Boulder#foot} finds the face from the heightmaps, and where it
 * rains, moss grows on the boulders. Only in Diffusicraft worlds, whose climate it reads.
 */
public record RockfallFeature(int minRise, float chance) implements Feature {
	public static final MapCodec<RockfallFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.intRange(4, 4096).fieldOf("min_rise").forGetter(RockfallFeature::minRise),
		Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(RockfallFeature::chance)
	).apply(instance, RockfallFeature::new));

	@Override
	public MapCodec<RockfallFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, @Nullable ChunkGenerator generator, RandomSource random,
						 BlockPos origin) {
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		if (world == null || random.nextFloat() >= chance) {
			return false;
		}
		int x0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()));
		int z0 = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()));
		Boulder.Ground ground = Boulders.ground(level);
		Boulder.Foot foot = Boulder.foot(ground, x0, z0, minRise, random);
		BlockState rock = foot == null ? null : rock(level, ground, foot);
		if (rock == null) {
			return false;
		}
		float moss = Boulders.moss(ClimateColumn.at(world.field(), world.scale(), x0 + 8, z0 + 8).column.precipitation);
		float size = Math.clamp(foot.rise() / 10f, 1.5f, 4.5f) * (float) Math.sqrt(GiantTree.size(world.scale()));
		float[] radii = new float[1 + random.nextInt(foot.rise() >= 24 ? 4 : 3)];
		for (int i = 0; i < radii.length; i++) {
			radii[i] = Math.max(size * (0.5f + 0.6f * random.nextFloat()), 1.2f);
		}
		Arrays.sort(radii);
		boolean placed = false;
		for (int i = 0; i < radii.length; i++) {
			Boulder boulder = Boulder.shape(radii[i], 0.6f + 0.3f * random.nextFloat(), random);
			// Out from the foot, away from the face, and across it: the bigger the boulder, the farther it rolled.
			int out = Math.round(radii[i]) + 2 * i + random.nextInt(3);
			int across = Math.round((2f * random.nextFloat() - 1f) * (2 + 2 * i));
			placed |= Boulders.setDown(level, ground, boulder, foot.x() - foot.dx() * out - foot.dz() * across,
				foot.z() - foot.dz() * out + foot.dx() * across, 2 * Math.round(boulder.halfHeight), rock, moss, x0, z0,
				random);
		}
		return placed;
	}

	/**
	 * The rock of the face over a foot: the most common rock at the top of the ground and just under it, up the face.
	 * Null if most of the face is not rock.
	 */
	private static @Nullable BlockState rock(WorldGenLevel level, Boulder.Ground ground, Boulder.Foot foot) {
		Object2IntOpenHashMap<BlockState> rocks = new Object2IntOpenHashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int samples = 0, rocky = 0, mostCommon = 0;
		BlockState rock = null;
		for (int d = 1; d <= foot.run(); d++) {
			int x = foot.x() + foot.dx() * d, z = foot.z() + foot.dz() * d, top = ground.top(x, z);
			for (int y = top; y > top - 2; y--) {
				samples++;
				BlockState state = level.getBlockState(pos.set(x, y, z));
				if (isRock(state)) {
					rocky++;
					BlockState block = state.getBlock().defaultBlockState();
					int count = rocks.addTo(block, 1) + 1;
					if (count > mostCommon) {
						mostCommon = count;
						rock = block;
					}
				}
			}
		}
		return 2 * rocky >= samples ? rock : null;
	}

	private static boolean isRock(BlockState state) {
		return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.SANDSTONE)
			|| state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE);
	}
}
