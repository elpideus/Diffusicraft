package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
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
import org.jspecify.annotations.Nullable;
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
 * Plants every tree of Diffusicraft's data pack on a superflat world, the giants too, three of each in the biome it
 * grows in, and takes a screenshot of each: {@code diffusicraft-world-tree-<name>} in
 * {@code build/run/clientGameTest/screenshots}. The giants grow twice: at their full height, as in a 1:1 world, and
 * as in a Vanilla one ({@code tree-<name>-vanilla}). It fails if a tree never grows on open grass, as happens when its
 * trunk, foliage or decorators are set up wrongly. It needs no models and takes about a minute: run it alone with
 * {@code -Pdiffusicraft.gametests=Trees}.
 */
public class TreesGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft game test");
	/** Blocks between the trees' plots along X: room for the widest crowns and for the biome blend between plots. */
	private static final int PLOT = 64;
	/** Blocks between the three trees of a plot. */
	private static final int SPACING = 16;
	/** The same for the giants, whose crowns reach out a few dozen blocks and which do not grow under each other's. */
	private static final int GIANT_PLOT = 192;
	private static final int GIANT_SPACING = 56;
	/** Chunks seen around the camera: enough for the giants, seen from about 170 blocks away. */
	private static final int RENDER_DISTANCE = 14;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTests.skips(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(RENDER_DISTANCE);
			hideHud(mc, true);
		});
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			// Not Fabric's wait for the chunks to render, which expects a square of chunks while the server sends a
			// circle: above a render distance of 6 it never returns. Each view waits for its own chunks.
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

	/** A tree to plant, and the size of world it grows as in, or {@code null} for the superflat world's own. */
	private record Planting(Holder.Reference<Feature> tree, @Nullable WorldScale scale) {
	}

	/** Plants three of each tree along X, each in a plot of the biome it grows in. */
	private static List<Plot> plant(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Map<Identifier, Holder<Biome>> homes = homes(level);
		List<Holder.Reference<Feature>> trees = level.registryAccess().lookupOrThrow(Registries.FEATURE).listElements()
			.filter(f -> f.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID)
				&& (f.value() instanceof TreeFeature || f.value() instanceof GiantTreeFeature))
			.sorted(Comparator.comparing(f -> f.key().identifier().getPath()))
			.toList();
		List<Planting> plantings = new ArrayList<>();
		for (Holder.Reference<Feature> tree : trees) {
			plantings.add(new Planting(tree, null));
			if (tree.value() instanceof GiantTreeFeature) {
				// Again as in the smallest world, to see how much less the giants grow there.
				plantings.add(new Planting(tree, WorldScale.VANILLA));
			}
		}
		List<Plot> plots = new ArrayList<>();
		int x = 0, last = 0;
		for (Planting planting : plantings) {
			Holder.Reference<Feature> tree = planting.tree;
			Identifier id = tree.key().identifier();
			String name = id.getPath() + (planting.scale == null ? "" : "-" + planting.scale.getSerializedName());
			boolean giant = tree.value() instanceof GiantTreeFeature;
			int plot = giant ? GIANT_PLOT : PLOT, spacing = giant ? GIANT_SPACING : SPACING, side = giant ? 32 : 16;
			x += (last + plot) / 2;
			last = plot;
			for (int cx = (x - plot / 2) >> 4; cx <= (x + plot / 2) >> 4; cx++) {
				for (int cz = -(side >> 4) - 1; cz <= side >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, 0);
			Holder<Biome> home = homes.get(id);
			if (home != null) {
				// Up to where the tallest giants' crowns reach, in boxes of 16 blocks: the command refuses more than
				// 32,768 blocks at once. Each starts on a cell of the biome's 4 blocks, so that it sets a cell of its
				// own: one that sets none fails.
				int west = Math.ceilDiv(x - plot / 2 + 1, 4) * 4, east = x + plot / 2 - 1;
				for (int y = ground - 4; y < ground + (giant ? 250 : 60); y += 16) {
					for (int bx = west; bx <= east; bx += 16) {
						FillBiomeCommand.fill(level, new BlockPos(bx, y, -side),
							new BlockPos(Math.min(bx + 15, east), y + 15, side - 1), home).ifRight(e -> {
							throw new AssertionError("Trees: cannot set the biome of " + id + "'s plot: "
								+ e.getMessage());
						});
					}
				}
			}
			int grown = 0, top = ground;
			for (int k = -1; k <= 1; k++) {
				RandomSource random = RandomSource.create(id.hashCode() * 31L + k);
				BlockPos at = new BlockPos(x + k * spacing, ground, 0);
				if (planting.scale == null ? tree.value().place(level, level.getChunkSource().getGenerator(), random, at)
					: ((GiantTreeFeature) tree.value()).place(level, random, at, planting.scale)) {
					grown++;
				}
				top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + k * spacing, 0));
			}
			LOGGER.info("Tree {}: {} of 3 grew, {} blocks tall at most, in {}", name, grown, top - ground,
				home == null ? "plains" : home.getRegisteredName());
			plots.add(new Plot(name, x, ground, top, grown));
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
