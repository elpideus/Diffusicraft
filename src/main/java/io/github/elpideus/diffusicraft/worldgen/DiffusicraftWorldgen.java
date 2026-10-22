package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.mixin.NoiseGeneratorSettingsAccessor;
import io.github.elpideus.diffusicraft.worldgen.biome.ClimateBiomeSource;
import io.github.elpideus.diffusicraft.worldgen.biome.HeightTemperature;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateFunction;
import io.github.elpideus.diffusicraft.worldgen.density.CoastFunction;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.density.RiverFunction;
import io.github.elpideus.diffusicraft.worldgen.density.SurfaceFunction;
import io.github.elpideus.diffusicraft.worldgen.feature.DiffusicraftWorldFilter;
import io.github.elpideus.diffusicraft.worldgen.feature.ErraticFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.HollowSurfacePlacement;
import io.github.elpideus.diffusicraft.worldgen.feature.LianaFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.RiverFallFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.RockfallFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.SeleniteBeamFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.UndergrowthFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.WaterfallFeature;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.material.ModOres;
import io.github.elpideus.diffusicraft.worldgen.material.RiverRule;
import io.github.elpideus.diffusicraft.worldgen.material.StrataRule;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Registers the world generator. The generator itself is data: noise settings, density functions, world presets and
 * biomes in {@code data/diffusicraft/worldgen}, built on the density function, material rule, feature and placement
 * types registered here.
 */
public final class DiffusicraftWorldgen {
	/** The default world type: Diffusicraft at {@link WorldScale#DEFAULT}. */
	public static final ResourceKey<WorldPreset> DEFAULT_PRESET = preset(WorldScale.DEFAULT);
	/**
	 * Vanilla's features that Diffusicraft worlds leave out. The blobs of dirt and gravel that vanilla scatters through
	 * the rock: a soil is rock weathered at the top of the ground ({@link StrataRule}), and loose gravel lies in scree,
	 * river beds and on beaches, not deep in solid rock. Where the blobs reached a cliff, they showed through its face.
	 * And the lava lakes on the surface, which no plain, steppe or forest has; the lava lakes underground stay.
	 */
	private static final Set<ResourceKey<PlacedFeature>> LEFT_OUT = Set.of(OrePlacements.ORE_DIRT,
		OrePlacements.ORE_GRAVEL, MiscOverworldPlacements.LAKE_LAVA_SURFACE);
	/**
	 * What Diffusicraft worlds place right after vanilla's water springs, in this order: the falls of their rivers and
	 * their waterfalls, then the erratics and the boulders fallen at the feet of cliffs, which stay out of the falls'
	 * water; see {@link #afterSprings}.
	 */
	public static final List<ResourceKey<PlacedFeature>> AFTER_SPRINGS = Stream.of("river_falls", "waterfalls",
		"erratics", "rockfall").map(name -> ResourceKey.create(Registries.PLACED_FEATURE, Diffusicraft.id(name))).toList();

