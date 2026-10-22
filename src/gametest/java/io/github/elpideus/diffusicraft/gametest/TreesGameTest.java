package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeStructure;
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
 * as in a Vanilla one ({@code tree-<name>-vanilla}). Those that grow as structures, the ancient oaks and the kapoks,
 * are written a chunk at a time, as their pieces are in a world. It fails if a tree never grows on open grass, as
 * happens when its trunk, foliage or decorators are set up wrongly. It needs no models and takes about a minute: run
 * it alone with {@code -Pdiffusicraft.gametests=Trees}.
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
	/** The tangent of half the vertical field of view, 70°. */
	private static final double HALF_HEIGHT = Math.tan(Math.toRadians(35));
	/** The same for the horizontal one, in a window of 16:9. */
	private static final double HALF_WIDTH = HALF_HEIGHT * 16 / 9;

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
				WorldGenerationGameTest.view(context, world, new BlockPos(plot.x, plot.eye, -plot.distance), 0, 0,
					"tree-" + plot.name);
			}
			List<String> failed = plots.stream().filter(p -> p.grown == 0).map(Plot::name).toList();
			check(failed.isEmpty(), "trees that never grew on open grass: " + failed);
		} finally {
			context.runOnClient(mc -> hideHud(mc, false));
		}
	}

	/**
	 * A tree's plot: its name, the X of its middle tree, the Y of the camera and how far back from the trees it stands,
	 * and how many of the three grew.
	 */
	private record Plot(String name, int x, int eye, int distance, int grown) {
	}

	/**
	 * A tree to plant, a feature or a giant that grows as a structure, and the size of world it grows as in, or
	 * {@code null} for the superflat world's own.
	 */
	private record Planting(Identifier id, @Nullable Feature feature, @Nullable GiantTreeStructure structure,
							@Nullable WorldScale scale) {
		boolean giant() {
			return structure != null || feature instanceof GiantTreeFeature;
		}

		/** Grows one tree at {@code at}, the block over the ground. */
		boolean place(ServerLevel level, RandomSource random, BlockPos at) {
			if (structure != null) {
				return structure.plant(level, random, at, scale == null ? WorldScale.ULTRA : scale);
			}
			return scale == null ? feature.place(level, level.getChunkSource().getGenerator(), random, at)
				: ((GiantTreeFeature) feature).place(level, random, at, scale);
		}
	}

	/** Plants three of each tree along X, each in a plot of the biome it grows in. */
	private static List<Plot> plant(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Map<Identifier, Holder<Biome>> homes = homes(level);
		List<Planting> plantings = new ArrayList<>();
		level.registryAccess().lookupOrThrow(Registries.FEATURE).listElements()
			.filter(f -> f.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID)
				&& (f.value() instanceof TreeFeature || f.value() instanceof GiantTreeFeature))
			.sorted(Comparator.comparing(f -> f.key().identifier().getPath()))
			.forEach(tree -> {
				plantings.add(new Planting(tree.key().identifier(), tree.value(), null, null));
				if (tree.value() instanceof GiantTreeFeature) {
					// Again as in the smallest world, to see how much less the giants grow there.
					plantings.add(new Planting(tree.key().identifier(), tree.value(), null, WorldScale.VANILLA));
				}
			});
		level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements()
			.filter(s -> s.key().identifier().getNamespace().equals(Diffusicraft.MOD_ID)
				&& s.value() instanceof GiantTreeStructure)
			.sorted(Comparator.comparing(s -> s.key().identifier().getPath()))
			.forEach(tree -> {
				Identifier id = tree.key().identifier();
				GiantTreeStructure structure = (GiantTreeStructure) tree.value();
				plantings.add(new Planting(id, null, structure, null));
				plantings.add(new Planting(id, null, structure, WorldScale.VANILLA));
				structure.biomes().stream()
					.min(Comparator.comparing((Holder<Biome> b) -> !b.getRegisteredName().startsWith(Diffusicraft.MOD_ID))
						.thenComparing(Holder::getRegisteredName))
					.ifPresent(biome -> homes.put(id, biome));
			});
		List<Plot> plots = new ArrayList<>();
		int x = 0, last = 0;
		for (Planting planting : plantings) {
			Identifier id = planting.id;
			String name = id.getPath() + (planting.scale == null ? "" : "-" + planting.scale.getSerializedName());
			boolean giant = planting.giant();
			// The structures' crowns spread farther than a feature's: three of them side by side, just apart.
			int reach = planting.structure == null ? 0
				: planting.structure.reach(planting.structure.maxHeight(planting.scale == null ? WorldScale.ULTRA
				: planting.scale));
			int spacing = reach > 0 ? 2 * reach + 8 : giant ? GIANT_SPACING : SPACING;
			int plot = reach > 0 ? 3 * spacing : giant ? GIANT_PLOT : PLOT;
			int side = reach > 0 ? 64 : giant ? 32 : 16;
			x += (last + plot) / 2;
			last = plot;
			for (int cx = (x - plot / 2) >> 4; cx <= (x + plot / 2) >> 4; cx++) {
				for (int cz = -(side >> 4) - 1; cz <= side >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, 0);
			Holder<Biome> home = homes.get(id);
			// Not where the plot has its biome already, as the plains giants' does: a fill that sets nothing fails.
			if (home != null && !level.getBiome(new BlockPos(x, ground, 0)).is(home)) {
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
				if (planting.place(level, random, new BlockPos(x + k * spacing, ground, 0))) {
					grown++;
				}
				top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + k * spacing, 0));
			}
			LOGGER.info("Tree {}: {} of 3 grew, {} blocks tall at most, in {}", name, grown, top - ground,
				home == null ? "plains" : home.getRegisteredName());
			// Level with the middle of the tallest tree, and far enough back for the field of view to take it in from
			// the ground to the top, and with it each column of the middle tree's crown on the camera's side: those
			// stand closer than the trunk, and the kapoks' flat umbrellas reach out as high as they grow.
			int half = (top - ground) / 2;
			double distance = Math.max(20, (half + 4) / HALF_HEIGHT);
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= 0; dz++) {
					int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, dz);
					if (height > ground) {
						distance = Math.max(distance, -dz + Math.max((height - ground - half + 4) / HALF_HEIGHT,
							(Math.abs(dx) + 4) / HALF_WIDTH));
					}
				}
			}
			plots.add(new Plot(name, x, ground + half, (int) Math.ceil(distance), grown));
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
