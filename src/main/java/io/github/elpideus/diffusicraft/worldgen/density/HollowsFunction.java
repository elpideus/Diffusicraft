package io.github.elpideus.diffusicraft.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.function.IntBinaryOperator;

/**
 * {@code diffusicraft:hollows}: the density of the {@link Hollows}, negative in the open and {@link Hollows#SOLID} in
 * solid rock. The world's final density takes the minimum with it. {@code min_y} must be the bottom of the world, as
 * the deep tiers are placed relative to the lava level above it.
 */
public record HollowsFunction(WorldScale scale, int minY) implements DensityFunction {
	public static final MapCodec<HollowsFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(HollowsFunction::scale),
		Codec.INT.fieldOf("min_y").forGetter(HollowsFunction::minY)
	).apply(i, HollowsFunction::new));

	/** The hollows seed of a world is derived from the world seed and this name. */
	public static final Identifier SEED_ID = Diffusicraft.id("hollows");

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		TerrainField field = TerrainFields.forContext(context);
		Hollows hollows = Hollows.of(context.createRandom(SEED_ID).nextLong(), minY);
		WorldScale scale = this.scale;
		IntBinaryOperator surface = (x, z) -> (int) Math.floor(scale.surfaceY(field.elevation(scale.toPixel(z),
			scale.toPixel(x))));
		return new Sampler(hollows, surface);
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return Interval.of(Hollows.OPEN, Hollows.SOLID);
	}

	@Override
	public @Axes int domainAxes() {
		return ALL_AXES;
	}

	@Override
	public MapCodec<HollowsFunction> codec() {
		return CODEC;
	}

	/** Shared by every thread generating the world, so each thread keeps its own column. */
	private record Sampler(Hollows hollows, IntBinaryOperator surface, ThreadLocal<Hollows.Column> columns)
		implements DensitySampler {
		Sampler(Hollows hollows, IntBinaryOperator surface) {
			this(hollows, surface, ThreadLocal.withInitial(Hollows.Column::new));
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			Hollows.Column column = columns.get();
			for (int z = 0; z < volume.sizeZ(); z++) {
				int blockZ = volume.blockZ(z);
				for (int x = 0; x < volume.sizeX(); x++) {
					int blockX = volume.blockX(x);
					hollows.column(blockX, blockZ, surface, column);
					int index = volume.indexUnchecked(x, 0, z);
					if (column.solid()) {
						outputBuffer.setRange(index, volume.sizeY(), Hollows.SOLID);
						continue;
					}
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(index + y, hollows.density(column, volume.blockY(y)));
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			// Heightmap probes and aquifers walk down one column at a time.
			Hollows.Column column = columns.get();
			if (!column.isAt(blockX, blockZ)) {
				hollows.column(blockX, blockZ, surface, column);
			}
			return hollows.density(column, blockY);
		}
	}
}