	private DiffusicraftWorldgen() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("surface"), SurfaceFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("climate"), ClimateFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("hollows"), HollowsFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("river"), RiverFunction.CODEC);
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Diffusicraft.id("coast"), CoastFunction.CODEC);
		Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE, Diffusicraft.id("strata"), StrataRule.CODEC);
		Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE, Diffusicraft.id("river"), RiverRule.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Diffusicraft.id("climate"), ClimateBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, Diffusicraft.id("hollow_surface"),
			HollowSurfacePlacement.CODEC);
		Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, Diffusicraft.id("diffusicraft_world"),
			DiffusicraftWorldFilter.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("selenite_beam"), SeleniteBeamFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("giant_tree"), GiantTreeFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("waterfall"), WaterfallFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("river_falls"), RiverFallFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("lianas"), LianaFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("erratic"), ErraticFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("rockfall"), RockfallFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, Diffusicraft.id("undergrowth"), UndergrowthFeature.CODEC);
		TerrainFields.register();
		addHollowFeatures();
		addLoneGiants();
		addLianas();
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			LevelStem overworld = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(LevelStem.OVERWORLD);
			HeightTemperature.set(overworld != null && usesModels(overworld.generator()) ? 0f : 1f);
		});
		// The ores of other mods are looked up again in the tags and ore features as they are now.
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
			if (!client) {
				ModOres.load(registries);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			HeightTemperature.set(1f);
			StrataRule.clearCache();
			Hollows.clearCache();
			Rivers.clearCache();
		});
	}

	/**
	 * Gives vanilla's lush and dripstone caves their plants and dripstone on the floors and ceilings of the Grand
	 * Hollows, which vanilla's placements, made for low caves, hardly reach (see {@link HollowSurfacePlacement}). They
	 * place nothing in other worlds. The order is the one {@code tools/biomes.py} lists them in.
	 */
	private static void addHollowFeatures() {
		addFeatures("hollow", Biomes.LUSH_CAVES, "hollow_moss_floor", "hollow_moss_ceiling", "hollow_lush_clay",
			"hollow_cave_vines", "hollow_spore_blossoms");
		addFeatures("hollow", Biomes.DRIPSTONE_CAVES, "hollow_dripstone_floor", "hollow_dripstone_ceiling");
	}

	/**
	 * Gives vanilla's plains and woods a giant tree now and then, over the trees of their own: ancient oaks on the
	 * plains and in the broadleaf woods, redwoods among the old spruce, kapoks over the jungle. They grow only in
	 * Diffusicraft worlds ({@link DiffusicraftWorldFilter}); its own groves of giants are biomes of their own.
	 */
	private static void addLoneGiants() {
		addFeatures("giant", Biomes.PLAINS, "lone_giant_oak_plains");
		addFeatures("giant", Biomes.SUNFLOWER_PLAINS, "lone_giant_oak_plains");
		for (ResourceKey<Biome> woods : List.of(Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST,
			Biomes.DARK_FOREST)) {
			addFeatures("giant", woods, "lone_giant_oak");
		}
		addFeatures("giant", Biomes.OLD_GROWTH_SPRUCE_TAIGA, "lone_redwood");
		addFeatures("giant", Biomes.OLD_GROWTH_PINE_TAIGA, "lone_redwood");
		addFeatures("giant", Biomes.JUNGLE, "lone_kapok");
	}

	/**
	 * Grows thickets of shrubs and ferns on the floors of vanilla's jungles ({@link UndergrowthFeature}), and lets their
	 * vines grow down from the crowns towards the floor ({@link LianaFeature}), after the trees and the giants. Only in
	 * Diffusicraft worlds; its own jungles have them too.
	 */
	private static void addLianas() {
		addFeatures("lianas", Biomes.JUNGLE, "jungle_undergrowth", "jungle_lianas");
		addFeatures("lianas", Biomes.SPARSE_JUNGLE, "sparse_jungle_undergrowth", "sparse_jungle_lianas");
		addFeatures("lianas", Biomes.BAMBOO_JUNGLE, "sparse_jungle_undergrowth", "sparse_jungle_lianas");
	}

	/**
	 * Adds features to the end of a biome's vegetation, in the order given. One modification for them all: Fabric
	 * applies modifications in the order of their ids, and the game requires every biome to agree on one order of
	 * features, so features added one by one could follow each other the other way round from Diffusicraft's biomes.
	 */
	private static void addFeatures(String name, ResourceKey<Biome> biome, String... features) {
		BiomeModifications.create(Diffusicraft.id(name + "_" + biome.identifier().getPath())).add(
			ModificationPhase.ADDITIONS, BiomeSelectors.includeByKey(biome), context -> {
				for (String feature : features) {
					context.getGenerationSettings().addFeature(GenerationStep.Decoration.VEGETAL_DECORATION,
						ResourceKey.create(Registries.PLACED_FEATURE, Diffusicraft.id(feature)));
				}
			});
	}

	public static ResourceKey<WorldPreset> preset(WorldScale scale) {
		return ResourceKey.create(Registries.WORLD_PRESET, Diffusicraft.id(scale.getSerializedName()));
	}

	/**
	 * The size of one of Diffusicraft's presets, or {@code null} for anyone else's. Each size is its own preset; only
	 * the default one is listed among the world types.
	 */
	public static WorldScale scale(ResourceKey<WorldPreset> preset) {
		if (!isDiffusicraft(preset)) {
			return null;
		}
		return Arrays.stream(WorldScale.values())
			.filter(scale -> scale.getSerializedName().equals(preset.identifier().getPath())).findFirst().orElse(null);
	}

	/** Whether a preset is one of Diffusicraft's. */
	public static boolean isDiffusicraft(ResourceKey<WorldPreset> preset) {
		return preset.identifier().getNamespace().equals(Diffusicraft.MOD_ID);
	}

	/** Whether noise settings are Diffusicraft's, which is told by their material rule. */
	public static boolean isDiffusicraft(NoiseGeneratorSettings settings) {
		return ownRule(settings).unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID)).isPresent();
	}

	/** The scale of Diffusicraft's noise settings, told by their material rule, or {@code null} for anyone else's. */
	public static WorldScale scale(NoiseGeneratorSettings settings) {
		return ownRule(settings).unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID))
			.flatMap(key -> Arrays.stream(WorldScale.values())
				.filter(scale -> scale.getSerializedName().equals(key.identifier().getPath())).findFirst())
			.orElse(null);
	}

	/** The material rule the settings were loaded with, before biome mods such as TerraBlender add theirs to it. */
	private static Holder<MaterialRule> ownRule(NoiseGeneratorSettings settings) {
		return ((NoiseGeneratorSettingsAccessor) (Object) settings).diffusicraft$materialRule();
	}

	/** Whether Diffusicraft worlds leave out a placed feature, which vanilla's biomes and its own list. */
	public static boolean leavesOut(Optional<ResourceKey<PlacedFeature>> feature) {
		return feature.isPresent() && LEFT_OUT.contains(feature.get());
	}

	/**
	 * Whether Diffusicraft worlds place their {@link #AFTER_SPRINGS} right after a placed feature: after vanilla's water
	 * springs, which nearly every land biome has, vanilla's and other mods' as well as Diffusicraft's. Added to the
	 * biomes instead, they would need a place in the order of features that every mod's biomes agree with.
	 */
	public static boolean afterSprings(Optional<ResourceKey<PlacedFeature>> feature) {
		return feature.isPresent() && feature.get().equals(MiscOverworldPlacements.SPRING_WATER);
	}

	/** Whether a generator uses Diffusicraft's noise settings, and so needs the models. */
	public static boolean usesModels(ChunkGenerator generator) {
		return generator instanceof NoiseBasedChunkGenerator noise && noise.generatorSettings().unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID)).isPresent();
	}
}
