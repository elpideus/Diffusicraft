package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeologyTest {
	private static final long SEED = 0x1234_5678_9ABCL;
	private static final Set<Rock> MAGMA_LAYER = EnumSet.of(Rock.MAGMA, Rock.BLACKSTONE, Rock.BASALT);
	private static final Set<Rock> SEDIMENTS = EnumSet.of(Rock.SANDSTONE, Rock.RED_SANDSTONE, Rock.SHALE,
		Rock.COAL_SEAM, Rock.LIMESTONE, Rock.CHALK, Rock.ROCK_SALT, Rock.SLATE, Rock.MARBLE, Rock.SCHIST);

	/** Gentle lowland: the kind of column that keeps a thick sedimentary cover. */
	private static Geology.Column lowland(Geology geology, int x, int z) {
		Geology.Column c = new Geology.Column();
		c.surfaceY = 80;
		c.meanSurfaceY = 78;
		c.meanElevation = 150;
		c.relief = 40;
		c.temperature = 11;
		c.precipitation = 800;
		geology.column(x, z, c);
		return c;
	}

	@Test
	void layersComeInEarthOrderFromTheBottomUp() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		for (int x = -2000; x < 2000; x += 97) {
			for (int z = -2000; z < 2000; z += 89) {
				Geology.Column c = lowland(geology, x, z);
				assertTrue(c.magmaTop() > -64 + 4, "magma layer above the bedrock");
				assertTrue(c.magmaTop() < c.deepslateTop(), "magma below deepslate");
				for (int y = -60; y < 80; y++) {
					Rock rock = geology.rock(c, x, y, z);
					if (y < c.magmaTop()) {
						assertTrue(MAGMA_LAYER.contains(rock), rock + " in the magma layer at " + y);
					} else {
						assertFalse(MAGMA_LAYER.contains(rock), rock + " above the magma layer at " + y);
					}
					if (y >= c.deepslateTop() && rock != Rock.KIMBERLITE) {
						assertFalse(rock.deep(), rock + " above the deepslate at " + y);
					}
					if (y >= c.magmaTop() && y < c.deepslateTop()) {
						assertTrue(rock.deep(), rock + " in the lower crust at " + y);
					}
				}
			}
		}
	}

	@Test
	void lowlandsAreCoveredInSediment() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		int sediment = 0;
		int total = 0;
		for (int x = -3000; x < 3000; x += 131) {
			for (int z = -3000; z < 3000; z += 127) {
				Geology.Column c = lowland(geology, x, z);
				for (int y = 70; y < 80; y++) {
					total++;
					if (SEDIMENTS.contains(geology.rock(c, x, y, z))) {
						sediment++;
					}
				}
			}
		}
		assertTrue(sediment > total / 2, sediment + " of " + total + " blocks below lowland surfaces are sediment");
	}

	@Test
	void belowTheSeaEveryScaleKeepsTheStandardProportions() {
		Geology standard = new Geology(SEED, -64, 15, 10);
		Geology earth = new Geology(SEED, -256, 1, 1);
		int columns = 0;
		int standardBasement = 0;
		int earthBasement = 0;
		for (int x = -6000; x < 6000; x += 211) {
			for (int z = -6000; z < 6000; z += 199) {
				Geology.Column s = lowland(standard, x, z);
				Geology.Column e = lowland(earth, x, z);
				// At most 2.1 km of cover, which is 210 blocks at 10 m per block, not the 2100 of Earth 1:1's scale.
				assertTrue(e.sedimentBase() >= 63 - 210, "cover down to y = " + e.sedimentBase());
				columns++;
				if (s.sedimentBase() > s.deepslateTop()) {
					standardBasement++;
				}
				if (e.sedimentBase() > e.deepslateTop()) {
					earthBasement++;
				}
			}
		}
		assertTrue(standardBasement > columns / 10, "basement under " + standardBasement + " of " + columns);
		assertTrue(earthBasement > standardBasement / 2,
			"basement under " + earthBasement + " Earth 1:1 lowlands, " + standardBasement + " standard ones");
	}

	@Test
	void aboveTheSeaThicknessesFollowTheVerticalScale() {
		Geology oneMeter = new Geology(SEED, -256, 1, 1);
		Geology tenMeters = new Geology(SEED, -256, 1, 10);
		int compared = 0;
		for (int x = -3000; x < 3000; x += 173) {
			Geology.Column a = plateau(oneMeter, x);
			Geology.Column b = plateau(tenMeters, x);
			double coverA = a.meanSurfaceY - a.sedimentBase();
			double coverB = b.meanSurfaceY - b.sedimentBase();
			if (coverA > 10) {
				assertEquals(coverA, 10 * coverB, 0.01 * coverA, "cover at x = " + x);
				compared++;
			}
		}
		assertTrue(compared > 5, compared + " plateau columns with a cover");
	}

	/** High, gentle land: its cover ends far above the sea at any scale. */
	private static Geology.Column plateau(Geology geology, int x) {
		Geology.Column c = new Geology.Column();
		c.surfaceY = 1465;
		c.meanSurfaceY = 1463;
		c.meanElevation = 1400;
		c.relief = 40;
		c.temperature = 4;
		c.precipitation = 600;
		geology.column(x, 0, c);
		return c;
	}

	@Test
	void sameSeedSameGround() {
		Geology a = new Geology(SEED, -256, 5, 4);
		Geology b = new Geology(SEED, -256, 5, 4);
		Geology other = new Geology(SEED + 1, -256, 5, 4);
		int differences = 0;
		for (int x = -500; x < 500; x += 37) {
			for (int z = -500; z < 500; z += 41) {
				Geology.Column ca = lowland(a, x, z);
				Geology.Column cb = lowland(b, x, z);
				Geology.Column co = lowland(other, x, z);
				for (int y = -250; y < 80; y += 3) {
					Rock ra = a.rock(ca, x, y, z);
					assertEquals(ra, b.rock(cb, x, y, z));
					assertEquals(a.ore(ca, x, y, z, ra), b.ore(cb, x, y, z, ra));
					if (ra != other.rock(co, x, y, z)) {
						differences++;
					}
				}
			}
		}
		assertTrue(differences > 0, "another seed gives other ground");
	}

	@Test
	void oresAreRareAndDiamondsDeep() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		Map<Ore, Integer> counts = new EnumMap<>(Ore.class);
		long blocks = 0;
		for (int x = 0; x < 256; x++) {
			for (int z = 0; z < 256; z += 3) {
				Geology.Column c = lowland(geology, x, z);
				for (int y = -60; y < 78; y++) {
					blocks++;
					Rock rock = geology.rock(c, x, y, z);
					Ore ore = geology.ore(c, x, y, z, rock);
					if (ore == null) {
						continue;
					}
					counts.merge(ore, 1, Integer::sum);
					if (ore == Ore.DIAMOND && rock != Rock.KIMBERLITE) {
						assertTrue(y < c.deepslateTop() + 12, "diamond at " + y);
					}
				}
			}
		}
		long ores = counts.values().stream().mapToLong(Integer::longValue).sum();
		double share = ores / (double) blocks;
		// The bodies are rich but scattered: a few hundredths of a percent of the ground, on top of vanilla's own ores.
		assertTrue(share > 0.0002 && share < 0.005, "ore share " + share + " " + counts);
		assertTrue(counts.getOrDefault(Ore.IRON, 0) > counts.getOrDefault(Ore.DIAMOND, 0), counts.toString());
	}

	@Test
	void sampleRateIsFastEnoughForChunkGeneration() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		Geology.Column c = new Geology.Column();
		long sink = 0;
		long start = System.nanoTime();
		int blocks = 0;
		for (int chunk = 0; chunk < 40; chunk++) {
			for (int x = chunk * 16; x < chunk * 16 + 16; x++) {
				for (int z = 0; z < 16; z++) {
					c.surfaceY = 80;
					c.meanSurfaceY = 78;
					c.meanElevation = 150;
					c.relief = 40;
					c.temperature = 11;
					c.precipitation = 800;
					geology.column(x, z, c);
					for (int y = 79; y >= -60; y--) {
						Rock rock = geology.rock(c, x, y, z);
						Ore ore = geology.ore(c, x, y, z, rock);
						sink += rock.ordinal() + (ore == null ? 0 : ore.ordinal());
						blocks++;
					}
				}
			}
		}
		double nsPerBlock = (System.nanoTime() - start) / (double) blocks;
		// Generous: on any machine this should be well under a microsecond once warm. The first chunks include JIT.
		assertTrue(nsPerBlock < 2000, nsPerBlock + " ns per block");
		assertTrue(sink > 0);
	}
}
