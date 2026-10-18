package io.github.elpideus.diffusicraft.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.util.Interval;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * {@code diffusicraft:climate}: one of the six multi-noise biome parameters, derived from the real terrain and
 * climate by {@link ClimateMapping}. Used in the noise router in place of vanilla's climate noises.
 */
public record ClimateFunction(WorldScale scale, Parameter parameter) implements DensityFunction {
	public static final MapCodec<ClimateFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(ClimateFunction::scale),
		Parameter.CODEC.fieldOf("parameter").forGetter(ClimateFunction::parameter)
	).apply(i, ClimateFunction::new));

	public enum Parameter implements StringRepresentable {
		TEMPERATURE("temperature", ClimateMapping.TEMPERATURE),
		HUMIDITY("humidity", ClimateMapping.HUMIDITY),
		CONTINENTALNESS("continentalness", ClimateMapping.CONTINENTALNESS),
		EROSION("erosion", ClimateMapping.EROSION),
		WEIRDNESS("weirdness", ClimateMapping.WEIRDNESS),
		DEPTH("depth", -1);

		public static final Codec<Parameter> CODEC = StringRepresentable.fromEnum(Parameter::values);

		private final String id;
		private final int index;

		Parameter(String id, int index) {
			this.id = id;
			this.index = index;
		}

		@Override
		public String getSerializedName() {
			return id;
		}
	}

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		TerrainField field = TerrainFields.forContext(context);
		if (parameter == Parameter.DEPTH) {
			return new DepthSampler(field, scale);
		}
		int index = parameter.index;
		return new ColumnSampler(scale) {
			@Override
			float sampleColumn(int blockX, int blockZ) {
				return ClimateColumn.at(field, scale, blockX, blockZ).parameters[index];
			}
		};
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return parameter == Parameter.DEPTH ? Interval.of(-32f, 32f) : Interval.of(-1f, 1f);
	}

	@Override
	public @Axes int domainAxes() {
		return parameter == Parameter.DEPTH ? ALL_AXES : AXIS_X | AXIS_Z;
	}

	@Override
	public MapCodec<ClimateFunction> codec() {
		return CODEC;
	}

	private record DepthSampler(TerrainField field, WorldScale scale) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			for (int z = 0; z < volume.sizeZ(); z++) {
				int blockZ = volume.blockZ(z);
				for (int x = 0; x < volume.sizeX(); x++) {
					float surface = ClimateColumn.at(field, scale, volume.blockX(x), blockZ).surfaceY;
					int index = volume.indexUnchecked(x, 0, z);
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(index + y, ClimateMapping.depth(scale, surface, volume.blockY(y)));
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			return ClimateMapping.depth(scale, ClimateColumn.at(field, scale, blockX, blockZ).surfaceY, blockY);
		}
	}
}
