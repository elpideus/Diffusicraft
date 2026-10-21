package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeCoverTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// No data pack is loaded, so the tags that the giant asks of the ground and of its own blocks are bound here.
		BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(
			BlockTags.SUBSTRATE_OVERWORLD, List.of(Blocks.GRASS_BLOCK.builtInRegistryHolder()),
			BlockTags.LOGS, List.of(Blocks.JUNGLE_WOOD.builtInRegistryHolder()),
			BlockTags.LEAVES, List.of(Blocks.JUNGLE_LEAVES.builtInRegistryHolder())))).apply();
	}

	/** A biome that grows one giant kapok in every other chunk, and nothing else. */
	private static Holder<Biome> kapoks() {
		GiantTreeFeature kapok = new GiantTreeFeature(GiantTreeFeature.Form.KAPOK, Blocks.JUNGLE_WOOD.defaultBlockState(),
			Blocks.JUNGLE_LEAVES.defaultBlockState(), UniformInt.of(80, 130));
		PlacedFeature placed = new PlacedFeature(Holder.direct(kapok), List.of(RarityFilter.onAverageOnceEvery(2)));
		return Holder.direct(new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.95f)
			.downfall(0.9f)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(new BiomeGenerationSettings.PlainBuilder()
				.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, Holder.direct(placed)).build())
			.build());
	}

	@Test
	void giantsAreDrawnWholeWithTheirTrunksFarOut() {
		TreeCover cover = TreeCover.of(kapoks());
		TreeCover.Crown crown = new TreeCover.Crown();
		int columns = 0, covered = 0, tall = 0, trunks = 0, farTrunks = 0;
		for (int z = 0; z < 512; z++) {
			for (int x = 0; x < 512; x++) {
				columns++;
				if (cover.crown(42, x, z, 1, crown)) {
					covered++;
					tall += crown.high > 70 ? 1 : 0;
					trunks += crown.holdsTrunk ? 1 : 0;
					assertSame(Blocks.JUNGLE_LEAVES, crown.leaves.getBlock());
					assertSame(Blocks.JUNGLE_WOOD, crown.trunk.getBlock());
				}
				if (x % 4 == 0 && z % 4 == 0 && cover.crown(42, x, z, 4, crown) && crown.holdsTrunk) {
					farTrunks++;
				}
			}
		}
		// One kapok in two chunks, each an umbrella some 35 blocks across.
		float share = covered / (float) columns;
		assertTrue(share > 0.2f && share < 0.9f, "the kapoks cover " + share + " of the ground");
		assertTrue(tall > covered * 0.8f, tall + " of " + covered + " columns under a crown high up");
		assertTrue(trunks > 0 && farTrunks > 0, trunks + " trunks, " + farTrunks + " a level of detail farther");
	}
}
