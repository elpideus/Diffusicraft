package io.github.elpideus.diffusicraft.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How real-world meters map to blocks. Terrain Diffusion produces elevation at 30 m per pixel; a scale says how
 * many meters one block covers horizontally, and how elevation in meters becomes a block height.
 *
 * <p>Heights use a linear map up to a knee, then ease towards a ceiling, so ordinary terrain keeps its real
 * proportions and only the highest mountains (or deepest oceans) are compressed to fit the build height:
 * <pre>
 *     blocks(e) = e / v                                for e / v &lt;= knee
 *     blocks(e) = knee + (cap - knee) tanh((e / v - knee) / (cap - knee))   above
 * </pre>
 * See docs/WORLDGEN.md for the numbers behind each scale.
 */
public enum WorldScale implements StringRepresentable {
	/** About 15 m per block, in the vanilla build height. Mountains are tall but fit under y=320. */
	STANDARD("standard", 15.0, 63, 10.0, 100.0, 240.0, 20.0, 20.0, 45.0),
	/** 5 m per block, in a tall world (y -256 to 2031), close to real proportions. */
	GRAND("grand", 5.0, 63, 4.0, 1200.0, 1940.0, 8.0, 40.0, 160.0),
	/** 1 m per block: the real Earth size. Mountains above about 1.5 km are compressed until build height grows. */
	EARTH("earth", 1.0, 63, 1.0, 1200.0, 1940.0, 1.0, 60.0, 200.0);

	public static final Codec<WorldScale> CODEC = StringRepresentable.fromEnum(WorldScale::values);
	/** Meters per native Terrain Diffusion pixel. */
	public static final double METERS_PER_PIXEL = 30.0;

	private final String id;
	private final double metersPerBlock;
	private final int seaLevel;
	private final double landMetersPerBlock;
	private final double landKnee;
	private final double landCap;
	private final double oceanMetersPerBlock;
	private final double oceanKnee;
	private final double oceanCap;

	WorldScale(String id, double metersPerBlock, int seaLevel, double landMetersPerBlock, double landKnee, double landCap,
			   double oceanMetersPerBlock, double oceanKnee, double oceanCap) {
		this.id = id;
		this.metersPerBlock = metersPerBlock;
		this.seaLevel = seaLevel;
		this.landMetersPerBlock = landMetersPerBlock;
		this.landKnee = landKnee;
		this.landCap = landCap;
		this.oceanMetersPerBlock = oceanMetersPerBlock;
		this.oceanKnee = oceanKnee;
		this.oceanCap = oceanCap;
	}

	@Override
	public String getSerializedName() {
		return id;
	}

	/** Horizontal meters per block. */
	public double metersPerBlock() {
		return metersPerBlock;
	}

	/** Vertical meters per block on land, below the height where mountains are compressed. */
	public double verticalMetersPerBlock() {
		return landMetersPerBlock;
	}

	/** Native pixels per block, horizontally. */
	public double pixelsPerBlock() {
		return metersPerBlock / METERS_PER_PIXEL;
	}

	public int seaLevel() {
		return seaLevel;
	}

	/**
	 * Native pixel coordinate of a block coordinate, with pixel centres at integer + 0.5 (the block's centre is
	 * sampled).
	 */
	public double toPixel(int block) {
		return (block + 0.5) * pixelsPerBlock();
	}

	/** Height of the terrain surface, in blocks, for an elevation in meters. Water fills up to {@link #seaLevel()}. */
	public float surfaceY(float elevationMeters) {
		if (elevationMeters >= 0f) {
			return (float) (seaLevel + ease(elevationMeters / landMetersPerBlock, landKnee, landCap));
		}
		return (float) (seaLevel - ease(-elevationMeters / oceanMetersPerBlock, oceanKnee, oceanCap));
	}

	/** Highest surface any elevation can reach. */
	public float maxSurfaceY() {
		return (float) (seaLevel + landCap);
	}

	/** Lowest ocean floor any elevation can reach. */
	public float minSurfaceY() {
		return (float) (seaLevel - oceanCap);
	}

	static double ease(double blocks, double knee, double cap) {
		if (blocks <= knee) {
			return blocks;
		}
		double room = cap - knee;
		return knee + room * Math.tanh((blocks - knee) / room);
	}
}
