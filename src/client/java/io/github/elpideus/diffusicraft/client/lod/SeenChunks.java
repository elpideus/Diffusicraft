package io.github.elpideus.diffusicraft.client.lod;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.lod.LodStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chunks this client has seen of a server that does not share what players built (see {@code LodFeed}), kept as
 * the columns of the far terrain ({@link LodStore}): the far terrain shows them as the client last saw them, and the
 * terrain as generated everywhere else, so that nobody finds from afar what others built. A chunk is kept as the
 * client lets go of it, or of its level, from its highest section down to a little under its lowest ground: enough
 * for its columns, without the depths of a tall world.
 *
 * <p>The stores are kept in {@code diffusicraft/seen} in the game's folder, by server address, world and level. They
 * open when the far terrain or a chunk first needs them, and close once the client has left the server. Everything
 * here runs on the client thread.
 */
public final class SeenChunks {
	/** Sections kept under the one with the lowest ground of a chunk, for the block its deepest runs are made of. */
	private static final int SECTIONS_UNDER = 2;
	/** The most sections a chunk keeps. A chunk whose ground falls further, down a cliff, is left out. */
	private static final int MAX_SECTIONS = 64;

	private static @Nullable Session session;
	/** The level whose chunks the client holds, and those chunks. */
	private static @Nullable ClientLevel tracked;
	private static final Map<Long, LevelChunk> loaded = new HashMap<>();

	private SeenChunks() {
	}

	public static void init() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (level != tracked) {
				letGo(current());
				tracked = level;
			}
			loaded.put(chunk.getPos().pack(), chunk);
		});
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			Session session = current();
			if (level == tracked && loaded.remove(chunk.getPos().pack(), chunk) && session != null) {
				session.keep(level, chunk);
			}
		});
		// The client drops a level without letting go of its chunks one by one: as it leaves a server or a dimension.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Session session = current();
			if (client.level != tracked) {
				letGo(session);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Session left = session;
			session = null;
			letGo(left);
			if (left != null) {
				left.close();
			}
		});
	}

	/**
	 * The store of what this client has seen of a level of the server whose far terrain {@code feed} is, or null if
	 * the server shares what players built, or the client has left it.
	 */
	public static @Nullable LodStore store(RemoteLods.Feed feed, ResourceKey<Level> dimension) {
		Session session = current();
		return session == null || session.feed != feed ? null : session.store(dimension);
	}

	/**
	 * The session of the server the client is on, opened if need be; null if it keeps nothing, as where Distant
	 * Horizons or Voxy, which keep their own, is installed. Closes the session of a server the client left, once it
	 * has kept the chunks the client still held of it.
	 */
	private static @Nullable Session current() {
		RemoteLods.Feed feed = RemoteLods.feed();
		RemoteLods.Feed wanted = FarTerrain.ENABLED && feed != null && !feed.sharesBuilds() ? feed : null;
		if (session != null && session.feed != wanted) {
			Session left = session;
			session = null;
			letGo(left);
			// Writing out what is left may take a moment.
			Thread.ofPlatform().name("Diffusicraft seen chunks").start(left::close);
		}
		if (session == null && wanted != null) {
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			if (connection != null) {
				session = new Session(wanted, folder(connection.getServerData(), wanted.world()),
					connection.registryAccess().lookupOrThrow(Registries.BIOME));
			}
		}
		return session;
	}

	/** Keeps the chunks still held of the level tracked, as the client lets go of them all. */
	private static void letGo(@Nullable Session into) {
		ClientLevel level = tracked;
		if (level != null && into != null) {
			for (LevelChunk chunk : loaded.values()) {
				into.keep(level, chunk);
			}
		}
		loaded.clear();
		tracked = null;
	}

	/** The folder of a server's world: by its address, and the number the server tells its worlds apart by. */
	private static Path folder(@Nullable ServerData server, long world) {
		String address = server == null ? "" : server.ip.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return FabricLoader.getInstance().getGameDir().resolve(Diffusicraft.MOD_ID).resolve("seen")
			.resolve(address.isEmpty() ? "unknown" : address).resolve(HexFormat.of().toHexDigits(world));
	}

	/** What the client keeps of one server's world. */
	private static final class Session {
		private final RemoteLods.Feed feed;
		private final Path folder;
		private final HolderLookup.RegistryLookup<Biome> biomes;
		private final Map<ResourceKey<Level>, LodStore> stores = new HashMap<>();

		Session(RemoteLods.Feed feed, Path folder, HolderLookup.RegistryLookup<Biome> biomes) {
			this.feed = feed;
			this.folder = folder;
			this.biomes = biomes;
		}

		@Nullable LodStore store(ResourceKey<Level> dimension) {
			if (!feed.serves(dimension)) {
				return null;
			}
			return stores.computeIfAbsent(dimension, key -> {
				Identifier id = key.identifier();
				return new LodStore(folder.resolve(id.getNamespace()).resolve(id.getPath()), key, biomes,
					biomes.getOrThrow(Biomes.PLAINS));
			});
		}

		/**
		 * Keeps a chunk the client let go of. Nothing changes its sections any more, so they are read as they are; its
		 * light, which the level still changes, is copied.
		 */
		void keep(ClientLevel level, LevelChunk chunk) {
			LodStore store = store(level.dimension());
			LevelChunkSection[] sections = chunk.getSections();
			int top = chunk.getHighestFilledSectionIndex();
			if (store == null || top < 0) {
				return;
			}
			int lowest = Integer.MAX_VALUE;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					lowest = Math.min(lowest, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
				}
			}
			int ground = chunk.getSectionIndex(Math.max(lowest, chunk.getMinY()));
			if (top - ground >= MAX_SECTIONS) {
				return;
			}
			int bottom = Math.max(0, ground - SECTIONS_UNDER);
			// The heightmap stops at water: on down to the floor of a sea.
			while (bottom > 0 && top - bottom + 1 < MAX_SECTIONS && sections[bottom].hasFluid()) {
				bottom--;
			}
			int minSectionY = chunk.getSectionYFromSectionIndex(bottom);
			DataLayer[] sky = new DataLayer[top - bottom + 1];
			LayerLightEventListener light = level.getLightEngine().getLayerListener(LightLayer.SKY);
			for (int i = 0; i < sky.length; i++) {
				DataLayer layer = light.getDataLayerData(SectionPos.of(chunk.getPos(), minSectionY + i));
				sky[i] = layer == null || layer.isEmpty() ? null : layer.copy();
			}
			store.saved(chunk.getPos(), Arrays.copyOfRange(sections, bottom, top + 1), sky, minSectionY,
				chunk.getMinY());
		}

		/** Writes out what is left and closes the stores. */
		void close() {
			for (LodStore store : List.copyOf(stores.values())) {
				try {
					store.close();
				} catch (IOException | RuntimeException e) {
					Diffusicraft.LOGGER.warn("Could not close the far terrain's seen chunks", e);
				}
			}
		}
	}
}
