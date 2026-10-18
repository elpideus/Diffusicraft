package io.github.elpideus.diffusicraft.worldgen.density;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;

/**
 * {@code diffusicraft:spawn_suitability}: how good a place is for the world spawn, from 0 (sea) to 1 (dry lowland
 * or hills), read from the coarse map only. The spawn search samples a few thousand blocks around the origin, and
 * running the full model over that area would take minutes.
 */
public record SpawnSuitabilityFunction(WorldScale scale) implements DensityFunction {
	public static final MapCodec<SpawnSuitabilityFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(SpawnSuitabilityFunction::scale)
	).apply(i, SpawnSuitabilityFunction::new));

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		TerrainField field = TerrainFields.forContext(context);
		WorldScale scale = this.scale;
		return new ColumnSampler(scale) {
			@Override
			float sampleColumn(int blockX, int blockZ) {
				return suitability(field.coarseElevation(scale.toPixel(blockZ), scale.toPixel(blockX)));
			}
		};
	}

	/** 1 between 100 and 700 m, falling to 0 at the sea and to 0.3 on high mountains. */
	static float suitability(float elevation) {
		if (elevation <= 0f) {
			return 0f;
		}
		if (elevation < 100f) {
			return elevation / 100f;
		}
		if (elevation <= 700f) {
			return 1f;
		}
		return Math.max(0.3f, 1f - (elevation - 700f) / 2000f);
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return Interval.of(0f, 1f);
	}

	@Override
	public @Axes int domainAxes() {
		return AXIS_X | AXIS_Z;
	}

	@Override
	public MapCodec<SpawnSuitabilityFunction> codec() {
		return CODEC;
	}
}
