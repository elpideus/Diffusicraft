package io.github.elpideus.diffusicraft.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The size of a Diffusicraft world: how many real-world meters one block covers, and how elevation in meters becomes
 * a block height. Terrain Diffusion produces elevation at 30 m per pixel. The sizes run from 1:1 down to 1:32,
 * halving each step, and shrink the world both across and up.
 *
 * <p>Diffusicraft is inspired by real terrain, not a simulation of it, so heights are exaggerated: the smaller the
 * world, the more, as vanilla's mountains are. Heights use a linear map up to a knee, then ease towards a ceiling, so
 * ordinary terrain keeps its shape and only the highest mountains (or deepest oceans) are compressed to fit:
 * <pre>
 *     blocks(e) = e / v                                for e / v &lt;= knee
 *     blocks(e) = knee + (cap - knee) tanh((e / v - knee) / (cap - knee))   above
 * </pre>
 * The underground and the oceans stay game-sized at every size. See docs/WORLDGEN.md for the numbers behind each one.
 */
public enum WorldScale implements StringRepresentable {
	/** 1:32, in vanilla's build height (y -64 to 319). Heights are tripled, so mountains reach vanilla's. */
	VANILLA("vanilla", 32, -64, 384, 32.0 / 3, 120.0, 240.0, 20.0, 20.0, 45.0),
	/** 1:16, from y -256 to 1279. */
	MEDIUM("medium", 16, -256, 1536, 6.4, 750.0, 1100.0, 16.0, 30.0, 80.0),
	/** 1:8, from y -256 to 2303. */
	LARGE("large", 8, -256, 2560, 3.5, 1600.0, 2150.0, 12.0, 35.0, 120.0),
	/**
	 * 1:4, from y -256 to 3839: the tallest world that Distant Horizons and Voxy hold whole. Heights are doubled up to
	 * 6 km.
	 */
	GRAND("grand", 4, -256, 4096, 2.0, 3000.0, 3700.0, 8.0, 40.0, 160.0),
	/** 1:2, from y -256 to 8191. */
	EXTREME("extreme", 2, -256, 8448, 1.15, 5500.0, 7800.0, 3.0, 50.0, 180.0),
	/**
	 * 1:1, the real Earth's size and the default, from y -256 to 16,367 (the top of the tallest dimension). Heights
	 * are half as tall again up to 6.5 km, so Everest stands at about y = 13,000.
	 */
	ULTRA("ultra", 1, -256, 16624, 0.65, 10000.0, 15800.0, 1.0, 60.0, 200.0);

	/** The size a new world starts with. */
	public static final WorldScale DEFAULT = ULTRA;
	/** Sea level at every size. */
	public static final int SEA_LEVEL = 63;
	public static final Codec<WorldScale> CODEC = StringRepresentable.fromEnum(WorldScale::values);
	/** Meters per native Terrain Diffusion pixel. */
	public static final double METERS_PER_PIXEL = 30.0;

	private final String id;
	private final int metersPerBlock;
	private final int minY;
	private final int height;
	private final double landMetersPerBlock;
	private final double landKnee;
	private final double landCap;
	private final double oceanMetersPerBlock;
	private final double oceanKnee;
	private final double oceanCap;

	WorldScale(String id, int metersPerBlock, int minY, int height, double landMetersPerBlock, double landKnee,
			   double landCap, double oceanMetersPerBlock, double oceanKnee, double oceanCap) {
		this.id = id;
		this.metersPerBlock = metersPerBlock;
		this.minY = minY;
		this.height = height;
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

	/** The second number of the size's ratio: 1 for 1:1, 32 for 1:32. */
	public int ratio() {
		return metersPerBlock;
	}

	/** Lowest block of the world. Must match the dimension type that tools/worldgen_data.py writes. */
	public int minY() {
		return minY;
	}

	/** Highest block of the world. */
	public int maxY() {
		return minY + height - 1;
	}

	/** Height of the world, in blocks. */
	public int height() {
		return height;
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
		return SEA_LEVEL;
	}

	/**
	 * Native pixel coordinate of a block coordinate, with pixel centres at integer + 0.5 (the block's centre is
	 * sampled).
	 */
	public double toPixel(int block) {
		return (block + 0.5) * pixelsPerBlock();
	}

	/**
	 * Height of the terrain surface, in blocks, for an elevation in meters. Water fills up to {@link #seaLevel()}. The
	 * sea floor lies at least a block under it, so the shallowest sea still holds water instead of a dry shelf flush
	 * with the water.
	 */
	public float surfaceY(float elevationMeters) {
		if (elevationMeters >= 0f) {
			return (float) (SEA_LEVEL + ease(elevationMeters / landMetersPerBlock, landKnee, landCap));
		}
		double depth = ease(-elevationMeters / oceanMetersPerBlock, oceanKnee, oceanCap);
		return (float) Math.min(SEA_LEVEL - 1, SEA_LEVEL - depth);
	}

	/**
	 * How fast {@link #surfaceY} rises with the elevation, in blocks per meter: zero where the shallowest sea floor is
	 * held a block under the sea.
	 */
	public double surfaceRate(float elevationMeters) {
		if (elevationMeters >= 0f) {
			return easeRate(elevationMeters / landMetersPerBlock, landKnee, landCap) / landMetersPerBlock;
		}
		double blocks = -elevationMeters / oceanMetersPerBlock;
		return ease(blocks, oceanKnee, oceanCap) < 1 ? 0 : easeRate(blocks, oceanKnee, oceanCap) / oceanMetersPerBlock;
	}

	/** Highest surface any elevation can reach. */
	public float maxSurfaceY() {
		return (float) (SEA_LEVEL + landCap);
	}

	/** Lowest ocean floor any elevation can reach. */
	public float minSurfaceY() {
		return (float) (SEA_LEVEL - oceanCap);
	}

	static double ease(double blocks, double knee, double cap) {
		if (blocks <= knee) {
			return blocks;
		}
		double room = cap - knee;
		return knee + room * Math.tanh((blocks - knee) / room);
	}

	/** The derivative of {@link #ease} with {@code blocks}. */
	static double easeRate(double blocks, double knee, double cap) {
		if (blocks <= knee) {
			return 1;
		}
		double tanh = Math.tanh((blocks - knee) / (cap - knee));
		return 1 - tanh * tanh;
	}
}
