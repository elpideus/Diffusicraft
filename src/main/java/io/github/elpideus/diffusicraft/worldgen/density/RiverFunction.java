package io.github.elpideus.diffusicraft.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.util.Interval;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * {@code diffusicraft:river}: how the {@link Rivers} reshape the ground. The world's final density takes the maximum
 * with {@code fill}, which is solid between a river column's band bottom and its surface, then the minimum with
 * {@code carve}, which is air above the surface. Away from rivers they are -1 and 1 and change nothing.
 */
public record RiverFunction(WorldScale scale, Mode mode) implements DensityFunction {
	public static final MapCodec<RiverFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(RiverFunction::scale),
		Mode.CODEC.fieldOf("mode").forGetter(RiverFunction::mode)
	).apply(i, RiverFunction::new));

	public enum Mode implements StringRepresentable {
		/** Positive below the surface of a river column, -1 above it; 1 elsewhere. */
		CARVE("carve"),
		/** Positive between the band bottom and the surface of a river column, negative outside; -1 elsewhere. */
		FILL("fill");

		public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

		private final String id;

		Mode(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return id;
		}
	}

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		return new Sampler(Rivers.of(TerrainFields.forContext(context)), scale, mode == Mode.CARVE);
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return Interval.of(-1f, 1f);
	}

	@Override
	public @Axes int domainAxes() {
		return ALL_AXES;
	}

	@Override
	public MapCodec<RiverFunction> codec() {
		return CODEC;
	}

	private record Sampler(Rivers rivers, WorldScale scale, boolean carve) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			float outside = carve ? 1f : -1f;
			for (int z = 0; z < volume.sizeZ(); z++) {
				int blockZ = volume.blockZ(z);
				for (int x = 0; x < volume.sizeX(); x++) {
					RiverColumn column = rivers.column(scale, volume.blockX(x), blockZ);
					int index = volume.indexUnchecked(x, 0, z);
					if (!column.river) {
						outputBuffer.setRange(index, volume.sizeY(), outside);
						continue;
					}
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(index + y, density(column, volume.blockY(y)));
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			RiverColumn column = rivers.column(scale, blockX, blockZ);
			return column.river ? density(column, blockY) : carve ? 1f : -1f;
		}

		private float density(RiverColumn column, int y) {
			float above = column.surface - y;
			float value = carve ? above : Math.min(above, y - column.bandBottom);
			return Math.clamp(value / 16f, -1f, 1f);
		}
	}
}
