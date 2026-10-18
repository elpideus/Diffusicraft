package io.github.elpideus.diffusicraft.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import io.github.elpideus.diffusicraft.worldgen.lod.LodColumn;
import io.github.elpideus.diffusicraft.worldgen.lod.LodSampler;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Distant Horizons' generator for one Diffusicraft level: fills its LODs from {@link LodSampler} without generating
 * chunks. Each LOD is a square of {@code 64} columns, each column {@code 2^detail} blocks wide; the sampler takes one
 * column at the middle of each. Columns follow Distant Horizons' own conventions: runs from the bottom up, heights
 * relative to the bottom of the level, and air from the highest block to the top of the level, as between the ground
 * and the crowns of its trees.
 */
final class DiffusicraftLodGenerator implements IDhApiWorldGenerator {
	/** Above the default priority of other generator overrides, so that ours draws the levels it can draw. */
	private static final int PRIORITY = 20;
	private static final byte SMALLEST_DETAIL = 0;
	private static final byte LARGEST_DETAIL = 12;
	private static final int FULL_SKY = 15;
	/** What Distant Horizons gives water, whose light it does not trace. */
	private static final int WATER_SKY = 14;

	private final LodSampler sampler;
	private final IDhApiLevelWrapper levelWrapper;
	private final int minY;
	private final int height;
	private final Map<BlockState, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
	private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new ConcurrentHashMap<>();
	private volatile boolean closed;

	DiffusicraftLodGenerator(LodSampler sampler, IDhApiLevelWrapper levelWrapper) {
		this.sampler = sampler;
		this.levelWrapper = levelWrapper;
		this.minY = levelWrapper.getMinHeight();
		this.height = levelWrapper.getHeight();
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}

	@Override
	public byte getSmallestDataDetailLevel() {
		return SMALLEST_DETAIL;
	}

	@Override
	public byte getLargestDataDetailLevel() {
		return LARGEST_DETAIL;
	}

	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
	}

	@Override
	public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
											  byte detailLevel, IDhApiFullDataSource source,
											  EDhApiDistantGeneratorMode mode, ExecutorService executor,
											  Consumer<IDhApiFullDataSource> done) {
		return CompletableFuture.runAsync(() -> {
			if (closed) {
				return;
			}
			sampler.sample(SectionPos.sectionToBlockCoord(chunkPosMinX), SectionPos.sectionToBlockCoord(chunkPosMinZ),
				source.getWidthInDataColumns(), 1 << detailLevel,
				(x, z, column) -> source.setApiDataPointColumn(x, z, points(column)));
			done.accept(source);
		}, executor);
	}

	private List<DhApiTerrainDataPoint> points(LodColumn column) {
		IDhApiBiomeWrapper biome = biomes.computeIfAbsent(column.biome(),
			holder -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{holder}, levelWrapper));
		int size = column.size();
		DhApiTerrainDataPoint[] points = new DhApiTerrainDataPoint[size];
		// The sky lights what stands on the ground, the ground through the water over it, and nothing below it.
		int ground = column.ground();
		for (int run = 0; run < size; run++) {
			BlockState state = column.state(run);
			int sky;
			if (run > ground) {
				sky = state.is(Blocks.WATER) ? WATER_SKY : FULL_SKY;
			} else if (run == ground) {
				sky = Math.max(0, FULL_SKY - column.waterDepth());
			} else {
				sky = 0;
			}
			int bottom = run == 0 ? 0 : column.bottom(run) - minY;
			points[run] = DhApiTerrainDataPoint.create((byte) 0, state.getLightEmission(), sky, bottom,
				column.top(run) - minY, block(state), biome);
		}
		List<DhApiTerrainDataPoint> list = new ArrayList<>(size + 1);
		list.addAll(List.of(points));
		int top = column.top() - minY;
		if (top < height) {
			list.add(DhApiTerrainDataPoint.create((byte) 0, 0, FULL_SKY, top, height,
				DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper(), biome));
		}
		return list;
	}

	private IDhApiBlockStateWrapper block(BlockState state) {
		return blocks.computeIfAbsent(state, key -> key.isAir()
			? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
			: DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{key}, levelWrapper));
	}

	@Override
	public void preGeneratorTaskStart() {
	}

	@Override
	public void close() {
		closed = true;
	}
}
