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
		int cover = 0;
		int sediment = 0;
		int total = 0;
		for (int x = -3000; x < 3000; x += 131) {
			for (int z = -3000; z < 3000; z += 127) {
				Geology.Column c = lowland(geology, x, z);
				for (int y = 70; y < 80; y++) {
					total++;
					if (y >= c.sedimentBase()) {
						cover++;
						sediment += SEDIMENTS.contains(geology.rock(c, x, y, z)) ? 1 : 0;
					}
				}
			}
		}
		assertTrue(cover > total / 2, cover + " of " + total + " blocks below lowland surfaces are in the cover");
		System.out.printf("Cover: %d of %d blocks sedimentary rock%n", sediment, cover);
		// Most of the cover's beds are stone; the rest are the basin's own rocks.
		assertTrue(sediment > cover / 5 && sediment < cover / 2,
			sediment + " of " + cover + " blocks of the cover are sedimentary rock");
	}

	@Test
	void theGroundIsMostlyStoneAboveZeroAndDeepslateBelow() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		Map<Rock, Integer> above = new EnumMap<>(Rock.class);
		Map<Rock, Integer> below = new EnumMap<>(Rock.class);
		int aboveTotal = 0;
		int belowTotal = 0;
		for (int x = -6000; x < 6000; x += 211) {
			for (int z = -6000; z < 6000; z += 199) {
				Geology.Column c = lowland(geology, x, z);
				for (int y = -60; y < 72; y++) {
					Rock rock = geology.rock(c, x, y, z);
					if (y >= 0) {
						above.merge(rock, 1, Integer::sum);
						aboveTotal++;
					} else {
						below.merge(rock, 1, Integer::sum);
						belowTotal++;
					}
				}
			}
		}
		int stone = above.getOrDefault(Rock.STONE, 0);
		int deepslate = below.getOrDefault(Rock.DEEPSLATE, 0);
		int sandstone = above.getOrDefault(Rock.SANDSTONE, 0) + above.getOrDefault(Rock.RED_SANDSTONE, 0);
		assertTrue(stone > 0.5 * aboveTotal, "stone " + stone + " of " + aboveTotal + ": " + above);
		assertTrue(deepslate > 0.6 * belowTotal, "deepslate " + deepslate + " of " + belowTotal + ": " + below);
		assertTrue(sandstone < 0.05 * aboveTotal, "sandstone " + sandstone + " of " + aboveTotal + ": " + above);
	}

	@Test
	void sandstoneKeepsToTheDeserts() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		int sandstone = 0;
		int total = 0;
		for (int x = -6000; x < 6000; x += 211) {
			for (int z = -6000; z < 6000; z += 199) {
				Geology.Column c = lowland(geology, x, z);
				c.temperature = 24;
				c.precipitation = 150;
				geology.column(x, z, c);
				for (int y = 70; y < 80; y++) {
					if (y < c.sedimentBase()) {
						continue;
					}
					Rock rock = geology.rock(c, x, y, z);
					total++;
					sandstone += rock == Rock.SANDSTONE || rock == Rock.RED_SANDSTONE ? 1 : 0;
				}
			}
		}
		assertTrue(sandstone > 0.2 * total, sandstone + " of " + total + " blocks of a desert's cover are sandstone");
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
	void sulfurousGroundLiesInPatchesOverTheVolcanicBasement() {
		// At 1:1 a region spans about four times as many blocks as at 15 m per block.
		for (double meters : new double[]{15, 1}) {
			Geology geology = new Geology(SEED, -64, meters, 10);
			int step = (int) Math.round(97 * Math.sqrt(15 / meters));
			int sulfurous = 0, count = 0;
			for (int x = -400 * step; x < 400 * step; x += step) {
				for (int z = -400 * step; z < 400 * step; z += step) {
					count++;
					if (geology.sulfurous(x, z)) {
						sulfurous++;
						Geology.Basement basement = lowland(geology, x, z).basement;
						assertTrue(basement == Geology.Basement.SCHIST || basement == Geology.Basement.VOLCANIC,
							basement + " at " + x + ", " + z);
					}
				}
			}
			double share = sulfurous / (double) count;
			System.out.printf("sulfurous ground at %.0f m per block: %.2f%%%n", meters, 100 * share);
			assertTrue(share > 0.02 && share < 0.06, "sulfurous ground: " + share);
		}
	}

	@Test
	void theSaltCavesKnowTheEvaporiteBasins() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		int evaporite = 0, count = 0;
		for (float[] climate : new float[][]{{24, 150}, {14, 300}, {11, 800}, {5, 200}}) {
			for (int x = -6000; x < 6000; x += 211) {
				for (int z = -6000; z < 6000; z += 199) {
					Geology.Column c = lowland(geology, x, z);
					c.temperature = climate[0];
					c.precipitation = climate[1];
					geology.column(x, z, c);
					boolean basin = c.basin() == Geology.Basin.EVAPORITE;
					assertEquals(basin, geology.evaporite(x, z, climate[0], climate[1]), x + ", " + z);
					evaporite += basin ? 1 : 0;
					count++;
				}
			}
		}
		// Most of the arid ground, and none of the rest.
		assertTrue(evaporite > 0.25 * count && evaporite < 0.5 * count, evaporite + " of " + count);
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
		// hard beds and gently through the soft ones. Sandstone and limestone show only in hard beds; shale, chalk and
		// salt only in soft ones, and stone in both.
		Geology geology = new Geology(SEED, -256, 1, 1);
		Ledges full = ledges(geology, 1);
		System.out.printf("Ledges: hard beds %.2f, soft beds %.2f times as steep as the slope; %.1f%% of the cliffs in"
			+ " hard rock or stone, %.1f%% of the benches on soft rock or stone%n", full.hard, full.soft,
			100 * full.hardCliffs, 100 * full.softBenches);
		assertTrue(full.hard > 1.4, "hard beds stand steeper than the slope: " + full.hard);
		assertTrue(full.soft < 0.6, "soft beds lie gentler than the slope: " + full.soft);
		// Coal seams sit in some cliffs.
		assertTrue(full.hardCliffs > 0.95, "cliffs in hard rock or stone: " + full.hardCliffs);
		assertTrue(full.softBenches > 0.75, "benches on soft rock or stone: " + full.softBenches);
		// Weaker ledges, on a gentler or a greener slope, stand on the same beds.
		Ledges weak = ledges(geology, 0.4);
		System.out.printf("Weak ledges: %.1f%% of the cliffs in hard rock or stone%n", 100 * weak.hardCliffs);
		assertTrue(weak.hardCliffs > 0.95, "weak cliffs in hard rock or stone: " + weak.hardCliffs);
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
					int block = (int) Math.floor((ground + next) / 2);
					Rock rock = geology.rock(c, x, block, z);
					boolean hardBed = geology.hardBed(c, block);
					assertFalse(hardBed ? SOFT_BEDS.contains(rock) && rock != Rock.COAL_SEAM : HARD_BEDS.contains(rock),
						rock + " in a " + (hardBed ? "hard" : "soft") + " bed at " + x + ", " + block + ", " + z);
					if (steepness > cliff) {
						cliffs++;
						hardCliffs += HARD_BEDS.contains(rock) || rock == Rock.STONE ? 1 : 0;
					} else if (steepness < 0.5) {
						benches++;
						softBenches += SOFT_BEDS.contains(rock) || rock == Rock.STONE ? 1 : 0;
					}
					if (hardBed) {
						hard += steepness;
						hardCount++;
					} else {
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
	void modMineralsTakeSomeBodiesWhereAModAddsThem() {
		Geology geology = new Geology(SEED, -64, 15, 10);
		Set<Ore> all = EnumSet.allOf(Ore.class);
		Set<Ore> zinc = EnumSet.copyOf(Ore.VANILLA);
		zinc.add(Ore.ZINC);
		Map<Ore, Set<Rock>> hosts = new EnumMap<>(Ore.class);
		long vanilla = 0;
		long modded = 0;
		for (int x = 0; x < 512; x += 2) {
			for (int z = 0; z < 512; z += 5) {
				Geology.Column c = lowland(geology, x, z);
				for (int y = -60; y < 78; y++) {
					Rock rock = geology.rock(c, x, y, z);
					Ore without = geology.ore(c, x, y, z, rock);
					Ore with = geology.ore(c, x, y, z, rock, all);
					Ore withZinc = geology.ore(c, x, y, z, rock, zinc);
					assertTrue(without == null || Ore.VANILLA.contains(without), "vanilla's alone: " + without);
					// A mod's mineral takes a whole body, of its own size, or leaves it as it was.
					assertTrue(withZinc == without || withZinc == null || withZinc == Ore.ZINC,
						without + " became " + withZinc);
					assertTrue(with == without || with == null || !Ore.VANILLA.contains(with));
					// Bodies only: a coal seam is coal all through.
					vanilla += without != null && rock != Rock.COAL_SEAM ? 1 : 0;
					if (with != null && !Ore.VANILLA.contains(with)) {
						modded++;
						hosts.computeIfAbsent(with, o -> EnumSet.noneOf(Rock.class)).add(rock);
					}
				}
			}
		}
		assertTrue(modded > 0.1 * vanilla && modded < 0.6 * vanilla, modded + " modded, " + vanilla + " vanilla");
		assertFalse(hosts.containsKey(Ore.ALUMINUM), "bauxite lies in the soil, not the rock");
		assertTrue(EnumSet.of(Rock.LIMESTONE, Rock.SHALE, Rock.SLATE, Rock.ANDESITE, Rock.TUFF, Rock.STONE)
			.containsAll(hosts.getOrDefault(Ore.ZINC, Set.of())), "zinc in " + hosts.get(Ore.ZINC));
		assertTrue(hosts.containsKey(Ore.ZINC) && hosts.containsKey(Ore.NICKEL), hosts.toString());
		for (Ore metal : new Ore[]{Ore.PLATINUM, Ore.OSMIUM, Ore.IRIDIUM}) {
			assertTrue(EnumSet.of(Rock.GABBRO, Rock.PERIDOTITE).containsAll(hosts.getOrDefault(metal, Set.of())),
				metal + " in " + hosts.get(metal));
		}
		assertTrue(EnumSet.of(Rock.MARBLE).containsAll(hosts.getOrDefault(Ore.RUBY, Set.of())));
		assertTrue(EnumSet.of(Rock.PERIDOTITE).containsAll(hosts.getOrDefault(Ore.PERIDOT, Set.of())));
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
