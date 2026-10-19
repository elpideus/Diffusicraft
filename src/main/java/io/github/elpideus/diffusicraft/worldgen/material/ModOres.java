package io.github.elpideus.diffusicraft.worldgen.material;

import io.github.elpideus.diffusicraft.worldgen.geology.Ore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.ReplaceBlockFeature;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The blocks of the minerals that tech mods add (see {@link Ore}), found through the common tags once the tags have
 * loaded. A mineral's ores are the blocks under {@code c:ores/<name>} for the first of its names that any mod tags:
 * the metal's, then the mineral's. Of those, the geology places one that sits in stone and one that sits in deepslate.
 * Where several mods add the same mineral, the first by id wins. What a block sits in comes, in this order, from the tags {@code c:ores_in_ground/stone} and
 * {@code c:ores_in_ground/deepslate}, from the ground the mods' own ore features put it in, from a "deepslate" in its
 * id, and from its map color, which mods copy from stone or deepslate along with the rest of its properties. Nether and
 * End ores are never placed.
 */
public final class ModOres {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModOres.class);
	private static final TagKey<Block> IN_STONE = tag("ores_in_ground/stone");
	private static final TagKey<Block> IN_DEEPSLATE = tag("ores_in_ground/deepslate");
	private static final String IN_GROUND = "ores_in_ground/";
	private static volatile @Nullable ModOres current;
	/** What was last logged, so that the tags loading again for the same mods log nothing. */
	private static volatile String logged = "";

	/** What an ore block sits in. */
	enum Ground {
		STONE,
		DEEPSLATE,
		/** Netherrack, end stone or anything else the overworld's rock is not. */
		OTHER
	}

	/** A block under one of a mineral's tags. */
	record Candidate<T>(T block, String id, Ground ground) {
	}

	/** The blocks for one mineral, in stone and in deepslate. Either stands in for the other where a mod has one. */
	record Choice<T>(T stone, T deep) {
	}

	private final Set<Ore> available = EnumSet.copyOf(Ore.VANILLA);
	private final BlockState[] stone = new BlockState[Ore.values().length];
	private final BlockState[] deep = new BlockState[Ore.values().length];

	private ModOres(Map<Block, Ground> placed) {
		StringJoiner found = new StringJoiner(", ");
		for (Ore ore : Ore.values()) {
			if (ore.tags().isEmpty()) {
				continue;
			}
			Choice<Block> choice = null;
			for (String name : ore.tags()) {
				List<Candidate<Block>> candidates = new ArrayList<>();
				for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag("ores/" + name))) {
					Identifier id = BuiltInRegistries.BLOCK.getKey(holder.value());
					candidates.add(new Candidate<>(holder.value(), String.valueOf(id), ground(holder, id, placed)));
				}
				choice = choose(candidates);
				if (choice != null) {
					break;
				}
			}
			if (choice != null) {
				available.add(ore);
				stone[ore.ordinal()] = choice.stone().defaultBlockState();
				deep[ore.ordinal()] = choice.deep().defaultBlockState();
				found.add(ore.tags().getFirst() + " " + BuiltInRegistries.BLOCK.getKey(choice.stone()) + " and "
					+ BuiltInRegistries.BLOCK.getKey(choice.deep()));
			}
		}
		if (found.length() > 0 && !found.toString().equals(logged)) {
			LOGGER.info("The geology places the ores of other mods: {}", found);
		}
		logged = found.toString();
	}

	/** Finds the blocks in the tags and ore features loaded now: called on the server whenever its tags load. */
	public static void load(RegistryAccess registries) {
		current = new ModOres(placedOres(registries));
	}

	/** The blocks found when the server's tags last loaded or, before that, those the tags alone tell. */
	public static ModOres current() {
		ModOres ores = current;
		if (ores == null) {
			synchronized (ModOres.class) {
				ores = current;
				if (ores == null) {
					ores = new ModOres(Map.of());
					current = ores;
				}
			}
		}
		return ores;
	}

	/** Forgets the blocks: called when the server stops. */
	public static void clear() {
		current = null;
	}

	/** The minerals the geology may place: vanilla's and those a mod adds. */
	public Set<Ore> available() {
		return available;
	}

	/** The block of a modded mineral the geology placed, which {@link #available} holds. */
	public BlockState block(Ore ore, boolean deepslate) {
		return (deepslate ? deep : stone)[ore.ordinal()];
	}

	static <T> @Nullable Choice<T> choose(List<Candidate<T>> candidates) {
		List<Candidate<T>> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparing(Candidate::id));
		T inStone = null;
		T inDeepslate = null;
		for (Candidate<T> candidate : sorted) {
			if (candidate.ground() == Ground.STONE && inStone == null) {
				inStone = candidate.block();
			} else if (candidate.ground() == Ground.DEEPSLATE && inDeepslate == null) {
				inDeepslate = candidate.block();
			}
		}
		if (inStone == null && inDeepslate == null) {
			return null;
		}
		return new Choice<>(inStone != null ? inStone : inDeepslate, inDeepslate != null ? inDeepslate : inStone);
	}

	private static Ground ground(Holder<Block> block, Identifier id, Map<Block, Ground> placed) {
		if (block.is(IN_STONE)) {
			return Ground.STONE;
		}
		if (block.is(IN_DEEPSLATE)) {
			return Ground.DEEPSLATE;
		}
		if (block.tags().anyMatch(t -> t.location().getNamespace().equals("c")
			&& t.location().getPath().startsWith(IN_GROUND))) {
			return Ground.OTHER;
		}
		Ground ground = placed.get(block.value());
		if (ground != null) {
			return ground;
		}
		if (id.getPath().contains("deepslate")) {
			return Ground.DEEPSLATE;
		}
		MapColor color = block.value().defaultMapColor();
		return color == MapColor.STONE ? Ground.STONE : color == MapColor.DEEPSLATE ? Ground.DEEPSLATE : Ground.OTHER;
	}

	/**
	 * The ground every ore feature puts its blocks in: stone if it replaces stone, deepslate if it replaces deepslate,
	 * and otherwise the ground of another dimension. A block several features place takes the first of those.
	 */
	private static Map<Block, Ground> placedOres(RegistryAccess registries) {
		Map<Block, Ground> placed = new HashMap<>();
		Registry<Feature> features = registries.lookup(Registries.FEATURE).orElse(null);
		if (features == null) {
			return placed;
		}
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
		RandomSource random = RandomSource.create(0);
		for (Feature feature : features) {
			List<BlockReplacement> targets = feature instanceof AbstractOreFeature ore ? ore.targetStates()
				: feature instanceof ReplaceBlockFeature replace ? replace.replacements() : List.of();
			for (BlockReplacement target : targets) {
				Ground ground = target.target().test(stone, BlockPos.ZERO, random) ? Ground.STONE
					: target.target().test(deepslate, BlockPos.ZERO, random) ? Ground.DEEPSLATE : Ground.OTHER;
				placed.merge(target.state().getBlock(), ground, (a, b) -> a.compareTo(b) <= 0 ? a : b);
			}
		}
		return placed;
	}

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", path));
	}
}
