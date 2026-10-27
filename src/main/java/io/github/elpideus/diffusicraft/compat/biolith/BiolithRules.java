package io.github.elpideus.diffusicraft.compat.biolith;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Gives the taller Diffusicraft worlds the surface rules of Biolith's mods (Terrestria, Promenade and others). Biolith
 * puts them in front of the rule of every level whose dimension type is vanilla's overworld, as the world starts;
 * Diffusicraft's taller sizes have dimension types of their own, so this does the same for them, before the levels are
 * created. Diffusicraft runs the rules only in other mods' biomes (see
 * {@link io.github.elpideus.diffusicraft.worldgen.material.SplitRule}).
 *
 * <p>Biolith is not a dependency: its collector of rules is read by reflection, and nothing happens without it.
 */
public final class BiolithRules {
	private static final String COLLECTOR = "com.terraformersmc.biolith.impl.surface.SurfaceRuleCollector";

	private BiolithRules() {
	}

	public static void addTo(LevelStem overworld, RegistryAccess registries) {
		if (!FabricLoader.getInstance().isModLoaded("biolith")
			|| overworld.type().is(BuiltinDimensionTypes.OVERWORLD)
			|| !(overworld.generator() instanceof NoiseBasedChunkGenerator generator)) {
			return;
		}
		MaterialRule[] rules;
		try {
			Class<?> collector = Class.forName(COLLECTOR);
			Object rulesOfOverworld = collector.getField("OVERWORLD").get(null);
			rules = (MaterialRule[]) collector.getMethod("getAllBootstrapped", RegistryAccess.class)
				.invoke(rulesOfOverworld, registries);
		} catch (ReflectiveOperationException | ClassCastException e) {
			Diffusicraft.LOGGER.warn("Could not read Biolith's surface rules; its biomes keep vanilla's surface", e);
			return;
		}
		if (rules.length == 0) {
			return;
		}
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		DiffusicraftWorldgen.Loaded loaded = (DiffusicraftWorldgen.Loaded) (Object) settings;
		loaded.diffusicraft$setMaterialRule(Holder.direct(MaterialRules.sequence(
			Stream.concat(Arrays.stream(rules), Stream.of(loaded.diffusicraft$loadedRule().value()))
				.toArray(MaterialRule[]::new))));
	}
}
