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
	void theBasementIsHarderThanItsCover() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		Map<Geology.Basement, double[]> byBasement = new EnumMap<>(Geology.Basement.class);
		double basement = 0, cover = 0;
		int count = 0;
		for (int x = -6000; x < 6000; x += 173) {
			for (int z = -6000; z < 6000; z += 167) {
				Geology.Column c = lowland(geology, x, z);
				double below = geology.hardness(x, z, c.sedimentBase - 20, c.meanSurfaceY, c.meanElevation, c.relief);
				double above = geology.hardness(x, z, c.sedimentBase + 20, c.meanSurfaceY, c.meanElevation, c.relief);
				assertTrue(below >= 0 && below <= 1 && above >= 0 && above <= 1, below + ", " + above);
				assertTrue(below >= above, "the basement at " + x + ", " + z + " is softer than its cover");
				basement += below;
				cover += above;
				count++;
				double[] sum = byBasement.computeIfAbsent(c.basement, b -> new double[2]);
				sum[0] += below;
				sum[1]++;
			}
		}
		System.out.printf("Hardness: basement %.2f, flat cover %.2f%n", basement / count, cover / count);
		assertTrue(basement / count > cover / count + 0.3, "the basement is much harder than flat beds");
		double granite = byBasement.get(Geology.Basement.GRANITE)[0] / byBasement.get(Geology.Basement.GRANITE)[1];
		double schist = byBasement.get(Geology.Basement.SCHIST)[0] / byBasement.get(Geology.Basement.SCHIST)[1];
		assertTrue(granite > schist + 0.3, "granite " + granite + " is harder than schist " + schist);
	}

	@Test
	void hardnessChangesSmoothly() {
		Geology geology = new Geology(SEED, -256, 1, 1);
		Geology.Column c = new Geology.Column();
		for (int x = -20000; x < 20000; x += 1009) {
			for (int z = -20000; z < 20000; z += 997) {
				c.surfaceY = c.meanSurfaceY = 1200;
				c.meanElevation = 1140;
				c.relief = 220;
				geology.column(x, z, c);
				// The contact with the basement spreads over 8 blocks, and the kinds of basement merge into each other.
				// In fold belts the contact can be steep, so the hardness may change quickly along X, but never jump.
				for (double y = c.sedimentBase - 8; y <= c.sedimentBase + 8; y += 0.25) {
					assertEquals(geology.hardness(x, z, y, c.meanSurfaceY, c.meanElevation, c.relief),
						geology.hardness(x, z, y + 0.01, c.meanSurfaceY, c.meanElevation, c.relief), 0.003,
						"across the contact at " + x + ", " + z);
				}
				for (double t = 0; t < 64; t += 0.25) {
					double y = c.sedimentBase + 2;
					assertEquals(geology.hardness(x + t, z, y, c.meanSurfaceY, c.meanElevation, c.relief),
						geology.hardness(x + t + 0.01, z, y, c.meanSurfaceY, c.meanElevation, c.relief), 0.01,
						"along X at " + (x + t) + ", " + z);
				}
			}
		}
	}

	private static final Set<Rock> HARD_BEDS = EnumSet.of(Rock.SANDSTONE, Rock.RED_SANDSTONE, Rock.LIMESTONE);
	private static final Set<Rock> SOFT_BEDS = EnumSet.of(Rock.SHALE, Rock.CHALK, Rock.ROCK_SALT, Rock.COAL_SEAM);

	@Test
	void hardBedsStandOutOfSlopesAsLedges() {
		// Slopes down through the cover of lowlands at Earth 1:1, sampled finely: the ground climbs steeply through the
		// beds that show sandstone and limestone, and gently through those that show shale, chalk, salt and coal.
		Geology geology = new Geology(SEED, -256, 1, 1);
		Ledges full = ledges(geology, 1);
		System.out.printf("Ledges: hard beds %.2f, soft beds %.2f times as steep as the slope; %.1f%% of the cliffs in"
			+ " hard beds, %.1f%% of the benches on soft ones%n", full.hard, full.soft, 100 * full.hardCliffs,
			100 * full.softBenches);
		assertTrue(full.hard > 1.4, "hard beds stand steeper than the slope: " + full.hard);
		assertTrue(full.soft < 0.6, "soft beds lie gentler than the slope: " + full.soft);
		// Thin partings and coal seams sit in some cliffs, and thin sandstones recede with the shale around them.
		assertTrue(full.hardCliffs > 0.95, "cliffs in hard beds: " + full.hardCliffs);
		assertTrue(full.softBenches > 0.75, "benches on soft beds: " + full.softBenches);
		// Weaker ledges, on a gentler or a greener slope, stand on the same beds.
		Ledges weak = ledges(geology, 0.4);
		System.out.printf("Weak ledges: %.1f%% of the cliffs in hard beds%n", 100 * weak.hardCliffs);
		assertTrue(weak.hardCliffs > 0.95, "weak cliffs in hard beds: " + weak.hardCliffs);
	}

	/** How steep the ground climbs through hard and soft beds, and which beds its cliffs and benches are in. */
	private record Ledges(double hard, double soft, double hardCliffs, double softBenches) {
	}

	private static Ledges ledges(Geology geology, double strength) {
		// A cliff climbs at least 90% as steeply as the steepest the ledges get at this strength.
		double cliff = 0.9 / (1 - strength * (1 - 1.0 / 3));
		double hard = 0, soft = 0, cliffs = 0, hardCliffs = 0, benches = 0, softBenches = 0;
		int hardCount = 0, softCount = 0;
		for (int x = -6000; x < 6000; x += 373) {
			for (int z = -6000; z < 6000; z += 367) {
				Geology.Column c = lowland(geology, x, z);
				Geology.Site site = geology.site(x, z, c.meanSurfaceY, c.meanElevation, c.relief);
				double step = 0.05;
				for (double y = c.sedimentBase + 10; y < 70; y += step) {
					double ground = y + geology.ledge(site, y, strength);
					double next = y + step + geology.ledge(site, y + step, strength);
					double steepness = (next - ground) / step;
					assertTrue(steepness > 0, "the ground climbs at " + x + ", " + y + ", " + z);
					Rock rock = geology.rock(c, x, (int) Math.floor((ground + next) / 2), z);
					if (steepness > cliff) {
						cliffs++;
						hardCliffs += HARD_BEDS.contains(rock) ? 1 : 0;
					} else if (steepness < 0.5) {
						benches++;
						softBenches += SOFT_BEDS.contains(rock) ? 1 : 0;
					}
					if (HARD_BEDS.contains(rock)) {
						hard += steepness;
						hardCount++;
					} else if (SOFT_BEDS.contains(rock)) {
						soft += steepness;
						softCount++;
					}
				}
			}
		}
		return new Ledges(hard / hardCount, soft / softCount, hardCliffs / cliffs, softBenches / benches);
	}

	@Test
	void ledgesAverageOutAndStayWithinBounds() {
		Geology geology = new Geology(SEED, -256, 1, 1);
		for (int x = -6000; x < 6000; x += 1201) {
			for (int z = -6000; z < 6000; z += 1193) {
				Geology.Column c = lowland(geology, x, z);
				Geology.Site site = geology.site(x + 0.5, z + 0.5, c.meanSurfaceY, c.meanElevation, c.relief);
				double sum = 0;
				int count = 0;
				for (double y = c.sedimentBase + 8; y < c.sedimentBase + 1008; y += 0.5) {
					double ledge = geology.ledge(site, y, 1);
					assertTrue(Math.abs(ledge) <= 10, "a ledge of " + ledge + " blocks");
					sum += ledge;
					count++;
				}
				assertEquals(0, sum / count, 0.5, "the ground keeps its height on average at " + x + ", " + z);
			}
		}
	}

	@Test
	void ledgesChangeSmoothly() {
		Geology geology = new Geology(SEED, -256, 1, 1);
		for (int x = -6000; x < 6000; x += 1201) {
			for (int z = -6000; z < 6000; z += 1193) {
				Geology.Column c = lowland(geology, x, z);
				Geology.Site site = geology.site(x, z, c.meanSurfaceY, c.meanElevation, c.relief);
				// Through the contact with the basement and up the beds.
				for (double y = c.sedimentBase - 10; y < c.sedimentBase + 200; y += 0.25) {
					assertEquals(geology.ledge(site, y, 1), geology.ledge(site, y + 0.01, 1), 0.05, "up at " + y);
				}
				for (double t = 0; t < 64; t += 0.25) {
					double y = c.sedimentBase + 40;
					Geology.Site here = geology.site(x + t, z, c.meanSurfaceY, c.meanElevation, c.relief);
					Geology.Site next = geology.site(x + t + 0.01, z, c.meanSurfaceY, c.meanElevation, c.relief);
					assertEquals(geology.ledge(here, y, 1), geology.ledge(next, y, 1), 0.05, "along X at " + (x + t));
				}
			}
		}
	}

	@Test
	void noLedgesInTheBasementOrInFoldBelts() {
		Geology geology = new Geology(SEED, -256, 1, 1);
		Geology.Column c = new Geology.Column();
		int folded = 0;
		for (int x = -40000; x < 40000; x += 1009) {
			for (int z = -40000; z < 40000; z += 997) {
				c.surfaceY = c.meanSurfaceY = 1200;
				c.meanElevation = 1140;
				c.relief = 300;
				geology.column(x, z, c);
				Geology.Site site = geology.site(x, z, c.meanSurfaceY, c.meanElevation, c.relief);
				for (double y = c.sedimentBase - 300; y < c.sedimentBase - 4; y += 3.7) {
					assertEquals(0, geology.ledge(site, y, 1), "in the basement at " + x + ", " + y + ", " + z);
				}
				if (c.fold() >= 0.35f) {
					folded++;
					for (double y = c.sedimentBase; y < c.meanSurfaceY; y += 3.7) {
						assertEquals(0, geology.ledge(site, y, 1), "in a fold belt at " + x + ", " + y + ", " + z);
					}
				}
			}
		}
		assertTrue(folded > 0, "some fold belts");
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
