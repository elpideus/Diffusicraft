package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells a {@link TerrainPrefetcher} where the players of Diffusicraft worlds are and where they are heading, twice a
 * second, while {@code generateAhead} is on. Everything here runs on the server thread.
 */
final class PlayerPrefetch {
	/** Ticks between plans. */
	private static final int INTERVAL = 10;
	/** Blocks beyond a player's view distance to have ready, in every direction. */
	private static final double MARGIN = 64;
	/** A move longer than this between two plans, in blocks, is a teleport and says nothing of where a player goes. */
	private static final double TELEPORT = 200;

	private static final Map<UUID, Motion> MOTIONS = new HashMap<>();
	private static TerrainPrefetcher prefetcher;

	private PlayerPrefetch() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Resources.get().config().generateAhead) {
				prefetcher = new TerrainPrefetcher();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (prefetcher != null) {
				prefetcher.close();
				prefetcher = null;
			}
			MOTIONS.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(PlayerPrefetch::tick);
	}

	private static void tick(MinecraftServer server) {
		if (prefetcher == null || server.getTickCount() % INTERVAL != 0) {
			return;
		}
		List<TerrainPrefetcher.Traveller> travellers = new ArrayList<>();
		Set<UUID> online = new HashSet<>();
		int serverView = server.getPlayerList().getViewDistance();
		for (ServerLevel level : server.getAllLevels()) {
			BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getChunkSource().randomState())
				.diffusicraft$biomeWorld();
			if (world == null) {
				continue;
			}
			for (ServerPlayer player : level.players()) {
				online.add(player.getUUID());
				Motion motion = MOTIONS.computeIfAbsent(player.getUUID(), id -> new Motion());
				motion.update(level.dimension(), player.getX(), player.getZ(), server.getTickCount());
				// The server generates chunks out to the view distance the client asked for, within its own.
				int view = Mth.clamp(player.requestedViewDistance(), 2, serverView);
				travellers.add(new TerrainPrefetcher.Traveller(world.field(), world.scale(), player.getX(),
					player.getZ(), motion.vx, motion.vz, (view + 1) * 16 + MARGIN));
			}
		}
		MOTIONS.keySet().retainAll(online);
		prefetcher.update(travellers);
	}

	/** A player's position at the last plan and its velocity, smoothed over the last few plans. */
	private static final class Motion {
		ResourceKey<Level> level;
		double x;
		double z;
		int tick;
		double vx;
		double vz;

		void update(ResourceKey<Level> level, double x, double z, int tick) {
			double seconds = (tick - this.tick) / 20.0;
			double dx = x - this.x, dz = z - this.z;
			if (level != this.level || seconds <= 0 || Math.hypot(dx, dz) > TELEPORT) {
				vx = 0;
				vz = 0;
			} else {
				vx = (vx + dx / seconds) / 2;
				vz = (vz + dz / seconds) / 2;
			}
			this.level = level;
			this.x = x;
			this.z = z;
			this.tick = tick;
		}
	}
}
