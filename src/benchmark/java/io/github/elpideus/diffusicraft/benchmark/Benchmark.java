package io.github.elpideus.diffusicraft.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import io.github.elpideus.diffusicraft.benchmark.RoutePlanner.Point;
import io.github.elpideus.diffusicraft.benchmark.RoutePlanner.Waypoint;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.client.worldgen.WorldSizes;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plays the same route in real time and writes what it cost to {@code benchmark/<label>.json} in the game's folder.
 * Fabric's client game tests cannot measure this: they run the client and the server in lockstep, one tick to a frame,
 * so a slow tick looks like a slow frame and neither runs as it does for a player.
 *
 * <p>It makes a new world, waits at the spawn while the far terrain fills, flies low over the land at a sprint, keeps
 * flying from a teleport to a fresh place, leaves the world and quits, so that the phases cover what players do and the
 * crashes of quitting show up as well. See docs/PERFORMANCE.md for what to run and what to read in the numbers.
 *
 * <p>Options, as {@code -Pdiffusicraft.benchmark.<name>=...}: {@code label}, {@code seed}, {@code size} (a world size,
 * such as {@code medium}), {@code renderDistance}, {@code spawnSeconds}, {@code flightSeconds} and
 * {@code teleportSeconds}.
 */
public final class Benchmark implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft benchmark");
	private static final String LABEL = option("label", "run");
	private static final String SEED = System.getProperty("diffusicraft.seed", "20261052");
	private static final String SIZE = option("size", "");
	private static final int RENDER_DISTANCE = Integer.parseInt(option("renderDistance", "16"));
	private static final int SPAWN_SECONDS = Integer.parseInt(option("spawnSeconds", "45"));
	private static final int FLIGHT_SECONDS = Integer.parseInt(option("flightSeconds", "90"));
	private static final int TELEPORT_SECONDS = Integer.parseInt(option("teleportSeconds", "60"));
	/** How many pictures the flight takes on the way, to look at what a player flying there sees. */
	private static final int FLIGHT_PICTURES = 5;
	/** How long the player hangs still after the flight, at least, for a picture once the view has caught up. */
	private static final int REST_SECONDS = 15;
	/** How long that may take: a view that has not filled by then says so, in the report. */
	private static final int MOST_REST_SECONDS = 60;
	/** The frames after turning round to take a picture, before the renderer can have caught up with what it sees. */
	private static final int TURNED_FRAMES = 2;
	/** How far the teleport goes: well beyond anything the flight has generated. */
	private static final int TELEPORT_BLOCKS = 40_000;
	/** The height over the expected ground to teleport to: clear of it, and near enough to land within the phase. */
	private static final int LANDING_HEIGHT = 150;
	/** The height over the ground to fly at: low, where players are. */
	private static final int FLIGHT_HEIGHT = 30;
	/** How far over the ground the map expects the route stays, in blocks: the most a chunk not here yet can hold. */
	private static final int CRUISE_HEIGHT = 80;
	/**
	 * How fast the flight climbs and sinks, in blocks a tick. The space bar only climbs a third as fast as a sprint
	 * goes, and at 1:1 that runs a player into every hillside, which is no longer a flight at the speed this means to
	 * measure.
	 */
	private static final double MOST_CLIMB = 1.5;
	private static final double MOST_SINK = 0.4;
	/** How far ahead to look at the ground while flying, in blocks. */
	private static final int LOOK_AHEAD = 128;
	/** How fast a player flies at a sprint, in blocks a second. */
	private static final int SPRINT_SPEED = 22;
	/** How near a point of the route counts as reaching it, in blocks. */
	private static final int REACHED_BLOCKS = 32;
	private static final int STUCK_TICKS = 40;
	/** The route takes a few minutes: a run still going after this has hung, and takes the game down with it. */
	private static final int TIMEOUT_SECONDS = Integer.parseInt(option("timeoutSeconds", "1500"));

	private enum Stage { MENUS, CREATING, LOADING, SPAWN, FLIGHT, REST, TELEPORT, LEFT, DONE }

	private final JsonArray phases = new JsonArray();
	/** The points still to fly to. */
	private final ArrayDeque<Waypoint> route = new ArrayDeque<>();
	private List<Waypoint> routeAfterTeleport;
	private Point landing;
	private double landingY;
	private Stage stage = Stage.MENUS;
	private long stageStart;
	private long createStart;
	private double worldLoadSeconds;
	private double routeBlocks;
	private double flightBlocks;
	private int flightPictures;
	private int turnedFrames = -1;
	private double lastX;
	private double lastZ;
	/** When the client got to the landing, or 0 until it has. */
	private long arrived;
	/** How long the player waited at the landing for a chunk under them, or -1 until it came. */
	private double groundSeconds = -1;
	/** How long the far terrain took to fill the view once the flight had stopped, or -1 if it did not. */
	private double farTerrainSeconds = -1;
	/** How far they sank meanwhile, not flying. */
	private double fallBlocks;
	private double stuckX;
	private double stuckY;
	private double stuckZ;
	private int stuckTicks;
	private int waited;

	private static String option(String name, String fallback) {
		return System.getProperty("diffusicraft.benchmark." + name, fallback);
	}

	@Override
	public void onInitializeClient() {
		ServerTickEvents.START_SERVER_TICK.register(server -> Recorder.tickStarted());
		ServerTickEvents.END_SERVER_TICK.register(server -> Recorder.tickEnded());
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		Thread.ofPlatform().daemon().name("Diffusicraft benchmark watchdog").start(() -> {
			try {
				Thread.sleep(TIMEOUT_SECONDS * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			LOGGER.error("The benchmark is still in {} after {} s", stage, TIMEOUT_SECONDS);
			Runtime.getRuntime().halt(3);
		});
	}

	private void tick(Minecraft mc) {
		switch (stage) {
			case MENUS -> openCreateWorld(mc);
			case CREATING -> create(mc);
			case LOADING -> {
				if (mc.gui.screen() instanceof ConfirmScreen confirm) {
					accept(confirm);
				} else if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
					worldLoadSeconds = (System.nanoTime() - createStart) / 1e9;
					LOGGER.info("In the world after {} s", String.format(Locale.ROOT, "%.1f", worldLoadSeconds));
					prepare(mc);
					begin(Stage.SPAWN, "spawn");
				}
			}
			case SPAWN -> {
				hold(mc);
				if (elapsed() >= SPAWN_SECONDS) {
					screenshot(mc, "spawn");
					mc.player.setPos(mc.player.getX(), route.getFirst().y(), mc.player.getZ());
					startRoute(mc.player);
					begin(Stage.FLIGHT, "flight");
				}
			}
			case FLIGHT -> {
				fly(mc);
				if (flightPictures < FLIGHT_PICTURES
					&& elapsed() >= (flightPictures + 1.0) * FLIGHT_SECONDS / (FLIGHT_PICTURES + 1)) {
					flightPictures++;
					screenshot(mc, "flight-" + flightPictures);
					LOGGER.info("At {}: {}", mc.player.blockPosition().toShortString(), FarTerrain.status());
				}
				if (elapsed() >= FLIGHT_SECONDS) {
					flightBlocks = routeBlocks;
					release(mc);
					begin(Stage.REST, "rest");
				}
			}
			case REST -> {
				hold(mc);
				int missing = FarTerrain.missing();
				if (missing == 0 && farTerrainSeconds < 0) {
					farTerrainSeconds = elapsed();
				}
				if (turnedFrames >= 0) {
					if (++turnedFrames == TURNED_FRAMES) {
						screenshot(mc, "turned");
						teleport(mc);
						begin(Stage.TELEPORT, "teleport");
					}
				} else if (elapsed() >= MOST_REST_SECONDS || elapsed() >= REST_SECONDS && missing <= 0) {
					screenshot(mc, "rest");
					LOGGER.info("At {}: {}", mc.player.blockPosition().toShortString(), FarTerrain.status());
					mc.player.setYRot(mc.player.getYRot() + 180);
					mc.player.setOldPosAndRot();
					turnedFrames = 0;
				}
			}
			case TELEPORT -> {
				land(mc);
				if (elapsed() >= TELEPORT_SECONDS) {
					screenshot(mc, "teleport");
					report(mc);
					begin(Stage.LEFT, "leaving");
					release(mc);
					long before = System.nanoTime();
					mc.disconnectFromWorld(Component.literal("Benchmark done"));
					leftSeconds = (System.nanoTime() - before) / 1e9;
				}
			}
			case LEFT -> {
				if (mc.level == null && mc.getSingleplayerServer() == null) {
					end();
					stage = Stage.DONE;
					write(mc);
					LOGGER.info("Benchmark done");
					mc.stop();
				}
			}
			case DONE -> {
			}
		}
	}

	private double leftSeconds;
	/** The way to fly, in radians from east towards south. */
	private double heading;
	private int cruiseY;

	private void openCreateWorld(Minecraft mc) {
		Screen shown = mc.gui.screen();
		if (shown != null && mc.gui.overlay() == null && !(shown instanceof TitleScreen)) {
			// A new run directory opens on the game's first-launch screens.
			LOGGER.info("Skipping {}", shown.getClass().getSimpleName());
			mc.gui.setScreen(new TitleScreen());
		} else if (shown instanceof TitleScreen && Resources.get().status() != null) {
			if (!Resources.get().status().modelsReady()) {
				throw new IllegalStateException("The benchmark needs the Terrain Diffusion models");
			}
			mc.options.pauseOnLostFocus = false;
			mc.options.onboardAccessibility = false;
			mc.options.tutorialStep = TutorialSteps.NONE;
			mc.getTutorial().setStep(TutorialSteps.NONE);
			// An unattended game counts as away from the keyboard, which limits it to 30 frames a second.
			mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(260);
			mc.options.renderDistance().set(RENDER_DISTANCE);
			mc.options.simulationDistance().set(8);
			CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen()));
			createStart = System.nanoTime();
			stage = Stage.CREATING;
		}
	}

	private void create(Minecraft mc) {
		if (!(mc.gui.screen() instanceof CreateWorldScreen screen)) {
			return;
		}
		WorldCreationUiState ui = screen.getUiState();
		ui.setName("benchmark");
		ui.setSeed(SEED);
		ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
		if (!SIZE.isEmpty()) {
			WorldSizes.select(ui, WorldScale.valueOf(SIZE.toUpperCase(Locale.ROOT)));
		}
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractButton button && hasKey(button.getMessage(), "selectWorld.create")) {
				LOGGER.info("Creating a world of size {} on seed {}", WorldSizes.selected(ui), SEED);
				button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				stage = Stage.LOADING;
				return;
			}
		}
		// The button is not there yet on the first tick of the screen.
		if (++waited > 100) {
			throw new IllegalStateException("Create World has no Create button");
		}
	}

	/** Answers the questions Create can ask, such as the warning about a far terrain mod or experimental features. */
	private void accept(ConfirmScreen confirm) {
		for (GuiEventListener child : confirm.children()) {
			if (child instanceof AbstractButton button && !hasKey(button.getMessage(), "gui.back")
				&& !hasKey(button.getMessage(), "gui.no") && !hasKey(button.getMessage(), "gui.cancel")) {
				LOGGER.info("Answering {}: {}", confirm.getTitle().getString(), button.getMessage().getString());
				button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				return;
			}
		}
	}

	private static boolean hasKey(Component text, String key) {
		return text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key);
	}

	/** Puts the game in the state the rest of the route assumes. */
	private void prepare(Minecraft mc) {
		LocalPlayer player = mc.player;
		player.getAbilities().flying = true;
		startRoute(player);
		RoutePlanner planner = new RoutePlanner(mc.getSingleplayerServer().overworld());
		RoutePlanner unseen = planner.coarse();
		Point spawn = new Point(player.getX(), player.getZ());
		List<Point> flight = planner.route(spawn, FLIGHT_SECONDS * SPRINT_SPEED);
		landing = unseen.landing(spawn, TELEPORT_BLOCKS);
		landingY = Math.min(mc.level.getMaxY() - 8, unseen.surfaceY(landing) + LANDING_HEIGHT);
		List<Point> afterTeleport = unseen.route(landing, TELEPORT_SECONDS * SPRINT_SPEED);
		route.addAll(planner.cruise(flight, CRUISE_HEIGHT));
		routeAfterTeleport = unseen.cruise(afterTeleport, CRUISE_HEIGHT);
		LOGGER.info("Route of {} points, {} on ordinary land, then {}, {} on ordinary land, from {}", flight.size(),
			planner.lowLand(flight), afterTeleport.size(), unseen.lowLand(afterTeleport), landing);
	}

	private void startRoute(LocalPlayer player) {
		lastX = player.getX();
		lastZ = player.getZ();
		stuckX = lastX;
		stuckY = player.getY();
		stuckZ = lastZ;
		stuckTicks = 0;
	}

	/** Stays in the air at the spawn, so that nothing but the terrain loads. */
	private void hold(Minecraft mc) {
		mc.player.getAbilities().flying = true;
	}

	/** Flies at a sprint along the route by pressing the player's keys, staying over the ground ahead. */
	private void fly(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		player.getAbilities().flying = true;
		while (!route.isEmpty() && Math.hypot(route.peek().point().x() - player.getX(),
			route.peek().point().z() - player.getZ()) < REACHED_BLOCKS) {
			route.poll();
		}
		if (!route.isEmpty()) {
			heading = Math.atan2(route.peek().point().z() - player.getZ(), route.peek().point().x() - player.getX());
			cruiseY = route.peek().y();
		}
		player.setYRot((float) Math.toDegrees(heading) - 90);
		player.setXRot(0);
		mc.options.keyUp.setDown(true);
		mc.options.keySprint.setDown(true);
		int ground = cruiseY - FLIGHT_HEIGHT;
		for (int ahead = 0; ahead <= LOOK_AHEAD; ahead += 16) {
			ground = Math.max(ground, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING,
				(int) (player.getX() + ahead * Math.cos(heading)), (int) (player.getZ() + ahead * Math.sin(heading))));
		}
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x, Math.clamp(ground + FLIGHT_HEIGHT - player.getY(), -MOST_SINK, MOST_CLIMB),
			motion.z);

		routeBlocks += Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
		lastX = player.getX();
		lastZ = player.getZ();
		if (Math.hypot(player.getX() - stuckX, player.getZ() - stuckZ) + Math.abs(player.getY() - stuckY) > 1) {
			stuckX = player.getX();
			stuckY = player.getY();
			stuckZ = player.getZ();
			stuckTicks = 0;
		} else if (++stuckTicks >= STUCK_TICKS) {
			// Wedged under an overhang, not waiting for chunks: out into the open, over whatever is ahead.
			stuckTicks = 0;
			player.setPos(player.getX(), ground + FLIGHT_HEIGHT, player.getZ());
		}
	}

	private void release(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	/** Teleports to the land planned for it. The server moves the player when it gets there, so {@link #land} waits. */
	private void teleport(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		release(mc);
		route.clear();
		route.addAll(routeAfterTeleport);
		LOGGER.info("Teleporting {} blocks, to {}", Math.round(Math.hypot(landing.x() - mc.player.getX(),
			landing.z() - mc.player.getZ())), landing);
		float yaw = mc.player.getYRot();
		server.execute(() -> server.getPlayerList().getPlayers().getFirst()
			.teleportTo(server.overworld(), landing.x(), landingY, landing.z(), Set.of(), yaw, 0, true));
	}

	/**
	 * Waits for the client to arrive and then, not flying, for the chunk under it, which shows whether the player stays
	 * where they are until the ground comes. From there the flight goes on.
	 */
	private void land(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (groundSeconds >= 0) {
			fly(mc);
		} else if (arrived == 0) {
			if (Math.hypot(player.getX() - landing.x(), player.getZ() - landing.z()) < REACHED_BLOCKS) {
				arrived = System.nanoTime();
				player.getAbilities().flying = false;
			}
		} else if (mc.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(player.getBlockX()),
			SectionPos.blockToSectionCoord(player.getBlockZ()))) {
			groundSeconds = (System.nanoTime() - arrived) / 1e9;
			fallBlocks = landingY - player.getY();
			LOGGER.info("The first chunk under the player came after {} s, and they sank {} blocks",
				String.format(Locale.ROOT, "%.1f", groundSeconds), String.format(Locale.ROOT, "%.1f", fallBlocks));
			startRoute(player);
		}
	}

	private void screenshot(Minecraft mc, String name) {
		File folder = FabricLoader.getInstance().getGameDir().toFile();
		Screenshot.grab(folder, LABEL + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
		});
	}

	/** What the world itself says about how the route went, while it is still open. */
	private void report(Minecraft mc) {
		LOGGER.info("Flew {} blocks, then {} from the landing, ending at {}", Math.round(flightBlocks),
			Math.round(routeBlocks - flightBlocks), mc.player.blockPosition().toShortString());
	}

	private double elapsed() {
		return (System.nanoTime() - stageStart) / 1e9;
	}

	private void begin(Stage next, String phase) {
		end();
		Recorder.start(phase);
		stage = next;
		stageStart = System.nanoTime();
	}

	private void end() {
		Recorder last = Recorder.start(null);
		if (last != null) {
			JsonObject report = last.report();
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				report.addProperty("loadedChunks", level.getChunkSource().getLoadedChunksCount());
			}
			phases.add(report);
			LOGGER.info("{}: {}", report.get("phase").getAsString(), report.get("frames"));
			LOGGER.info("{} ticks: {}", report.get("phase").getAsString(), report.get("ticks"));
		}
	}

	private void write(Minecraft mc) {
		JsonObject run = new JsonObject();
		run.addProperty("label", LABEL);
		run.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		run.addProperty("backend", device.backendName() + " on " + device.name());
		run.addProperty("mods", FabricLoader.getInstance().getAllMods().size());
		run.addProperty("seed", SEED);
		run.addProperty("size", SIZE);
		run.addProperty("renderDistance", RENDER_DISTANCE);
		run.addProperty("worldLoadSeconds", worldLoadSeconds);
		run.addProperty("flightBlocks", Math.round(flightBlocks));
		run.addProperty("flightBlocksPerSecond", Math.round(flightBlocks / FLIGHT_SECONDS));
		run.addProperty("landingBlocks", Math.round(routeBlocks - flightBlocks));
		run.addProperty("groundSeconds", groundSeconds);
		run.addProperty("farTerrainSeconds", farTerrainSeconds);
		run.addProperty("fallBlocks", fallBlocks);
		run.addProperty("leaveSeconds", leftSeconds);
		run.add("phases", phases);
		Path file = FabricLoader.getInstance().getGameDir().resolve("benchmark").resolve(LABEL + ".json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(run));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		LOGGER.info("Wrote {}", file);
	}
}
