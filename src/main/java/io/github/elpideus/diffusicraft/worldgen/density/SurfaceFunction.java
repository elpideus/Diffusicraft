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
 * {@code diffusicraft:surface}: the height of the terrain surface, in blocks, at each column. Terrain density is
 * built from it as {@code (surface - y) / 16}; see the noise settings in {@code data/diffusicraft/worldgen}.
 */
public record SurfaceFunction(WorldScale scale) implements DensityFunction {
	public static final MapCodec<SurfaceFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(SurfaceFunction::scale)
	).apply(i, SurfaceFunction::new));

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		TerrainField field = TerrainFields.forContext(context);
		WorldScale scale = this.scale;
		return new ColumnSampler(scale) {
			@Override
			float sampleColumn(int blockX, int blockZ) {
				return scale.surfaceY(field.elevation(scale.toPixel(blockZ), scale.toPixel(blockX)));
			}
		};
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return Interval.of(scale.minSurfaceY(), scale.maxSurfaceY());
	}

	@Override
	public @Axes int domainAxes() {
		return AXIS_X | AXIS_Z;
	}

	@Override
	public MapCodec<SurfaceFunction> codec() {
		return CODEC;
	}
}
