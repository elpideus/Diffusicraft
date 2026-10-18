package io.github.elpideus.diffusicraft.height;

/**
 * Build heights beyond vanilla's 4064 blocks, paid for with horizontal range.
 *
 * <p>Minecraft packs a block position into a {@code long}: 26 bits each for X and Z, 12 for Y. Every height limit
 * follows from those 12 bits ({@code DimensionType.Y_SIZE = 2^12 - 32}). Diffusicraft packs 24 bits each for X and Z
 * and 16 for Y in memory, and caps dimension types at 15 bits. That allows heights up to 32,736 blocks (Y from
 * −16,368 to 16,367) in worlds ±8,000,000 blocks wide (vanilla: ±30,000,000).
 *
 * <p>On the wire, positions keep vanilla's layout whenever Y fits in 12 bits, so vanilla clients still work in
 * vanilla-height dimensions. Taller positions use a layout that vanilla never produces for |X| < 2^24 (bits 25 and 24
 * of the X field differ); see {@link #toWire}.
 *
 * <p>Pure Java, so it can be tested without the game. The mixins in {@code io.github.elpideus.diffusicraft.mixin}
 * apply these numbers; see docs/HEIGHT.md.
 */
public final class ExtendedHeight {
	/** Bits for X and for Z in a packed block position (vanilla: 26). */
	public static final int HORIZONTAL_BITS = 24;
	/** The world size {@code BlockPos} derives {@link #HORIZONTAL_BITS} from (vanilla: 30,000,000). */
	public static final int PACKING_SIZE = 1 << (HORIZONTAL_BITS - 1);
	/** Bits for Y in dimension types and on the wire: heights up to 32,736 blocks (vanilla: 12, 4064 blocks). */
	public static final int DIMENSION_Y_BITS = 15;
	/** Blocks can be placed where |X| and |Z| are below this (vanilla: 30,000,000). */
	public static final int WORLD_LIMIT = 8_000_000;
	/** Largest world border reach from the origin (vanilla: 29,999,984). */
	public static final int BORDER_LIMIT = WORLD_LIMIT - 16;
	/** Largest block data of one chunk in a chunk packet (vanilla: 2 MiB). A full 32k-tall column needs more. */
	public static final int CHUNK_PACKET_LIMIT = 8 << 20;

	private static final int VANILLA_HORIZONTAL_BITS = 26;
	private static final int VANILLA_Y_BITS = 64 - 2 * VANILLA_HORIZONTAL_BITS;
	private static final long FIELD_MASK = (1L << VANILLA_HORIZONTAL_BITS) - 1;
	private static final long HORIZONTAL_MASK = (1L << HORIZONTAL_BITS) - 1;
	private static final int VANILLA_Y_MIN = -(1 << (VANILLA_Y_BITS - 1));
	private static final int VANILLA_Y_MAX = (1 << (VANILLA_Y_BITS - 1)) - 1;

	private ExtendedHeight() { }

	/** {@link #WORLD_LIMIT} with the sign of a vanilla ±30,000,000 bound. */
	public static int worldLimit(int vanillaBound) {
		return vanillaBound < 0 ? -WORLD_LIMIT : WORLD_LIMIT;
	}

	/** {@link #WORLD_LIMIT} with the sign of a vanilla ±3.0E7 clamp. */
	public static double worldLimit(double vanillaBound) {
		return vanillaBound < 0 ? -WORLD_LIMIT : WORLD_LIMIT;
	}

	/**
	 * Encodes a position for the network.
	 *
	 * <ul>
	 *   <li>Y in [−2048, 2047]: exactly vanilla's {@code BlockPos.asLong}, X and Z in 26 bits.</li>
	 *   <li>Otherwise: X and Z in the low 24 bits of their fields, Y in 15 bits. Y bits 0–11 go in the Y field,
	 *   bits 12–13 in the top of the Z field, and bit 14 in bit 25 of the X field, with bit 24 set to its
	 *   complement. Vanilla longs with |X| < 2^24 always have bits 25 and 24 equal, so the two never collide.</li>
	 * </ul>
	 *
	 * X and Z must fit in 24 bits and Y in 15 for the tall layout; the world limits guarantee it.
	 */
	public static long toWire(int x, int y, int z) {
		if (y >= VANILLA_Y_MIN && y <= VANILLA_Y_MAX) {
			return pack(x & FIELD_MASK, y & 0xFFF, z & FIELD_MASK);
		}
		int wide = y & 0x7FFF;
		long marker = (wide >>> 14) == 1 ? 1L << 25 : 1L << 24;
		long xField = (x & HORIZONTAL_MASK) | marker;
		long zField = (z & HORIZONTAL_MASK) | (long) (wide >>> 12 & 3) << 24;
		return pack(xField, wide & 0xFFF, zField);
	}

	public static int wireX(long wire) {
		return isTall(wire) ? signExtend(xField(wire), HORIZONTAL_BITS) : (int) (wire >> 38);
	}

	public static int wireY(long wire) {
		if (!isTall(wire)) {
			return (int) (wire << 52 >> 52);
		}
		long wide = (xField(wire) >>> 25 & 1) << 14 | (zField(wire) >>> 24 & 3) << 12 | (wire & 0xFFF);
		return signExtend(wide, DIMENSION_Y_BITS);
	}

	public static int wireZ(long wire) {
		return isTall(wire) ? signExtend(zField(wire), HORIZONTAL_BITS) : (int) (wire << 26 >> 38);
	}

	/** Whether a wire position uses the tall layout. */
	static boolean isTall(long wire) {
		long xField = xField(wire);
		return (xField >>> 25 & 1) != (xField >>> 24 & 1);
	}

	private static long pack(long xField, long yField, long zField) {
		return xField << 38 | zField << 12 | yField;
	}

	private static long xField(long wire) {
		return wire >>> 38;
	}

	private static long zField(long wire) {
		return wire >>> 12 & FIELD_MASK;
	}

	private static int signExtend(long value, int bits) {
		int shift = 64 - bits;
		return (int) (value << shift >> shift);
	}
}
