package io.github.elpideus.diffusicraft.client.compat.voxy;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.lod.LodGrid;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terrain of one of Voxy's worlds: fills the sections Voxy's store does not hold from Diffusicraft's terrain, as
 * {@link LodGrid} cuts them, so that Voxy draws the far terrain without the chunks having been generated. Only in
 * singleplayer, where the level's generator is at hand; elsewhere Voxy shows the chunks it has seen, as it does
 * without Diffusicraft.
 *
 * <p>The sections filled are not saved: Voxy saves a section only once the chunks it loads change it, and they then
 * replace what was filled where they lie. Voxy finds the sections to draw by walking down from its coarsest ones,
 * through the children each says hold blocks; a section claims every child under its blocks and one voxel round them,
 * and the parents of a section that turns out empty, or not, are put right as it is filled.
 */
public final class VoxyTerrain {
	/** Grids kept for the sections above and below them, which share their columns. */
	private static final int CACHED_GRIDS = 128;
	private static final int SIDE = 32;

	private final ResourceKey<Level> level;
	/** Voxy's ids of the blocks in each biome, without their light. */
	private final Map<Holder<Biome>, Map<BlockState, Long>> ids = new ConcurrentHashMap<>();
	private final AtomicBoolean warned = new AtomicBoolean();
	private volatile @Nullable Source source;

	VoxyTerrain(ResourceKey<Level> level) {
		this.level = level;
	}

