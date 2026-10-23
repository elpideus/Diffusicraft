package io.github.elpideus.diffusicraft.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.util.Interval;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * {@code diffusicraft:coast}: the sea cliffs and islets, block by block. The terrain is interpolated between the corners
 * of cells 4 blocks wide, which would smear a cliff's face into a slope reaching out to sea and an islet into the
 * water, so in the columns of a cliff or an islet and beside them ({@link TerrainSurface#cliff}) the world's final
 * density takes the maximum with {@code fill}, solid from the lowest corner of the column's cell up to the exact
 * ground, then the minimum with {@code carve}, air above the exact ground. Elsewhere they are -1 and 1 and change
 * nothing.
 *
 * <p>The fill goes down to the lowest corner, not just to the interpolated ground: deep in the rock, the density of a
 * corner on the cliff is only as solid as the caves let it be, a fraction of a corner out at sea's emptiness, so their
 * mix would undercut the face from the sea floor up. Only the cells across a face reach that low; the caves under the
 * top of the cliff stay.
 */
public record CoastFunction(WorldScale scale, Mode mode) implements DensityFunction {
	public static final MapCodec<CoastFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		WorldScale.CODEC.fieldOf("scale").forGetter(CoastFunction::scale),
		Mode.CODEC.fieldOf("mode").forGetter(CoastFunction::mode)
	).apply(i, CoastFunction::new));

	/** Width of the cells the terrain is interpolated over. */
	private static final int CELL = 4;
	private static final ThreadLocal<Columns> COLUMNS = ThreadLocal.withInitial(Columns::new);

	public enum Mode implements StringRepresentable {
		/** Positive below the ground of a cliff column, negative above it; 1 elsewhere. */
		CARVE("carve"),
		/**
		 * Positive between the lowest corner of its cell and the exact ground of a cliff column, negative elsewhere in
		 * it; -1 elsewhere.
		 */
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
		return new Sampler(TerrainFields.forContext(context).surface(scale), mode == Mode.CARVE);
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
	public MapCodec<CoastFunction> codec() {
		return CODEC;
	}

	private record Sampler(TerrainSurface surface, boolean carve) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			float outside = carve ? 1f : -1f;
			for (int z = 0; z < volume.sizeZ(); z++) {
				int blockZ = volume.blockZ(z);
				for (int x = 0; x < volume.sizeX(); x++) {
					Columns columns = COLUMNS.get();
					int k = columns.index(surface, volume.blockX(x), blockZ);
					int index = volume.indexUnchecked(x, 0, z);
					if (Float.isNaN(columns.top[k])) {
						outputBuffer.setRange(index, volume.sizeY(), outside);
						continue;
					}
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(index + y, density(columns, k, volume.blockY(y)));
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			Columns columns = COLUMNS.get();
			int k = columns.index(surface, blockX, blockZ);
			return Float.isNaN(columns.top[k]) ? carve ? 1f : -1f : density(columns, k, blockY);
		}

		private float density(Columns columns, int k, int y) {
			float solid = columns.top[k] - y;
			float value = carve ? solid : Math.min(solid, y - columns.bottom[k]);
			return Math.clamp(value / 16f, -1f, 1f);
		}
	}

	/**
	 * The cliff columns of the chunk a thread last asked about, shared by {@code fill} and {@code carve}. The world's
	 * ground is held weakly, so that a thread does not keep the terrain of a world that has closed.
	 */
	private static final class Columns {
		/** The exact ground of each column, NaN away from cliffs. */
		final float[] top = new float[256];
		/** A block under the lowest corner of the cell of each cliff column. */
		final float[] bottom = new float[256];
		final boolean[] valid = new boolean[256];
		/** The ground at the corners of the chunk's cells, 5 by 5, NaN until read. */
		final float[] corners = new float[25];
		WeakReference<TerrainSurface> surface = new WeakReference<>(null);
		int chunkX;
		int chunkZ;

		/** The index of column {@code (x, z)} in the arrays, which it fills if need be. */
		int index(TerrainSurface surface, int x, int z) {
			int chunkX = x >> 4, chunkZ = z >> 4;
			if (this.surface.get() != surface || this.chunkX != chunkX || this.chunkZ != chunkZ) {
				this.surface = new WeakReference<>(surface);
				this.chunkX = chunkX;
				this.chunkZ = chunkZ;
				Arrays.fill(valid, false);
				Arrays.fill(corners, Float.NaN);
			}
			int k = (z & 15) << 4 | (x & 15);
			if (!valid[k]) {
				top[k] = surface.cliff(x, z);
				if (!Float.isNaN(top[k])) {
					int cx = (x & 15) / CELL, cz = (z & 15) / CELL;
					bottom[k] = Math.min(Math.min(corner(surface, cx, cz), corner(surface, cx + 1, cz)),
						Math.min(corner(surface, cx, cz + 1), corner(surface, cx + 1, cz + 1))) - 1f;
				}
				valid[k] = true;
			}
			return k;
		}

		private float corner(TerrainSurface surface, int cx, int cz) {
			int k = cz * 5 + cx;
			if (Float.isNaN(corners[k])) {
				corners[k] = surface.y((chunkX << 4) + cx * CELL, (chunkZ << 4) + cz * CELL);
			}
			return corners[k];
		}
	}
}
