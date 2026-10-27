package io.github.elpideus.diffusicraft.worldgen.biome;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.elpideus.diffusicraft.worldgen.ClimateMapping;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.density.ClimateColumn;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import io.github.elpideus.diffusicraft.worldgen.material.OtherModsRule;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code diffusicraft:climate}: vanilla's multi-noise biome source with its picks refined by the real climate (see
 * {@link BiomeRefiner}), then varied by {@link SubBiomes}: patches of the biome's siblings, a few hundred blocks
 * across. Its picks come from vanilla's own table, as the game's code builds it, so that data packs replacing the
 * overworld's parameter list (Terralith) and mods hooking into it (TerraBlender, Biolith) leave Diffusicraft's map as
 * it is. It is still a {@link MultiNoiseBiomeSource} reading the parameter list as they leave it, and in the regions
 * {@link ModdedBiomes} opens to them, it takes that list's pick wherever the pick is another mod's biome. So mods that
 * add biomes, or look for a multi-noise source, work with it as they do with vanilla's, beside Diffusicraft's biomes
 * rather than in their place.
 *
 * <p>Each Grand {@link Hollows} cavern gets one cave biome of its own, from the climate over it (see
 * {@link HollowBiomes}), in place of whatever vanilla picks there. The Deep Grand Hollows keep vanilla's picks, but
 * not the deep dark, whose sculk would carpet their floors: there they get what vanilla would pick halfway down to it,
 * lush or dripstone caves where the climate calls for them and the biome above otherwise.
 *
 * <p>Vanilla's sulfur caves need a weirdness that Diffusicraft's terrain never reaches. They go where the ground is
 * {@link io.github.elpideus.diffusicraft.worldgen.geology.Geology#sulfurous sulfurous} instead, at the depths of
 * vanilla's cave biomes, in place of vanilla's picks there.
 *
 * <p>In worlds that are not Diffusicraft's (the sampler carries no {@link BiomeWorld}), it behaves exactly like
 * vanilla's source.
 */
public final class ClimateBiomeSource extends MultiNoiseBiomeSource {
	public static final MapCodec<ClimateBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("preset").forGetter(s -> s.preset),
		Biome.LIST_CODEC.fieldOf("biomes").forGetter(s -> s.refinements),
		RegistryOps.retrieveGetter(Registries.BIOME)
	).apply(i, ClimateBiomeSource::new));

	/** Depth at which the Grand Hollows pick their biome: the middle of vanilla's cave biomes. */
	private static final float HOLLOW_DEPTH = 0.5f;
	/** The depths of vanilla's cave biomes, between which sulfurous ground has its sulfur caves. */
	private static final float CAVE_TOP = 0.2f;
	private static final float CAVE_BOTTOM = 0.9f;

	private final Holder<MultiNoiseBiomeSourceParameterList> preset;
	private final HolderSet<Biome> refinements;
	private final HolderGetter<Biome> biomes;
	private volatile @Nullable MultiNoiseBiomeSource base;
	private volatile @Nullable Map<String, Holder<Biome>> targets;
	/** Whether the parameter list holds biomes of other mods; {@code null} until first asked. */
	private volatile @Nullable Boolean modded;

	private ClimateBiomeSource(Holder<MultiNoiseBiomeSourceParameterList> preset, HolderSet<Biome> refinements,
							   HolderGetter<Biome> biomes) {
		super(Either.right(preset));
		this.preset = preset;
		this.refinements = refinements;
		this.biomes = biomes;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.of(super.collectPossibleBiomes(), base().possibleBiomes().stream(), refinements.stream())
			.flatMap(s -> s).distinct();
	}

	/**
	 * Vanilla's overworld source with the table its code builds ({@link VanillaBiomeTable}). Nothing the mods do to
	 * the parameter list reaches it: TerraBlender only hooks lists it has set up and Biolith only sources it knows the
	 * dimension of.
	 */
	private MultiNoiseBiomeSource base() {
		MultiNoiseBiomeSource source = base;
		if (source == null) {
			base = source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(VanillaBiomeTable.get()
				.stream().map(entry -> entry.<Holder<Biome>>mapSecond(biomes::getOrThrow)).toList()));
		}
		return source;
	}

	/**
	 * Tells Biolith that this is the overworld's source whatever the dimension type, so that it places its mods'
	 * biomes in the taller sizes too, whose dimension types are Diffusicraft's. Biolith adds this method to every biome
	 * source; without Biolith nothing calls it.
	 */
	public ResourceKey<DimensionType> biolith$getDimensionType() {
		return BuiltinDimensionTypes.OVERWORLD;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected MapCodec<MultiNoiseBiomeSource> codec() {
		return (MapCodec<MultiNoiseBiomeSource>) (MapCodec<?>) CODEC;
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeWorld world = BiomeWorld.of(sampler);
		if (world == null) {
			return super.createResolver(sampler);
		}
		return refine(base().createResolver(sampler), super.createResolver(sampler), world);
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
												int quartSizeX, int quartSizeY, int quartSizeZ) {
		BiomeWorld world = BiomeWorld.of(sampler);
		if (world == null) {
			return super.createResolverForChunk(sampler, minQuartX, minQuartY, minQuartZ, quartSizeX, quartSizeY,
				quartSizeZ);
		}
		// The mods' resolver samples the climate of the whole chunk again, so it is made only for a chunk that needs it.
		return refine(base().createResolverForChunk(sampler, minQuartX, minQuartY, minQuartZ, quartSizeX, quartSizeY,
			quartSizeZ), new Lazy(() -> super.createResolverForChunk(sampler, minQuartX, minQuartY, minQuartZ,
			quartSizeX, quartSizeY, quartSizeZ)), world);
	}

	/**
	 * Refines vanilla's picks from {@code base}, and gives the regions open to other mods the picks of {@code mods}
	 * that are theirs.
	 */
	private BiomeResolver refine(BiomeResolver base, BiomeResolver mods, BiomeWorld world) {
		boolean modded = modded();
		return (quartX, quartY, quartZ) -> {
			Holder<Biome> biome = base.getNoiseBiome(quartX, quartY, quartZ);
			Columns columns = Columns.CACHE.get();
			Columns.Entry column = columns.entry(world, quartX, quartZ);
			boolean deepDark = biome.is(Biomes.DEEP_DARK);
			Hollows.Chamber chamber = grandHollow(column, QuartPos.toBlock(quartY) + 2);
			if (chamber != null) {
				Holder<Biome> own = columns.hollowBiome(world, chamber, this);
				if (own != null) {
					return own;
				}
				if (deepDark) {
					return column.hollowBiome(this);
				}
			}
			if (deepDark) {
				return biome;
			}
			Holder<Biome> sulfur = column.climate.sulfurous ? sulfurCaves(world, column, quartY) : null;
			if (sulfur != null) {
				return sulfur;
			}
			if (modded && column.open()) {
				Holder<Biome> theirs = mods.getNoiseBiome(quartX, quartY, quartZ);
				if (OtherModsRule.foreign(theirs)) {
					return theirs;
				}
			}
			return column.refine(biome, this);
		};
	}

	/**
	 * Whether the mods gave this source any biome of theirs. Asked first as chunks generate, after TerraBlender has
	 * added its regions' biomes as the level was made.
	 */
	private boolean modded() {
		Boolean any = modded;
		if (any == null) {
			modded = any = possibleBiomes().stream().anyMatch(OtherModsRule::foreign);
		}
		return any;
	}

	/** Vanilla's sulfur caves, where a quart of sulfurous ground is as deep as they go, or {@code null}. */
	private @Nullable Holder<Biome> sulfurCaves(BiomeWorld world, Columns.Entry column, int quartY) {
		float depth = ClimateMapping.depth(world.scale(), column.column.surfaceY, QuartPos.toBlock(quartY) + 2);
		return depth >= CAVE_TOP && depth <= CAVE_BOTTOM ? targets().get(HollowBiomes.SULFUR_CAVES) : null;
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

	/** A resolver made the first time it is asked for a biome. */
	private static final class Lazy implements BiomeResolver {
		private final Supplier<BiomeResolver> make;
		private @Nullable BiomeResolver resolver;

		Lazy(Supplier<BiomeResolver> make) {
			this.make = make;
		}

		@Override
		public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
			BiomeResolver r = resolver;
			if (r == null) {
				resolver = r = make.get();
			}
			return r.getNoiseBiome(quartX, quartY, quartZ);
		}
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
			/** Whether the column lies in a region open to other mods' biomes: -1 until first asked, then 0 or 1. */
			private byte open;

			void reset(BiomeWorld world, int blockX, int blockZ) {
				this.world = world;
				column.sample(world.field(), world.scale(), blockX, blockZ);
				climate.set(column.column);
				climate.sulfurous = world.geology().sulfurous(blockX, blockZ);
				SubBiomes.locate(world.field().seed(), blockX, blockZ, climate);
				memo = 0;
				hollowBiome = null;
				hollowPrepared = false;
				open = -1;
			}

			boolean open() {
				if (open < 0) {
					open = (byte) (ModdedBiomes.open(world.field().seed(), column.blockX(), column.blockZ(),
						ModdedBiomes.share()) ? 1 : 0);
				}
				return open == 1;
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
					Holder<Biome> vanilla = source.base().getNoiseBiome(Climate.target(p[ClimateMapping.TEMPERATURE],
						p[ClimateMapping.HUMIDITY], p[ClimateMapping.CONTINENTALNESS], p[ClimateMapping.EROSION],
						HOLLOW_DEPTH, p[ClimateMapping.WEIRDNESS]));
					hollowBiome = refine(vanilla, source);
				}
				return hollowBiome;
			}
		}
	}
}