	/** Called when Voxy's store did not hold a section. The status Voxy's store reports for it. */
	int load(WorldEngine engine, WorldSection section) {
		int extent = SIDE << section.lvl;
		int minY = section.y * extent;
		if (minY > VoxyHeights.MAX_Y || minY + extent <= VoxyHeights.MIN_Y) {
			return VoxyLods.MISSING;
		}
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || !server.isRunning()) {
			return VoxyLods.MISSING;
		}
		Source source = source(server);
		if (source.sampler == null) {
			return VoxyLods.MISSING;
		}
		boolean filled;
		try {
			filled = fill(engine, source, section, minY);
		} catch (RuntimeException e) {
			// Most often the world closing under Voxy's threads.
			if (warned.compareAndSet(false, true)) {
				Diffusicraft.LOGGER.warn("Could not fill Voxy's far terrain", e);
			} else {
				Diffusicraft.LOGGER.debug("Could not fill Voxy's far terrain", e);
			}
			return VoxyLods.MISSING;
		}
		repairParents(engine, section);
		return filled ? VoxyLods.LOADED : VoxyLods.MISSING;
	}

	private Source source(MinecraftServer server) {
		Source source = this.source;
		if (source == null || source.server != server) {
			synchronized (this) {
				source = this.source;
				if (source == null || source.server != server) {
					ServerLevel serverLevel = server.getLevel(level);
					source = new Source(server, serverLevel == null ? null : LodSampler.of(serverLevel));
					this.source = source;
				}
			}
		}
		return source;
	}

	/** Writes the section's voxels. Whether any of them holds a block. */
	private boolean fill(WorldEngine engine, Source source, WorldSection section, int minY) {
		LodSampler sampler = source.sampler;
		int size = 1 << section.lvl;
		if (minY + (SIDE << section.lvl) <= Math.max(sampler.minY(), VoxyHeights.MIN_Y)) {
			return false;
		}
		LodGrid grid = source.grid(section.lvl, section.x, section.z);
		if (minY >= grid.highest()) {
			return false;
		}
		Mapper mapper = engine.getMapper();
		long[] data = section._unsafeGetRawDataArray();
		BlockState[] states = new BlockState[SIDE];
		byte[] sky = new byte[SIDE];
		int blocks = 0;
		int claims = 0;
		for (int z = 0; z < SIDE; z++) {
			for (int x = 0; x < SIDE; x++) {
				int column = z * SIDE + x;
				grid.voxels(column, minY, size, states, sky);
				Holder<Biome> biome = grid.biome(column);
				Map<BlockState, Long> biomeIds = ids.computeIfAbsent(biome, b -> new ConcurrentHashMap<>());
				for (int y = 0; y < SIDE; y++) {
					BlockState state = states[y];
					long id;
					if (state == null) {
						id = Mapper.airWithLight(sky[y]);
					} else {
						Long base = biomeIds.get(state);
						if (base == null) {
							// Two threads may both ask Voxy; they get the same id.
							base = mapper.getBaseId((byte) 0, state, biome);
							biomeIds.put(state, base);
						}
						id = Mapper.withLight(base, sky[y] | state.getLightEmission() << 4);
						blocks++;
						if (claims != 0xFF) {
							claims |= claims(x, y, z);
						}
					}
					data[WorldSection.getIndex(x, y, z)] = id;
				}
			}
		}
		if (blocks == 0) {
			return false;
		}
		if (section.lvl == 0) {
			section.addNonEmptyBlockCount(blocks);
			section.updateLvl0State();
		} else {
			section._unsafeSetNonEmptyChildren((byte) claims);
		}
		return true;
	}

	/**
	 * The children a block at voxel {@code (x, y, z)} claims: its own, and those of the voxels above it and beside it,
	 * where the finer columns of a child may stand a little higher or wider than the coarse ones.
	 */
	static int claims(int x, int y, int z) {
		int claims = child(x, y, z) | child(x, Math.min(SIDE - 1, y + 1), z);
		claims |= child(Math.max(0, x - 1), y, z) | child(Math.min(SIDE - 1, x + 1), y, z);
		claims |= child(x, y, Math.max(0, z - 1)) | child(x, y, Math.min(SIDE - 1, z + 1));
		return claims;
	}

	/** The bit, among a section's children, of the child holding voxel {@code (x, y, z)}. */
	private static int child(int x, int y, int z) {
		// The same order as WorldSection.getChildIndex.
		return 1 << ((x >> 4) | (y >> 4) << 2 | (z >> 4) << 1);
	}

	/**
	 * Tells the parents of a section the store did not hold whether it holds blocks, as far up as that changes
	 * whether they do. Without this, Voxy's renderer would go down into the children a parent claimed that are empty,
	 * and never into one it did not claim. The change is not saved.
	 */
	private static void repairParents(WorldEngine engine, WorldSection section) {
		WorldSection child = section;
		boolean acquired = false;
		while (child.lvl < WorldEngine.MAX_LOD_LAYER) {
			WorldSection parent = engine.acquire(child.lvl + 1, child.x >> 1, child.y >> 1, child.z >> 1);
			int change = parent.updateEmptyChildState(child);
			if (change != 0 && engine.isLive()) {
				engine.markDirty(parent, WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE,
					0);
			}
			if (acquired) {
				child.release();
			}
			child = parent;
			acquired = true;
			// Only a parent that went from holding blocks to not, or back, changes its own parent.
			if (change != 2) {
				break;
			}
		}
		if (acquired) {
			child.release();
		}
	}

	/** Diffusicraft's additions to Voxy's {@code WorldEngine}. */
	public interface Carrier {
		@Nullable VoxyTerrain diffusicraft$terrain();

		void diffusicraft$setTerrain(VoxyTerrain terrain);
	}

	/** The level's sampler in one run of the integrated server, and the grids sampled from it. */
	private static final class Source {
		private final MinecraftServer server;
		private final @Nullable LodSampler sampler;
		private final Map<GridKey, CompletableFuture<LodGrid>> grids = new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<GridKey, CompletableFuture<LodGrid>> eldest) {
				return size() > CACHED_GRIDS;
			}
		};

		Source(MinecraftServer server, @Nullable LodSampler sampler) {
			this.server = server;
			this.sampler = sampler;
		}

		/** The columns under the sections at a level and horizontal position, sampled once for all of them. */
		LodGrid grid(int lvl, int x, int z) {
			GridKey key = new GridKey(lvl, x, z);
			CompletableFuture<LodGrid> future;
			boolean mine = false;
			synchronized (grids) {
				future = grids.get(key);
				if (future == null) {
					future = new CompletableFuture<>();
					grids.put(key, future);
					mine = true;
				}
			}
			if (mine) {
				try {
					int extent = SIDE << lvl;
					future.complete(LodGrid.sample(sampler, x * extent, z * extent, SIDE, 1 << lvl,
						VoxyHeights.MAX_Y + 1));
				} catch (RuntimeException | Error e) {
					synchronized (grids) {
						grids.remove(key, future);
					}
					future.completeExceptionally(e);
					throw e;
				}
			}
			return future.join();
		}
	}

	private record GridKey(int lvl, int x, int z) {
	}
}
