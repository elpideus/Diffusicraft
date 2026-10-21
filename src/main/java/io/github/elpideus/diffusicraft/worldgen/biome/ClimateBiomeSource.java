package io.github.elpideus.diffusicraft.worldgen.biome;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
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
 * {@link BiomeRefiner}), then varied by {@link SubBiomes}: patches of the biome's siblings, a few hundred blocks
 * across. It is still a {@link MultiNoiseBiomeSource} reading the same parameter list, so mods that add biomes to that
 * list, or look for a multi-noise source, work with it as they do with vanilla's.
 *
 * <p>Each Grand {@link Hollows} cavern gets one cave biome of its own, from the climate over it (see
 * {@link HollowBiomes}), in place of whatever vanilla picks there. Biomes of other mods are left where vanilla's
 * parameter list puts them. The Deep Grand Hollows keep vanilla's picks, but not the deep dark, whose sculk would
 * carpet their floors: there they get what vanilla would pick halfway down to it, lush or dripstone caves where the
 * climate calls for them and the biome above otherwise.
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
			Columns columns = Columns.CACHE.get();
			Columns.Entry column = columns.entry(world, quartX, quartZ);
			boolean deepDark = biome.is(Biomes.DEEP_DARK);
			Hollows.Chamber chamber = grandHollow(column, QuartPos.toBlock(quartY) + 2);
			if (chamber != null && (deepDark || isVanilla(biome))) {
				Holder<Biome> own = columns.hollowBiome(world, chamber, this);
				if (own != null) {
					return own;
				}
				if (deepDark) {
					return column.hollowBiome(this);
				}
			}
			return deepDark ? biome : column.refine(biome, this);
		};
	}

	/**
	 * The Grand Hollow a quart lies in, or in the rock around, or {@code null}. The game looks biomes up as much as a
	 * quart off, and sculk spreads from wherever the deep dark starts it, so deep dark left just under a floor would
	 * creep onto it.
	 */
	private static Hollows.@Nullable Chamber grandHollow(Columns.Entry column, int y) {
		if (y > column.column.surfaceY) {
			return null;
		}
		return column.hollows().grandHollowNear(column.hollowColumn(), y);
	}

	private static boolean isVanilla(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.identifier().getNamespace().equals("minecraft")).orElse(false);
	}

	/** The biome a Grand Hollow picks for itself, or {@code null} if it leaves it to vanilla or it is missing. */
	private @Nullable Holder<Biome> hollowBiome(Hollows.Chamber chamber, BiomeWorld world, TerrainField.Column scratch,
												BiomeClimate climate) {
		String id = HollowBiomes.pick(chamber.kind(), chamber.id(), HollowBiomes.climate(world, chamber, scratch, climate));
		return id == null ? null : targets().get(id);
	}

	/** The biome that replaces {@code biome} in a column, or {@code biome} itself. */
	private Holder<Biome> refined(Holder<Biome> biome, BiomeClimate climate) {
		String id = biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
		if (id == null) {
			return biome;
		}
		String target = BiomeRefiner.refine(id, climate);
		Holder<Biome> refined = target == null ? null : targets().get(target);
		if (refined != null) {
			biome = refined;
			id = target;
		}
		String sibling = SubBiomes.vary(id, climate);
		Holder<Biome> varied = sibling == null ? null : targets().get(sibling);
		return varied != null ? varied : biome;
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
		private static final int HOLLOWS = 16;
		private final Entry[] entries = new Entry[SIZE * SIZE];
		/** The biomes of the Grand Hollows met lately, by chamber id; a null biome is stored as a missing one. */
		private final long[] hollowIds = new long[HOLLOWS];
		@SuppressWarnings("unchecked")
		private final @Nullable Holder<Biome>[] hollowBiomes = new Holder[HOLLOWS];
		private final @Nullable BiomeWorld[] hollowWorlds = new BiomeWorld[HOLLOWS];
		private final @Nullable ClimateBiomeSource[] hollowSources = new ClimateBiomeSource[HOLLOWS];
		private final TerrainField.Column scratch = new TerrainField.Column();
		private final BiomeClimate climate = new BiomeClimate();

		@Nullable Holder<Biome> hollowBiome(BiomeWorld world, Hollows.Chamber chamber, ClimateBiomeSource source) {
			int slot = (int) (chamber.id() ^ chamber.id() >>> 32) & (HOLLOWS - 1);
			if (hollowIds[slot] != chamber.id() || hollowWorlds[slot] != world || hollowSources[slot] != source) {
				hollowBiomes[slot] = source.hollowBiome(chamber, world, scratch, climate);
				hollowIds[slot] = chamber.id();
				hollowWorlds[slot] = world;
				hollowSources[slot] = source;
			}
			return hollowBiomes[slot];
		}

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
				SubBiomes.locate(world.field().seed(), blockX, blockZ, climate);
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
