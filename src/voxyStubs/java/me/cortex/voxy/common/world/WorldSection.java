package me.cortex.voxy.common.world;

/**
 * Diffusicraft's declaration of the members of Voxy's {@code WorldSection} it uses, to compile against: a cube of
 * 32 × 32 × 32 voxels, each {@code 2^lvl} blocks on a side. Not Voxy's code: Voxy is All Rights Reserved, and at
 * runtime its own class stands in for this one. Never shipped.
 */
public final class WorldSection {
	/** Level of detail, and position in sections of that level. */
	public final int lvl;
	public final int x;
	public final int y;
	public final int z;

	private WorldSection() {
		lvl = x = y = z = 0;
	}

	/** Index of voxel {@code (x, y, z)} in {@link #_unsafeGetRawDataArray}. */
	public static int getIndex(int x, int y, int z) {
		throw new AssertionError();
	}

	/** Bit of the child at {@code (x, y, z)}, each 0 or 1, in {@link #getNonEmptyChildren}. */
	public static int getChildIndex(int x, int y, int z) {
		throw new AssertionError();
	}

	/** The voxels, as the ids of Voxy's {@code Mapper}. */
	public long[] _unsafeGetRawDataArray() {
		throw new AssertionError();
	}

	/** The children that hold blocks, one bit each; at level 0, all of them if any voxel is not air. */
	public byte getNonEmptyChildren() {
		throw new AssertionError();
	}

	public void _unsafeSetNonEmptyChildren(byte nonEmptyChildren) {
		throw new AssertionError();
	}

	/**
	 * Sets the bit of {@code child} from whether it holds blocks. Returns 0 if nothing changed, 2 if this section went
	 * from holding blocks to not or back, and 1 otherwise.
	 */
	public int updateEmptyChildState(WorldSection child) {
		throw new AssertionError();
	}

	/** Adds to the count of voxels that are not air, at level 0. */
	public int addNonEmptyBlockCount(int delta) {
		throw new AssertionError();
	}

	/** Sets the children from the count of voxels that are not air, at level 0. */
	public boolean updateLvl0State() {
		throw new AssertionError();
	}

	public int release() {
		throw new AssertionError();
	}
}
