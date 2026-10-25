package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import org.jspecify.annotations.Nullable;

/**
 * The biome of each Grand Hollow: one cave biome for the whole cavern, picked from the climate over its centre. A cave
 * keeps the mean temperature of the land above it all year round, and is as wet as the rain that soaks down into it.
 * <ul>
 *     <li><b>Sulfur caves</b> wherever the ground is {@link Geology#sulfurous}, whatever the climate: most of the Grand
 *     Hollows there and half the Ancient ones, eaten out by the sulfuric acid of the water that rises into them, as at
 *     Frasassi in Italy.</li>
 *     <li><b>Lush caves</b> under the wet tropics, where roots and water reach far down.</li>
 *     <li><b>Glowworm grottoes</b> under mild, wet land, like the Waitomo caves of New Zealand: ceilings strung with
 *     the glowing threads of fungus-gnat larvae over pools of clay.</li>
 *     <li><b>Selenite caverns</b>, in the Ancient Grand Hollows, mostly under dry land, like the Cave of the Crystals at
 *     Naica: beams of gypsum many blocks long, grown in still, warm water that has since drained away.</li>
 *     <li><b>Mushroom forests</b>, in the Ancient Grand Hollows under wet land: the forest of giant mushrooms that
 *     Verne set by the Lidenbrock Sea.</li>
 *     <li><b>Dripstone caves</b> everywhere else.</li>
 * </ul>
 * The Deep Grand Hollows over the lava seas get none of these: they keep the biome vanilla would pick. Pure: the same
 * cavern and climate always give the same biome.
 */
public final class HollowBiomes {
	public static final String LUSH_CAVES = "minecraft:lush_caves";
	public static final String DRIPSTONE_CAVES = "minecraft:dripstone_caves";
	public static final String GLOWWORM_GROTTO = "diffusicraft:glowworm_grotto";
	public static final String SELENITE_CAVERNS = "diffusicraft:selenite_caverns";
	public static final String MUSHROOM_FOREST = "diffusicraft:mushroom_forest";
	public static final String SULFUR_CAVES = "minecraft:sulfur_caves";

	private HollowBiomes() {
	}

	/**
	 * The biome of a Grand Hollow, or {@code null} to leave it to vanilla.
	 *
	 * @param kind the kind of hollow
	 * @param id   the hollow's {@link Hollows.Chamber#id()}
	 * @param c    the climate over the hollow's centre
	 */
	public static @Nullable String pick(Hollows.Kind kind, long id, BiomeClimate c) {
		double roll = GeoNoise.unit(GeoNoise.hash(id, 0, 1, 0));
		double second = GeoNoise.unit2(GeoNoise.hash(id, 0, 1, 0));
		// A roll of its own, so the other caverns of sulfurous ground share out as they do elsewhere.
		double sulfur = GeoNoise.unit(GeoNoise.hash(id, 0, 2, 0));
		return switch (kind) {
			case ANCIENT_GRAND_HOLLOW -> {
				if (c.sulfurous && sulfur < 0.5) {
					yield SULFUR_CAVES;
				}
				if (c.aridity() < 1) {
					yield roll < 0.75 ? SELENITE_CAVERNS : grand(second, c);
				}
				if (roll < 0.55) {
					yield MUSHROOM_FOREST;
				}
				yield roll < 0.75 ? SELENITE_CAVERNS : grand(second, c);
			}
			case GRAND_HOLLOW -> c.sulfurous && sulfur < 0.7 ? SULFUR_CAVES : grand(roll, c);
			default -> null;
		};
	}

	/**
	 * The climate over a Grand Hollow's centre, from the coarse map, and whether the ground there is sulfurous: the same
	 * wherever in the cavern it is asked for.
	 *
	 * @param scratch a column to read the terrain into
	 * @return {@code climate}, filled in
	 */
	public static BiomeClimate climate(BiomeWorld world, Hollows.Chamber chamber, TerrainField.Column scratch,
									   BiomeClimate climate) {
		WorldScale scale = world.scale();
		world.field().coarseColumn(scale.toPixel((int) Math.floor(chamber.z())),
			scale.toPixel((int) Math.floor(chamber.x())), scratch);
		climate.set(scratch);
		climate.sulfurous = world.geology().sulfurous(chamber.x(), chamber.z());
		return climate;
	}

	/**
	 * Whether the basin of a Grand Hollow holds a lake. Under wet land the water that soaks down through the rock fills
	 * it to the brim. Under dry land too little reaches it, under frozen ground none does, and the selenite caverns
	 * have been drained, like the Cave of the Crystals, whose crystals grew in water pumped out for the mine.
	 *
	 * @param biome the cavern's biome, from {@link #pick}
	 * @param c     the climate over the cavern's centre
	 */
	public static boolean holdsLake(@Nullable String biome, BiomeClimate c) {
		return biome != null && !biome.equals(SELENITE_CAVERNS) && c.aridity() >= 1 && c.temperature >= 0;
	}

	private static String grand(double roll, BiomeClimate c) {
		if (c.temperature >= 20 && c.precipitation >= 1500) {
			return roll < 0.7 ? LUSH_CAVES : DRIPSTONE_CAVES;
		}
		if (c.temperature >= 4 && c.temperature < 20 && c.precipitation >= 1000) {
			return roll < 0.7 ? GLOWWORM_GROTTO : DRIPSTONE_CAVES;
		}
		return DRIPSTONE_CAVES;
	}
}
