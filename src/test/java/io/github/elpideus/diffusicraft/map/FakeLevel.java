package io.github.elpideus.diffusicraft.map;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** A level for the map's tests: green fine tiles, blue coarse ones, the ground at 100 + x + z, counting the renders. */
final class FakeLevel implements MapLevel {
	static final int FINE_RGB = 0x00ff00;
	static final int COARSE_RGB = 0x0000ff;
	static final int BORDER = 1 << 20;

	final AtomicInteger fineRenders = new AtomicInteger();
	final AtomicInteger coarseRenders = new AtomicInteger();

	@Override
	public String id() {
		return "test:flat";
	}

	@Override
	public int fineZoom() {
		return 2;
	}

	@Override
	public int maxZoom() {
		return 5;
	}

	@Override
	public int minY() {
		return -64;
	}

	@Override
	public int maxY() {
		return 320;
	}

	@Override
	public int seaLevel() {
		return 63;
	}

	@Override
	public double metersPerBlock() {
		return 1;
	}

	@Override
	public int border() {
		return BORDER;
	}

	@Override
	public List<String> biomes() {
		return List.of("test:plains", "test:ocean");
	}

	@Override
	public List<String> biomeNames() {
		return List.of("Plains", "Ocean");
	}

	@Override
	public MapRaster render(int zoom, int x, int y, boolean coarse) {
		(coarse ? coarseRenders : fineRenders).incrementAndGet();
		MapRaster tile = new MapRaster();
		for (int k = 0; k < MapRaster.PIXELS; k++) {
			tile.ground[k] = (short) (100 + k % MapRaster.SIZE + k / MapRaster.SIZE);
			tile.rgb[k] = coarse ? COARSE_RGB : FINE_RGB;
		}
		tile.flags = coarse ? 0 : MapRaster.DETAIL;
		return tile;
	}

	@Override
	public @Nullable Point point(int x, int z) {
		return Math.abs(x) > BORDER || Math.abs(z) > BORDER ? null : new Point(x, z, 70, 3, 1);
	}

	@Override
	public List<Point> search(int biome, int x, int z, long deadline) {
		return List.of(new Point(x + 100, z - 50, 64, 0, biome));
	}
}
