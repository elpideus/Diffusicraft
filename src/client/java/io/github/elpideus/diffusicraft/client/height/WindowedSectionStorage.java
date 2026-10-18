package io.github.elpideus.diffusicraft.client.height;

import io.github.elpideus.diffusicraft.height.RenderWindow;
import io.github.elpideus.diffusicraft.runtime.Resources;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * A {@link RotatingSectionStorage} that holds the rows of a {@link RenderWindow} around the camera instead of every row
 * of the level. Sections outside the window read as absent, like sections beyond the render distance horizontally.
 *
 * <p>{@link #minY()}, {@link #maxY()} and {@link #height()} still describe the level: the occlusion graph uses them to
 * find the level's edge when the camera is above or below it, and the octree to size itself.
 */
public final class WindowedSectionStorage<T extends RotatingSectionStorage.Value> extends RotatingSectionStorage<T> {
	/**
	 * {@code verticalRenderDistance}, read once: the two grids must always get the same window, so a change in the
	 * middle of a session must not reach one of them before the other.
	 */
	private static final int VERTICAL_DISTANCE = Resources.get().config().verticalRenderDistance;

	private final RenderWindow window;
	private final int radius;
	private final int sizeXZ;
	private final T[] values;
	private SectionPos center = SectionPos.of(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
	private int lowest;

	/** The window for a level at a render distance; null when it would hold the whole level anyway. */
	public static @Nullable RenderWindow windowFor(int minSectionY, int maxSectionY, int renderDistance) {
		RenderWindow window = RenderWindow.of(minSectionY, maxSectionY, renderDistance, VERTICAL_DISTANCE);
		return window.coversLevel() ? null : window;
	}

	@SuppressWarnings("unchecked")
	public WindowedSectionStorage(int radius, RenderWindow window, ValueCreator<T> valueCreator) {
		// The parent creates the values, with the indices the rest of the game expects, for the window's rows only.
		super(radius, window.minSectionY(), window.minSectionY() + window.rows() - 1, valueCreator);
		this.window = window;
		this.radius = radius;
		this.sizeXZ = radius * 2 + 1;
		this.values = (T[]) new RotatingSectionStorage.Value[super.size()];
		int[] next = {0};
		super.forEach(value -> values[next[0]++] = value);
		this.lowest = window.minSectionY();
	}

	@Override
	public boolean repositionCenter(SectionPos newCenter) {
		if (newCenter.equals(center)) {
			return false;
		}
		int lowestX = newCenter.x() - radius;
		int lowestZ = newCenter.z() - radius;
		int newLowest = window.lowest(newCenter.y());
		for (int gridX = 0; gridX < sizeXZ; gridX++) {
			int sectionX = lowestX + Math.floorMod(gridX - lowestX, sizeXZ);
			for (int gridZ = 0; gridZ < sizeXZ; gridZ++) {
				int sectionZ = lowestZ + Math.floorMod(gridZ - lowestZ, sizeXZ);
				for (int slot = 0; slot < window.rows(); slot++) {
					long node = SectionPos.asLong(sectionX, window.sectionY(slot, newLowest), sectionZ);
					T value = values[index(gridX, slot, gridZ)];
					if (value.getSectionNode() != node) {
						value.setSectionNode(node);
					}
				}
			}
		}
		center = newCenter;
		lowest = newLowest;
		return true;
	}

	@Override
	public @Nullable T getValue(int sectionX, int sectionY, int sectionZ) {
		if (sectionY < lowest || sectionY >= lowest + window.rows()
			|| Math.abs((long) sectionX - center.x()) > radius || Math.abs((long) sectionZ - center.z()) > radius) {
			return null;
		}
		return values[index(Math.floorMod(sectionX, sizeXZ), window.slot(sectionY), Math.floorMod(sectionZ, sizeXZ))];
	}

	@Override
	public int radius() {
		return radius;
	}

	@Override
	public int minY() {
		return window.minSectionY();
	}

	@Override
	public int maxY() {
		return window.maxSectionY();
	}

	@Override
	public int height() {
		return window.maxSectionY() - window.minSectionY() + 1;
	}

	@Override
	public SectionPos centerSectionPos() {
		return center;
	}

	@Override
	public int size() {
		return values.length;
	}

	@Override
	public Iterator<T> iterator() {
		return Arrays.asList(values).iterator();
	}

	@Override
	public void forEach(Consumer<? super T> action) {
		for (T value : values) {
			action.accept(value);
		}
	}

	@Override
	public Spliterator<T> spliterator() {
		return Spliterators.spliterator(iterator(), values.length, 0);
	}

	/** The same layout as the parent's, with window slots in place of section rows. */
	private int index(int gridX, int slot, int gridZ) {
		return (gridZ * window.rows() + slot) * sizeXZ + gridX;
	}
}
