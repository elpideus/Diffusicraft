package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowsTest {
	private static final long SEED = 0x5EED_0F_CAFEL;
	private static final int SURFACE = 90;

	/** Open blocks of each kind over a large area, one column every 16 blocks, every other block in height. */
	private static Map<Hollows.Kind, Long> census(Hollows hollows, int minY, int fromY, int toY) {
		Map<Hollows.Kind, Long> open = new EnumMap<>(Hollows.Kind.class);
		Hollows.Column c = new Hollows.Column();
		for (int x = -4096; x < 4096; x += 16) {
			for (int z = -4096; z < 4096; z += 16) {
				hollows.column(x, z, (a, b) -> SURFACE, c);
				for (int y = fromY; y < toY; y += 2) {
					Hollows.Kind kind = hollows.openKind(c, y);
					if (kind != null) {
						open.merge(kind, 1L, Long::sum);
					}
				}
			}
		}
		return open;
	}

	@Test
	void everyTierAppearsAndTheGroundStaysMostlySolid() {
		for (int minY : new int[] {-64, -256}) {
			Hollows hollows = new Hollows(SEED, minY);
			Map<Hollows.Kind, Long> open = census(hollows, minY, minY, SURFACE);
			for (Hollows.Kind kind : Hollows.Kind.values()) {
				assertTrue(open.getOrDefault(kind, 0L) > 0, kind + " missing at min_y " + minY + ": " + open);
			}
			long samples = 512L * 512 * ((SURFACE - minY) / 2);
			double share = open.values().stream().mapToLong(Long::longValue).sum() / (double) samples;
			assertTrue(share > 0.01 && share < 0.12, "open share " + share + " at min_y " + minY);
		}
	}

	@Test
	void hollowsKeepTheirRoofAndSpareTheBedrock() {
		int minY = -64;
		Hollows hollows = new Hollows(SEED, minY);
		Hollows.Column c = new Hollows.Column();
		for (int x = -3000; x < 3000; x += 7) {
			for (int z = -3000; z < 3000; z += 61) {
				// Low surfaces too, as under the sea, where the roof squeezes the shallow hollows.
				int surface = 10 + Math.floorMod(x * 31 + z * 17, 120);
				hollows.column(x, z, (a, b) -> surface, c);
				for (int y = minY; y < surface; y++) {
					if (hollows.density(c, y) < 0) {
						assertTrue(y < surface - 28, "open at " + y + " under a surface at " + surface);
						assertTrue(y > minY + 6, "open at " + y + " just above the bedrock");
					}
				}
			}
		}
	}

	@Test
	void deepGrandHollowsHoldLavaSeas() {
		for (int minY : new int[] {-64, -256}) {
			Hollows hollows = new Hollows(SEED, minY);
			Map<Hollows.Kind, Long> belowLava = census(hollows, minY, minY, minY + 10);
			assertTrue(belowLava.getOrDefault(Hollows.Kind.DEEP_GRAND_HOLLOW, 0L) > 0,
				"no deep grand hollow reaches under the lava level at min_y " + minY + ": " + belowLava);
		}
	}

	@Test
	void deepGrandHollowFloorsLieWellUnderTheLava() {
		// Under most of a deep grand hollow open just above the lava level, the floor lies at least two blocks lower.
		// Less, and the world's interpolated density leaves the floor high and dry.
		int minY = -64;
		int lava = minY + 10;
		Hollows hollows = new Hollows(SEED, minY);
		Hollows.Column c = new Hollows.Column();
		int open = 0;
		int deep = 0;
		for (int x = -4096; x < 4096; x += 16) {
			for (int z = -4096; z < 4096; z += 16) {
				hollows.column(x, z, (a, b) -> SURFACE, c);
				if (hollows.openKind(c, lava) != Hollows.Kind.DEEP_GRAND_HOLLOW) {
					continue;
				}
				open++;
				if (hollows.openKind(c, lava - 3) != null) {
					deep++;
				}
			}
		}
		assertTrue(open > 0);
		assertTrue(deep > open * 0.6, deep + " of " + open + " lava sea columns are deep");
	}

	@Test
	void grandHollowsAreTallInTallWorlds() {
		Hollows hollows = new Hollows(SEED, -256);
		Hollows.Column c = new Hollows.Column();
		int tallest = 0;
		for (int x = -4096; x < 4096 && tallest < 80; x += 32) {
			for (int z = -4096; z < 4096 && tallest < 80; z += 32) {
				hollows.column(x, z, (a, b) -> SURFACE, c);
				int run = 0;
				for (int y = -250; y < 0; y++) {
					Hollows.Kind kind = hollows.openKind(c, y);
					run = kind == Hollows.Kind.GRAND_HOLLOW || kind == Hollows.Kind.ANCIENT_GRAND_HOLLOW ? run + 1 : 0;
					tallest = Math.max(tallest, run);
				}
			}
		}
		assertTrue(tallest >= 80, "the tallest grand hollow is " + tallest + " blocks high");
	}

	@Test
	void grandHollowsIncludeTheirPillarsAndWhatCutsIntoThem() {
		// A block with a Grand Hollow open six blocks away on all four sides is inside it: in a pillar, in a smaller
		// cave that cuts through it, or in a bump of its warped floor, which is why it may lie a little outside.
		Hollows hollows = new Hollows(SEED, -64);
		Hollows.Column c = new Hollows.Column();
		int[][] offsets = {{6, 0}, {-6, 0}, {0, 6}, {0, -6}};
		Hollows.Column[] sides = new Hollows.Column[offsets.length];
		for (int i = 0; i < sides.length; i++) {
			sides[i] = new Hollows.Column();
		}
		int enclosed = 0;
		int hidden = 0;
		for (int x = -4096; x < 4096; x += 13) {
			for (int z = -4096; z < 4096; z += 29) {
				hollows.column(x, z, (a, b) -> SURFACE, c);
				if (c.solid()) {
					continue;
				}
				for (int i = 0; i < sides.length; i++) {
					hollows.column(x + offsets[i][0], z + offsets[i][1], (a, b) -> SURFACE, sides[i]);
				}
				for (int y = -50; y < 30; y += 4) {
					Hollows.Kind kind = hollows.openKind(c, y);
					if (kind != null && kind.grand()) {
						continue;
					}
					boolean surrounded = true;
					for (Hollows.Column side : sides) {
						Hollows.Kind around = hollows.openKind(side, y);
						surrounded &= around != null && around.grand();
					}
					if (surrounded) {
						enclosed++;
						if (!hollows.nearGrandHollow(c, y)) {
							hidden++;
						}
					}
				}
			}
		}
		assertTrue(enclosed > 20, "only " + enclosed + " pillar or cave blocks found");
		assertEquals(0, hidden, hidden + " of " + enclosed + " blocks inside Grand Hollows are not in them");
	}

	@Test
	void theRockUnderGrandHollowFloorsAndOverTheirCeilingsCountsAsPartOfThem() {
		// The game looks biomes up as much as a quart off, and sculk spreads from wherever the deep dark starts it, so
		// the deep dark must stay well clear of the floors and the ceilings. The rest of the deep rock keeps it.
		for (int minY : new int[] {-64, -256}) {
			Hollows hollows = new Hollows(SEED, minY);
			Hollows.Column c = new Hollows.Column();
			int edges = 0;
			long near = 0;
			long samples = 0;
			for (int x = -4096; x < 4096; x += 17) {
				for (int z = -4096; z < 4096; z += 31) {
					hollows.column(x, z, (a, b) -> SURFACE, c);
					for (int y = minY; y < SURFACE - 28; y += 4) {
						samples++;
						near += hollows.nearGrandHollow(c, y) ? 1 : 0;
					}
					if (c.solid()) {
						continue;
					}
					for (int y = minY + 1; y < SURFACE - 28; y++) {
						Hollows.Kind kind = hollows.openKind(c, y);
						if (kind == null || !kind.grand()) {
							continue;
						}
						// The floors of the Deep Grand Hollows lie under their lava seas.
						boolean floor = kind != Hollows.Kind.DEEP_GRAND_HOLLOW && hollows.openKind(c, y - 1) == null;
						boolean ceiling = hollows.openKind(c, y + 1) == null;
						for (int d = 1; d <= 10; d++) {
							int under = y - d;
							int over = y + d;
							assertTrue(!floor || hollows.nearGrandHollow(c, under),
								"deep dark " + d + " blocks under a floor at " + x + ", " + y + ", " + z);
							assertTrue(!ceiling || hollows.nearGrandHollow(c, over),
								"deep dark " + d + " blocks over a ceiling at " + x + ", " + y + ", " + z);
						}
						edges += floor || ceiling ? 1 : 0;
					}
				}
			}
			assertTrue(edges > 1000, "only " + edges + " floors and ceilings found at min_y " + minY);
			double share = near / (double) samples;
			assertTrue(share < 0.12, share + " of the ground counts as near a Grand Hollow at min_y " + minY);
		}
	}

	@Test
	void sameSeedSameHollows() {
		Hollows a = new Hollows(SEED, -64);
		Hollows b = new Hollows(SEED, -64);
		Hollows other = new Hollows(SEED + 1, -64);
		Hollows.Column ca = new Hollows.Column();
		Hollows.Column cb = new Hollows.Column();
		Hollows.Column co = new Hollows.Column();
		int differences = 0;
		for (int x = -2000; x < 2000; x += 37) {
			for (int z = -2000; z < 2000; z += 41) {
				a.column(x, z, (p, q) -> SURFACE, ca);
				b.column(x, z, (p, q) -> SURFACE, cb);
				other.column(x, z, (p, q) -> SURFACE, co);
				for (int y = -60; y < 60; y += 3) {
					float density = a.density(ca, y);
					assertEquals(density, b.density(cb, y));
					if ((density < 0) != (other.density(co, y) < 0)) {
						differences++;
					}
				}
			}
		}
		assertTrue(differences > 0, "another seed gives other hollows");
	}

	@Test
	void sampleRateIsFastEnoughForChunkGeneration() {
		Hollows hollows = new Hollows(SEED, -256);
		Hollows.Column c = new Hollows.Column();
		double sink = 0;
		long start = System.nanoTime();
		int samples = 0;
		// The world samples the density at the corners of 4 x 8 x 4 cells.
		for (int x = 0; x < 2048; x += 4) {
			for (int z = 0; z < 256; z += 4) {
				hollows.column(x, z, (a, b) -> SURFACE, c);
				for (int y = -256; y < SURFACE; y += 8) {
					sink += hollows.density(c, y);
					samples++;
				}
			}
		}
		double nsPerSample = (System.nanoTime() - start) / (double) samples;
		// Generous: well under a microsecond once warm, columns included.
		assertTrue(nsPerSample < 3000, nsPerSample + " ns per sample");
		assertTrue(sink != 0);
	}
}
