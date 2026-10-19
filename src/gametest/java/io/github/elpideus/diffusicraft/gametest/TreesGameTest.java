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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plants every tree of Diffusicraft's data pack on a superflat world, three of each in the biome it grows in, and takes
 * a screenshot of each: {@code diffusicraft-world-tree-<name>} in {@code build/run/clientGameTest/screenshots}. It
 * fails if a tree never grows on open grass, as happens when its trunk, foliage or decorators are set up wrongly. It
 * needs no models and takes about a minute: run it alone with {@code -Pdiffusicraft.gametests=Trees}.
 */
public class TreesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");
	/** Blocks between the trees' plots along X: room for the widest crowns and for the biome blend between plots. */
	private static final int PLOT = 64;
	/** Blocks between the three trees of a plot. */
	private static final int SPACING = 16;

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
			List<Plot> plots = server.computeOnServer(TreesGameTest::plant);
			check(!plots.isEmpty(), "the data pack has trees");
			for (Plot plot : plots) {
				// Level with the middle of the tallest tree, and far enough back for the vertical field of view, 70°,
				// to take it in from the ground to the top.
				int half = (plot.top - plot.ground) / 2;
				int distance = Math.max(20, (int) Math.ceil((half + 4) / Math.tan(Math.toRadians(35))));
				WorldGenerationGameTest.view(context, world, new BlockPos(plot.x, plot.ground + half, -distance), 0, 0,
					"tree-" + plot.name);
			}
			List<String> failed = plots.stream().filter(p -> p.grown == 0).map(Plot::name).toList();
			check(failed.isEmpty(), "trees that never grew on open grass: " + failed);
		} finally {
			context.runOnClient(mc -> hideHud(mc, false));
		}
	}

	/**
	 * A tree's plot: its name, the X of its middle tree, the Y its trees stand on and the Y above the tallest, and how
	 * many of the three grew.
	 */
	private record Plot(String name, int x, int ground, int top, int grown) {
	}

	/** Plants three of each tree along X, each in a plot of the biome it grows in. */
	private static List<Plot> plant(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Map<Identifier, Holder<Biome>> homes = homes(level);
		List<Holder.Reference<Feature>> trees = level.registryAccess().lookupOrThrow(Registries.FEATURE).listElements()
			.filter(f -> f.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID) && f.value() instanceof TreeFeature)
			.sorted(Comparator.comparing(f -> f.key().identifier().getPath()))
			.toList();
		List<Plot> plots = new ArrayList<>();
		for (int i = 0; i < trees.size(); i++) {
			Holder.Reference<Feature> tree = trees.get(i);
			Identifier id = tree.key().identifier();
			int x = (i + 1) * PLOT;
			for (int cx = (x - PLOT / 2) >> 4; cx <= (x + PLOT / 2) >> 4; cx++) {
				for (int cz = -2; cz <= 1; cz++) {
					level.getChunk(cx, cz);
				}
			}
			int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, 0);
			Holder<Biome> home = homes.get(id);
			if (home != null) {
				// In bands of 16 blocks: the command refuses more than 32,768 blocks at once.
				for (int y = ground - 4; y < ground + 60; y += 16) {
					FillBiomeCommand.fill(level, new BlockPos(x - PLOT / 2 + 1, y, -16),
						new BlockPos(x + PLOT / 2 - 1, y + 15, 15), home).ifRight(e -> {
						throw new AssertionError("Trees: cannot set the biome of " + id + "'s plot: " + e.getMessage());
					});
				}
			}
			int grown = 0, top = ground;
			for (int k = -1; k <= 1; k++) {
				RandomSource random = RandomSource.create(id.hashCode() * 31L + k);
				if (tree.value().place(level, level.getChunkSource().getGenerator(), random,
					new BlockPos(x + k * SPACING, ground, 0))) {
					grown++;
				}
				top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + k * SPACING, 0));
			}
			LOGGER.info("Tree {}: {} of 3 grew, in {}", id, grown, home == null ? "plains" : home.getRegisteredName());
			plots.add(new Plot(id.getPath(), x, ground, top, grown));
		}
		return plots;
	}

	/** For each feature, the first Diffusicraft biome by name whose features lead to it. */
	private static Map<Identifier, Holder<Biome>> homes(ServerLevel level) {
		Map<Identifier, Holder<Biome>> homes = new HashMap<>();
		level.registryAccess().lookupOrThrow(Registries.BIOME).listElements()
			.filter(b -> b.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID))
			.sorted(Comparator.comparing(b -> b.key().identifier().getPath()))
			.forEach(biome -> {
				Set<Identifier> reached = new HashSet<>();
				for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
					for (Holder<PlacedFeature> placed : step) {
						reach(placed.value().feature(), reached);
					}
				}
				reached.forEach(id -> homes.putIfAbsent(id, biome));
			});
		return homes;
	}

	/** Adds {@code feature} and every feature it places, such as the trees of a random selector, to {@code reached}. */
	private static void reach(Holder<Feature> feature, Set<Identifier> reached) {
		if (feature.unwrapKey().map(key -> !reached.add(key.identifier())).orElse(false)) {
			return;
		}
		feature.value().getSubFeatures().forEach(sub -> reach(sub, reached));
	}

	/** Hides the HUD, or shows it again, as F1 does. */
	private static void hideHud(Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) {
			mc.gui.hud.toggle();
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Trees: " + what);
		}
	}
}
