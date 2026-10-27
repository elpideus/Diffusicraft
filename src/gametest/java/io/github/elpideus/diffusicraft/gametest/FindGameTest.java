package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.Sights;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /diffusicraft find}. Looks for every sight from the spawn, as the command does, then generates the chunks
 * at each place found and checks that a player can stand there: open space for the feet and the head, and the
 * cavern's floor just under them, or a shallow pool on it, or water or lava where the search says so, in the biome
 * looked for. Saves a view of each as {@code diffusicraft-world-find-<sight>}. Fails if a kind of hollow is not found
 * or a place is not where a player can stand.
 *
 * <p>Runs only when {@code diffusicraft.gametests} names it, since each place is new land to generate. Needs the
 * Terrain Diffusion models.
 */
public class FindGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft find test");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final int RENDER_DISTANCE = 4;
	/** Blocks under the place within which the floor, the water or the lava must lie. */
	private static final int DROP = 4;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!GameTests.named(this)) {
			return;
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(mc -> Resources.get().status() != null, 1200);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the find test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> {
			config.device = device;
			config.cpuThreads = Integer.getInteger("diffusicraft.cpuThreads", config.cpuThreads);
		});
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				WorldSizes.select(ui, WorldGenerationGameTest.SIZE);
			})
			.create()) {
			world.getServer().runCommand("diffusicraft find");
			world.getServer().runCommand("diffusicraft find hollow");
			Map<Sights.Sight, Sights.Found> found = world.getServer().computeOnServer(server ->
				search(server.overworld()));
			List<String> problems = new ArrayList<>();
			for (Sights.Sight sight : Sights.Sight.values()) {
				if (sight.kind() != null && !found.containsKey(sight)) {
					problems.add("no " + sight.id());
				}
			}
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("effect give @a minecraft:night_vision infinite 0 true");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			for (Sights.Found sight : found.values()) {
				String problem = world.getServer().computeOnServer(server -> check(server.overworld(), sight));
				if (problem != null) {
					problems.add(sight.sight().id() + " at " + sight.pos().toShortString() + ": " + problem);
				}
				Hollows.Chamber chamber = sight.chamber();
				double dx = chamber.x() - sight.pos().getX(), dz = chamber.z() - sight.pos().getZ();
				// Towards the middle of the cavern, or down at the water or the lava from over the middle.
				boolean middle = Math.hypot(dx, dz) < 8;
				float yaw = middle ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
				WorldGenerationGameTest.view(context, world, sight.pos(), yaw, middle ? 45f : 10f,
					"find-" + sight.sight().id());
			}
			if (!problems.isEmpty()) {
				throw new AssertionError("The finder's places are wrong: " + String.join("; ", problems));
			}
		}
	}

	/** Every sight nearest the spawn, timed. */
	private static Map<Sights.Sight, Sights.Found> search(ServerLevel level) {
		Sights sights = Sights.of(level);
		if (sights == null) {
			throw new AssertionError("Diffusicraft does not generate the overworld");
		}
		BlockPos from = level.getRespawnData().pos();
		Map<Sights.Sight, Sights.Found> found = new EnumMap<>(Sights.Sight.class);
		for (Sights.Sight sight : Sights.Sight.values()) {
			long start = System.nanoTime();
			Sights.Found place = sights.nearest(sight, from.getX(), from.getZ());
			long millis = (System.nanoTime() - start) / 1_000_000;
			if (place == null) {
				LOGGER.info("No {} within {} blocks ({} ms)", sight.id(), sights.radius(sight), millis);
				continue;
			}
			LOGGER.info("{} at {}, {} blocks away, on its {}: a {} in {} ({} ms)", sight.id(), place.pos().toShortString(),
				(int) Math.hypot(place.pos().getX() - from.getX(), place.pos().getZ() - from.getZ()),
				place.footing().name().toLowerCase(Locale.ROOT), place.chamber().kind(), place.biome(), millis);
			found.put(sight, place);
		}
		return found;
	}

	/** Generates the place's chunk and says what keeps a player from standing there, or {@code null}. */
	private static String check(ServerLevel level, Sights.Found found) {
		BlockPos pos = found.pos();
		level.getChunk(pos);
		for (BlockPos at : new BlockPos[] {pos, pos.above()}) {
			BlockState state = level.getBlockState(at);
			if (!state.getCollisionShape(level, at).isEmpty() || !state.getFluidState().isEmpty()) {
				return "not open at y = " + at.getY() + " (" + state + ")";
			}
		}
		if (found.sight().biome() != null
			&& !level.getBiome(pos).is(Identifier.parse(found.sight().biome()))) {
			return "in " + level.getBiome(pos).getRegisteredName();
		}
		for (int y = 1; y <= DROP; y++) {
			BlockPos at = pos.below(y);
			BlockState state = level.getBlockState(at);
			boolean water = state.getFluidState().is(FluidTags.WATER);
			boolean lava = state.getFluidState().is(FluidTags.LAVA);
			boolean solid = !state.getCollisionShape(level, at).isEmpty();
			// The search sees the terrain before the features, which dig pools in some floors.
			if (!water && !lava && !solid || water && found.footing() == Sights.Footing.FLOOR) {
				continue;
			}
			return switch (found.footing()) {
				case FLOOR -> lava ? "lava under it at y = " + at.getY() : null;
				// The cold may freeze its top.
				case LAKE -> water || state.is(BlockTags.ICE) ? null
					: "no lake under it, but " + state + " at y = " + at.getY();
				case LAVA -> lava ? null : "no lava sea under it, but " + state + " at y = " + at.getY();
			};
		}
		return "nothing within " + DROP + " blocks under it";
	}
}
