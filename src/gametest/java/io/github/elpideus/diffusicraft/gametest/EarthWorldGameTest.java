package io.github.elpideus.diffusicraft.gametest;

import io.github.elpideus.diffusicraft.height.SharedContainerData;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Creates a Diffusicraft Earth 1:1 world and goes to the highest ground near the spawn, which stands at its real
 * height: a block per meter up to 8 km. Chunks fill only up to their ground (see {@code TerrainCeiling}), so the test
 * checks that the chunks there hold all of it: their ground against the levels of detail, which come from the terrain
 * directly, and the summit against the highest ground the terrain gives. Saves views of the summit to
 * {@code build/run/clientGameTest/screenshots} and logs how long their chunks took and what they cost in memory, and
 * checks that the sections stored with fewer bits per block than vanilla's survive saving and sending.
 *
 * <p>Needs the Terrain Diffusion models, like {@link WorldGenerationGameTest}.
 */
public class EarthWorldGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft Earth test");
	private static final String SEED = "20261018";
	private static final int TICKS_PER_MINUTE = 20 * 60;
	/** Chunks are some 30 times taller here than in the standard world. */
	private static final int RENDER_DISTANCE = 12;
	/** How far from the spawn the highest coarse pixel is looked for, in coarse pixels: 7.68 km each at 1:1. */
	private static final int SEARCH = 16;
	/** Blocks between the samples of the full-resolution search around it. */
	private static final int STRIDE = 32;
	/** Columns per side of the square around the summit whose chunks are compared with the terrain. */
	private static final int CHECK = 64;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitFor(mc -> Resources.get().status() != null, TICKS_PER_MINUTE);
		if (!Resources.get().status().modelsReady()) {
			LOGGER.warn("The Terrain Diffusion models are not downloaded; skipping the Earth 1:1 test");
			return;
		}
		context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
		OnnxModel.Device device = OnnxModel.Device.valueOf(System.getProperty("diffusicraft.device", "CPU"));
		Resources.get().updateConfig(config -> config.device = device);

		long start = System.nanoTime();
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(ui -> {
				ui.setSeed(SEED);
				ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				ui.setWorldType(Stream.concat(ui.getNormalPresetList().stream(), ui.getAltPresetList().stream())
					.filter(entry -> entry.preset() != null && entry.preset().is(DiffusicraftWorldgen.EARTH_PRESET))
					.findFirst().orElseThrow(() -> new AssertionError("No Earth 1:1 world type")));
			})
			.create()) {
			LOGGER.info("Earth 1:1 world loaded in {} s", (System.nanoTime() - start) / 1_000_000_000L);
			WorldGenerationGameTest.Probe probe = world.getServer()
				.computeOnServer(server -> WorldGenerationGameTest.Probe.of(server.overworld()));
			start = System.nanoTime();
			BlockPos summit = findSummit(probe);
			int summitY = WorldGenerationGameTest.surface(probe, summit);
			LOGGER.info("Highest ground within {} km of the spawn {}: y = {} at {}, {}, {} m above the sea; found in {} s",
				Math.round(SEARCH * 7.68), probe.spawn(), summitY, summit.getX(), summit.getZ(),
				summitY - WorldScale.EARTH.seaLevel(), (System.nanoTime() - start) / 1_000_000_000L);

			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamemode spectator @a");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			// From the south, a little above the summit, and from above it, within the render distance.
			WorldGenerationGameTest.view(context, world, summit.offset(0, 0, 160).atY(summitY + 60), 180f, 20f,
				"earth-summit");
			WorldGenerationGameTest.view(context, world, summit.offset(0, 0, 40).atY(summitY + 140), 180f, 70f,
				"earth-summit-above");
			String problem = checkGround(world, probe, summit, summitY);
			logCost(world, summit);
			problem = problem != null ? problem : checkNarrowSections(world, summit);
			if (problem != null) {
				throw new AssertionError(problem);
			}
		}
	}

	/**
	 * Logs what the chunks around the summit cost: the heap, and their sections by kind. A section whose blocks share
	 * their data costs some 80 bytes; any other also holds its own block storage. Mixed sections are counted by how
	 * many blocks they hold: 2, 3 or 4, 5 to 16, and more; up to 8 they take fewer than vanilla's 4 bits per block
	 * (see {@code NarrowLinearConfiguration}).
	 */
	private static void logCost(TestSingleplayerContext world, BlockPos summit) {
		long[] counts = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			// Chunks, shared sections, single-block sections, the four kinds of mixed ones, block storage in bytes, and
			// the sections stored narrower than vanilla's 4 bits per block.
			long[] totals = new long[9];
			int[] values = new int[1];
			for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
				for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow((summit.getX() >> 4) + dx,
						(summit.getZ() >> 4) + dz);
					if (chunk == null) {
						continue;
					}
					totals[0]++;
					for (LevelChunkSection section : chunk.getSections()) {
						PalettedContainer<BlockState> states = section.getStates();
						if (((SharedContainerData.Container) states).diffusicraft$isShared()) {
							totals[1]++;
							continue;
						}
						values[0] = 0;
						states.count((state, count) -> values[0]++);
						int n = values[0];
						totals[n == 1 ? 2 : n == 2 ? 3 : n <= 4 ? 4 : n <= 16 ? 5 : 6]++;
						totals[7] += states.bitsPerEntry() * 4096L / 8;
						totals[8] += states.bitsPerEntry() < 4 && n > 1 ? 1 : 0;
					}
				}
			}
			return totals;
		});
		LOGGER.info("{} chunks around the summit: {} sections with shared blocks, {} of a single block without, {} "
				+ "mixed ({} / {} / {} / {}), {} of them narrower than 4 bits; {} MiB of block storage; heap {} MiB",
			counts[0], counts[1], counts[2], counts[3] + counts[4] + counts[5] + counts[6], counts[3], counts[4],
			counts[5], counts[6], counts[8], counts[7] >> 20, TallDimensionGameTest.usedHeapMiB());
	}

	/**
	 * Saves and sends sections stored narrower than 4 bits per block and reads them back, as the game does: saved at
	 * 4 bits like vanilla, then narrowed again to the blocks the section holds; sent as they are. The whole save would
	 * take long, so a few hundred.
	 *
	 * @return what is wrong with them, or {@code null}
	 */
	private static @Nullable String checkNarrowSections(TestSingleplayerContext world, BlockPos summit) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			PalettedContainerFactory factory = level.palettedContainerFactory();
			Strategy<BlockState> strategy = factory.blockStatesStrategy();
			int checked = 0;
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow((summit.getX() >> 4) + dx,
						(summit.getZ() >> 4) + dz);
					if (chunk == null) {
						continue;
					}
					for (LevelChunkSection section : chunk.getSections()) {
						PalettedContainer<BlockState> states = section.getStates();
						int bits = states.bitsPerEntry();
						if (bits == 0 || bits >= 4) {
							continue;
						}
						PalettedContainerRO.PackedData<BlockState> packed = states.pack(strategy);
						if (packed.bitsPerEntry() != 4) {
							return "A section of " + bits + " bits per block was saved with " + packed.bitsPerEntry();
						}
						PalettedContainer<BlockState> loaded = PalettedContainer.unpack(strategy, packed).getOrThrow();
						FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
						states.write(buffer);
						if (buffer.readableBytes() != states.getSerializedSize()) {
							return "A section of " + bits + " bits per block was sent in " + buffer.readableBytes()
								+ " bytes, not " + states.getSerializedSize();
						}
						PalettedContainer<BlockState> received = factory.createForBlockStates();
						received.read(buffer);
						// Saving leaves out the blocks the section no longer holds, so it may load narrower.
						int loadedBits = Mth.ceillog2(packed.paletteEntries().size());
						if (loaded.bitsPerEntry() != loadedBits || received.bitsPerEntry() != bits) {
							return "A section of " + bits + " bits per block came back with " + loaded.bitsPerEntry()
								+ " from a save of " + packed.paletteEntries().size() + " blocks and "
								+ received.bitsPerEntry() + " from a packet";
						}
						for (PalettedContainer<BlockState> copy : List.of(loaded, received)) {
							for (int i = 0; i < 4096; i++) {
								if (copy.get(i & 15, i >> 8, (i >> 4) & 15) != states.get(i & 15, i >> 8, (i >> 4) & 15)) {
									return "A section of " + bits + " bits per block came back with other blocks";
								}
							}
						}
						checked++;
					}
				}
			}
			LOGGER.info("{} sections narrower than 4 bits per block survived saving and sending", checked);
			return checked == 0 ? "No section around the summit is narrower than 4 bits per block" : null;
		});
	}

	/** The highest ground near the spawn: the highest coarse pixel first, then the highest block inside it. */
	private static BlockPos findSummit(WorldGenerationGameTest.Probe probe) {
		WorldScale scale = WorldScale.EARTH;
		TerrainField field = probe.field();
		int coarse = field.coarseScale();
		double i0 = scale.toPixel(probe.spawn().getZ()), j0 = scale.toPixel(probe.spawn().getX());
		int[] best = IntStream.rangeClosed(-SEARCH, SEARCH).boxed()
			.flatMap(di -> IntStream.rangeClosed(-SEARCH, SEARCH).mapToObj(dj -> new int[]{di, dj}))
			.parallel()
			.max(Comparator.comparingDouble(d -> field.coarseElevation(i0 + d[0] * coarse, j0 + d[1] * coarse)))
			.orElseThrow();
		int blocks = (int) Math.round(coarse / scale.pixelsPerBlock());
		BlockPos centre = probe.spawn().offset(best[1] * blocks, 0, best[0] * blocks);
		BlockPos highest = highest(probe, centre, blocks / 2, STRIDE);
		return highest(probe, highest, STRIDE, 1);
	}

	/** The highest of the columns {@code step} blocks apart within {@code radius} of {@code centre}. */
	private static BlockPos highest(WorldGenerationGameTest.Probe probe, BlockPos centre, int radius, int step) {
		int side = 2 * radius / step + 1;
		return IntStream.range(0, side * side).parallel()
			.mapToObj(k -> centre.offset((k % side) * step - radius, 0, (k / side) * step - radius))
			.max(Comparator.comparingDouble(at -> probe.state().sampleBlockValueUncached(
				probe.router().chunkSurfaceLevel(), at.getX(), 0, at.getZ())))
			.orElseThrow();
	}

	/**
	 * Compares the ground of the chunks around the summit with the levels of detail, and the highest block there with
	 * the summit.
	 *
	 * @return what is wrong with them, or {@code null}
	 */
	private static @Nullable String checkGround(TestSingleplayerContext world, WorldGenerationGameTest.Probe probe,
												BlockPos summit, int summitY) {
		LodSampler sampler = world.getServer().computeOnServer(server -> LodSampler.of(server.overworld()));
		if (sampler == null) {
			throw new AssertionError("No LOD sampler for the Earth 1:1 overworld");
		}
		int x0 = summit.getX() - CHECK / 2, z0 = summit.getZ() - CHECK / 2;
		int[] tops = new int[CHECK * CHECK];
		sampler.sample(x0, z0, CHECK, 1, (x, z, column) -> tops[z * CHECK + x] = column.top(column.ground()) - 1);
		int[] counts = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int compared = 0, close = 0, highest = Integer.MIN_VALUE;
			for (int k = 0; k < tops.length; k++) {
				int x = x0 + k % CHECK, z = z0 + k / CHECK;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					continue;
				}
				compared++;
				int ground = WorldGenerationGameTest.groundAt(level, pos.set(x, 0, z));
				close += Math.abs(ground - tops[k]) <= 1 ? 1 : 0;
				highest = Math.max(highest, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1);
			}
			return new int[]{compared, close, highest};
		});
		LOGGER.info("Around the summit, {} of {} columns within a block of the LOD's ground; highest block y = {}",
			counts[1], counts[0], counts[2]);
		// Some 95% match: the LODs have no caves, which open some steep ground. A block missing from #blocks_motion
		// once took it down to 85%, its heightmaps looking through the rock.
		if (counts[0] == 0 || counts[1] < 0.9 * counts[0]) {
			return "The chunks around the summit do not match the terrain: " + counts[1] + " of " + counts[0];
		}
		if (counts[2] < summitY - 2) {
			return "The summit is cut off: the highest block is at y = " + counts[2] + ", the terrain's at " + summitY;
		}
		return null;
	}
}
