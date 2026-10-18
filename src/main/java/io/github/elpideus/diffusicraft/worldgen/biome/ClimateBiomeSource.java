package io.github.elpideus.diffusicraft.worldgen.biome;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code diffusicraft:climate}: vanilla's multi-noise biome source with its picks refined by the real climate (see
 * {@link BiomeRefiner}). It is still a {@link MultiNoiseBiomeSource} reading the same parameter list, so mods that
 * add biomes to that list, or look for a multi-noise source, work with it as they do with vanilla's.
 *
 * <p>It also keeps the deep dark out of the Grand {@link Hollows}: their floors would otherwise be carpeted in sculk.
 * There it picks what vanilla would pick halfway down to the deep dark: lush or dripstone caves where the climate
 * calls for them, and the biome above otherwise.
 *
 * <p>In worlds that are not Diffusicraft's (the sampler carries no {@link BiomeWorld}), it behaves exactly like
 * vanilla's source.
 */
public final class ClimateBiomeSource extends MultiNoiseBiomeSource {
	public static final MapCodec<ClimateBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("preset").forGetter(s -> s.preset),
		Biome.LIST_CODEC.fieldOf("biomes").forGetter(s -> s.refinements)
	).apply(i, ClimateBiomeSource::new));

	/** Depth at which the Grand Hollows pick their biome: the middle of vanilla's cave biomes. */
	private static final float HOLLOW_DEPTH = 0.5f;
	/**
	 * How far into a hollow's walls and pillars, in density, a quart still counts as part of it: nearly as far as the
	 * density reaches ({@link Hollows#SOLID}). Sculk veins spread up to twenty blocks from where they start, and the
	 * game looks biomes up a quart off, so deep dark left in the rock around a hollow would creep into it.
	 */
	private static final double HOLLOW_MARGIN = 7.5;

	private final Holder<MultiNoiseBiomeSourceParameterList> preset;
	private final HolderSet<Biome> refinements;
	private volatile @Nullable Map<String, Holder<Biome>> targets;

	private ClimateBiomeSource(Holder<MultiNoiseBiomeSourceParameterList> preset, HolderSet<Biome> refinements) {
		super(Either.right(preset));
		this.preset = preset;
		this.refinements = refinements;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(super.collectPossibleBiomes(), refinements.stream()).distinct();
	}

	@Override
	@SuppressWarnings("unchecked")
	protected MapCodec<MultiNoiseBiomeSource> codec() {
		return (MapCodec<MultiNoiseBiomeSource>) (MapCodec<?>) CODEC;
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return refine(super.createResolver(sampler), BiomeWorld.of(sampler));
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
												int quartSizeX, int quartSizeY, int quartSizeZ) {
		return refine(super.createResolverForChunk(sampler, minQuartX, minQuartY, minQuartZ, quartSizeX, quartSizeY,
			quartSizeZ), BiomeWorld.of(sampler));
	}

	private BiomeResolver refine(BiomeResolver vanilla, @Nullable BiomeWorld world) {
		if (world == null) {
			return vanilla;
		}
		return (quartX, quartY, quartZ) -> {
			Holder<Biome> biome = vanilla.getNoiseBiome(quartX, quartY, quartZ);
			Columns.Entry column = Columns.CACHE.get().entry(world, quartX, quartZ);
			if (biome.is(Biomes.DEEP_DARK)) {
				return inGrandHollow(column, QuartPos.toBlock(quartY) + 2) ? column.hollowBiome(this) : biome;
			}
			return column.refine(biome, this);
		};
	}

	private static boolean inGrandHollow(Columns.Entry column, int y) {
		Hollows.Kind kind = column.hollows().kindNear(column.hollowColumn(), y, HOLLOW_MARGIN);
		return kind == Hollows.Kind.GRAND_HOLLOW || kind == Hollows.Kind.ANCIENT_GRAND_HOLLOW
			|| kind == Hollows.Kind.DEEP_GRAND_HOLLOW;
	}

	/** The biome that replaces {@code biome} in a column, or {@code biome} itself. */
	private Holder<Biome> refined(Holder<Biome> biome, BiomeClimate climate) {
		String id = biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
		String target = id == null ? null : BiomeRefiner.refine(id, climate);
		if (target == null) {
			return biome;
		}
		Holder<Biome> holder = targets().get(target);
		return holder != null ? holder : biome;
	}

	/** Every biome this source can pick, by id. A datapack may have removed some of the refinements. */
	private Map<String, Holder<Biome>> targets() {
		Map<String, Holder<Biome>> map = targets;
		if (map == null) {
			Map<String, Holder<Biome>> built = new HashMap<>();
			for (Holder<Biome> biome : possibleBiomes()) {
				biome.unwrapKey().ifPresent(key -> built.put(key.identifier().toString(), biome));
			}
			targets = map = built;
		}
		return map;
	}

	/**
	 * Each thread's recent columns, so the climate of a column is looked up once per chunk rather than once per quart.
	 * Direct-mapped by quart position: a chunk's sixteen columns never collide.
	 */
	private static final class Columns {
		static final ThreadLocal<Columns> CACHE = ThreadLocal.withInitial(Columns::new);
		private static final int SIZE = 8;
		private final Entry[] entries = new Entry[SIZE * SIZE];

		Entry entry(BiomeWorld world, int quartX, int quartZ) {
			int slot = (quartX & (SIZE - 1)) | (quartZ & (SIZE - 1)) * SIZE;
			Entry e = entries[slot];
			if (e == null) {
				e = entries[slot] = new Entry();
			}
			int blockX = QuartPos.toBlock(quartX) + 2;
			int blockZ = QuartPos.toBlock(quartZ) + 2;
			if (!e.column.isAt(world.field(), world.scale(), blockX, blockZ)) {
				e.reset(world, blockX, blockZ);
			}
			return e;
		}

		/** One column: its climate, and the refinements it has looked up so far. */
		static final class Entry {
			private static final int MEMO = 4;
			final ClimateColumn column = new ClimateColumn();
			private final BiomeClimate climate = new BiomeClimate();
			@SuppressWarnings("unchecked")
			private final Holder<Biome>[] in = new Holder[MEMO];
			@SuppressWarnings("unchecked")
			private final Holder<Biome>[] out = new Holder[MEMO];
			private int memo;
			private BiomeWorld world;
			private @Nullable Holder<Biome> hollowBiome;
			private final Hollows.Column hollowColumn = new Hollows.Column();
			private boolean hollowPrepared;

			void reset(BiomeWorld world, int blockX, int blockZ) {
				this.world = world;
				column.sample(world.field(), world.scale(), blockX, blockZ);
				climate.set(column.column);
				memo = 0;
				hollowBiome = null;
				hollowPrepared = false;
			}

			Holder<Biome> refine(Holder<Biome> biome, ClimateBiomeSource source) {
				for (int k = 0; k < memo; k++) {
					if (in[k] == biome) {
						return out[k];
					}
				}
				Holder<Biome> result = source.refined(biome, climate);
				if (memo < MEMO) {
					in[memo] = biome;
					out[memo++] = result;
				}
				return result;
			}

			Hollows hollows() {
				return world.hollows();
			}

			Hollows.Column hollowColumn() {
				if (!hollowPrepared) {
					int surface = (int) Math.floor(column.surfaceY);
					world.hollows().column(column.blockX(), column.blockZ(), (x, z) -> surface, hollowColumn);
					hollowPrepared = true;
				}
				return hollowColumn;
			}

			/** What vanilla would pick in a cave halfway down, refined. */
			Holder<Biome> hollowBiome(ClimateBiomeSource source) {
				if (hollowBiome == null) {
					float[] p = column.parameters;
					Holder<Biome> vanilla = source.getNoiseBiome(Climate.target(p[ClimateMapping.TEMPERATURE],
						p[ClimateMapping.HUMIDITY], p[ClimateMapping.CONTINENTALNESS], p[ClimateMapping.EROSION],
						HOLLOW_DEPTH, p[ClimateMapping.WEIRDNESS]));
					hollowBiome = refine(vanilla, source);
				}
				return hollowBiome;
			}
		}
	}
}
