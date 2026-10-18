package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateFunction;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.density.SpawnSuitabilityFunction;
import io.github.elpideus.diffusicraft.worldgen.density.SurfaceFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.material.StrataRule;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Registers the world generator. The generator itself is data: noise settings, density functions and world presets
 * in {@code data/diffusicraft/worldgen}, built on the density function types registered here.
 */
public final class DiffusicraftWorldgen {
	/** The default world type: Diffusicraft at {@link WorldScale#STANDARD}. */
	public static final ResourceKey<WorldPreset> STANDARD_PRESET = preset(WorldScale.STANDARD);
	public static final ResourceKey<WorldPreset> GRAND_PRESET = preset(WorldScale.GRAND);
	public static final ResourceKey<WorldPreset> EARTH_PRESET = preset(WorldScale.EARTH);

	private DiffusicraftWorldgen() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("surface"), SurfaceFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("climate"), ClimateFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("spawn_suitability"),
			SpawnSuitabilityFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("hollows"), HollowsFunction.CODEC);
		Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE, Diffusicraft.id("strata"), StrataRule.CODEC);
		TerrainFields.register();
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			StrataRule.clearCache();
			Hollows.clearCache();
		});
	}

	public static ResourceKey<WorldPreset> preset(WorldScale scale) {
		return ResourceKey.create(Registries.WORLD_PRESET, Diffusicraft.id(scale.getSerializedName()));
	}

	/** Whether a preset is one of Diffusicraft's. */
	public static boolean isDiffusicraft(ResourceKey<WorldPreset> preset) {
		return preset.identifier().getNamespace().equals(Diffusicraft.MOD_ID);
	}

	/** Whether noise settings are Diffusicraft's, which is told by their material rule. */
	public static boolean isDiffusicraft(NoiseGeneratorSettings settings) {
		return settings.materialRule().unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID)).isPresent();
	}

	/** Whether a generator uses Diffusicraft's noise settings, and so needs the models. */
	public static boolean usesModels(ChunkGenerator generator) {
		return generator instanceof NoiseBasedChunkGenerator noise && noise.generatorSettings().unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID)).isPresent();
	}
}
