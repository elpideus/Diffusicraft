package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lays out a plot of every Diffusicraft surface biome on a superflat world, grows its plants there as world generation
 * would, and takes a screenshot of each: {@code diffusicraft-world-biome-<name>} in
 * {@code build/run/clientGameTest/screenshots}. It shows how dense a biome's woods are, how its trees mix and what
 * colour its grass and leaves take. The ground stays superflat grass, so the biome's own ground and plants that need
 * sand, such as cacti, are missing. It needs no models and takes about a minute: run it alone with
 * {@code -Pdiffusicraft.gametests=Biomes}.
 */
public class BiomesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");
	/** Chunks on a side of a biome's plot. */
	private static final int CHUNKS = 3;
	/** Chunks between the plots' starts along X: the plot and a strip of plains, so that no biome hides another. */
	private static final int STRIDE = CHUNKS + 2;
	/** The steps of world generation that grow plants, scatter rocks and lay snow. */
	private static final List<GenerationStep.Decoration> STEPS = List.of(GenerationStep.Decoration.LOCAL_MODIFICATIONS,
		GenerationStep.Decoration.VEGETAL_DECORATION, GenerationStep.Decoration.TOP_LAYER_MODIFICATION);
	private static final TagKey<Biome> CAVE = tag("c", "is_cave");
	private static final TagKey<Biome> OCEAN = tag("minecraft", "is_ocean");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(6);
			hideHud(mc, true);
		});
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode spectator @a");
			List<Plot> plots = server.computeOnServer(BiomesGameTest::lay);
			check(!plots.isEmpty(), "the data pack has surface biomes");
			for (Plot plot : plots) {
				// From the plot's near edge, high enough to look over the first trees into the middle of the plot.
				WorldGenerationGameTest.view(context, world, new BlockPos(plot.x, plot.ground + 18, -30), 0, 25,
					"biome-" + plot.name);
			}
		} finally {
			context.runOnClient(mc -> hideHud(mc, false));
		}
	}

	/** A biome's plot: its name, the X of its middle and the Y of the superflat ground. */
	private record Plot(String name, int x, int ground) {
	}

	/** Lays out a plot of each surface biome along X and decorates it. */
	private static List<Plot> lay(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		List<Holder.Reference<Biome>> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME).listElements()
			.filter(b -> b.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID))
			.filter(b -> !b.is(CAVE) && !b.is(OCEAN))
			.sorted(Comparator.comparing(b -> b.key().identifier().getPath()))
			.toList();
		List<Plot> plots = new ArrayList<>();
		for (int i = 0; i < biomes.size(); i++) {
			Holder.Reference<Biome> biome = biomes.get(i);
			Identifier id = biome.key().identifier();
			int first = (i + 1) * STRIDE;
			// The plot and a chunk around it, into which trees on its edge reach.
			for (int cx = first - 1; cx <= first + CHUNKS; cx++) {
				for (int cz = -2; cz <= CHUNKS - 1; cz++) {
					level.getChunk(cx, cz);
				}
			}
			int minX = first << 4, maxX = ((first + CHUNKS) << 4) - 1;
			int minZ = -16, maxZ = ((CHUNKS - 1) << 4) - 1;
			int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, minX, minZ);
			// In bands of 8 blocks: the command refuses more than 32,768 blocks at once. Up to the crowns of the
			// tallest trees, whose leaves take the biome's colour.
			for (int y = ground - 4; y < ground + 60; y += 8) {
				FillBiomeCommand.fill(level, new BlockPos(minX, y, minZ), new BlockPos(maxX, y + 7, maxZ), biome)
					.ifRight(e -> {
						throw new AssertionError("Biomes: cannot set the biome of " + id + "'s plot: " + e.getMessage());
					});
			}
			List<HolderSet<PlacedFeature>> features = biome.value().getGenerationSettings().features();
			int placed = 0;
			for (int cx = first; cx < first + CHUNKS; cx++) {
				for (int cz = -1; cz < CHUNKS - 1; cz++) {
					// Where world generation starts each chunk's features: the modifiers spread them over the chunk
					// and find the ground.
					BlockPos origin = new BlockPos(cx << 4, level.getMinY(), cz << 4);
					for (GenerationStep.Decoration step : STEPS) {
						if (step.ordinal() >= features.size()) {
							continue;
						}
						int index = 0;
						for (Holder<PlacedFeature> feature : features.get(step.ordinal())) {
							RandomSource random = RandomSource.create(((cx * 31L + cz) * 31L + step.ordinal()) * 64L
								+ index++);
							// With the biome check, as world generation places them: a biome filter needs it.
							if (new FeaturePlacer(level, generator).placeWithBiomeCheck(feature.value(), random,
								origin)) {
								placed++;
							}
						}
					}
				}
			}
			LOGGER.info("Biome {}: {} features placed", id, placed);
			plots.add(new Plot(id.getPath(), (minX + maxX) / 2, ground));
		}
		return plots;
	}

	private static TagKey<Biome> tag(String namespace, String path) {
		return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(namespace, path));
	}

	/** Hides the HUD, or shows it again, as F1 does. */
	private static void hideHud(Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) {
			mc.gui.hud.toggle();
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Biomes: " + what);
		}
	}
}
