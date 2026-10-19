package io.github.elpideus.diffusicraft.block;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The blocks the world needs that vanilla lacks.
 *
 * <ul>
 *   <li><b>Rocks</b> for the geology. Each one drops itself, like granite, and the hard ones count as stone for tools
 *       and furnaces (see the item tags), because a sedimentary basin can hide plain stone a hundred blocks down.</li>
 *   <li><b>Plants</b> of the real-world biomes: heather and cottongrass of the moors, lavender of the maquis,
 *       edelweiss and gentian above the tree line. They are vanilla's bush and flower blocks with their own looks, so
 *       they grow, burn, compost and make dye and suspicious stew as vanilla's do.</li>
 *   <li><b>Soils</b> under the topsoil of the biomes that have their own: peat on the moors and the tundra, which
 *       burns, terra rossa on the Mediterranean's limestone, laterite in the tropics and loess on the steppe. They are
 *       dirt to everything that grows.</li>
 *   <li><b>Salt crust</b>, the white floor of the salt flats.</li>
 *   <li><b>Cave blocks</b> of the Grand Hollows: the glowing silk of glowworms (see {@link GlowwormSilkBlock}) and
 *       beams of selenite (see {@link SeleniteBlock}).</li>
 * </ul>
 */
public final class DiffusicraftBlocks {
	private static final List<Block> ROCKS = new ArrayList<>();
	private static final List<Block> PLANTS = new ArrayList<>();
	private static final List<Block> SOILS = new ArrayList<>();
	/** A block of peat burns half as long as coal: see {@code context_int_provider/cooking/time_peat.json}. */
	private static final ResourceKey<ContextIntProvider> PEAT_BURN_TIME =
		ResourceKey.create(Registries.CONTEXT_INT_PROVIDER, Diffusicraft.id("cooking/time_peat"));

	// Sedimentary.
	public static final Block LIMESTONE = rock("limestone", MapColor.TERRACOTTA_WHITE, SoundType.STONE, 1.2f);
	public static final Block CHALK = rock("chalk", MapColor.SNOW, SoundType.CALCITE, 0.6f);
	public static final Block SHALE = rock("shale", MapColor.COLOR_GRAY, SoundType.STONE, 1.0f);
	public static final Block ROCK_SALT = rock("rock_salt", MapColor.TERRACOTTA_WHITE, SoundType.CALCITE, 0.8f);
	// Metamorphic.
	public static final Block SLATE = rock("slate", MapColor.TERRACOTTA_CYAN, SoundType.STONE, 1.8f);
	public static final Block MARBLE = rock("marble", MapColor.QUARTZ, SoundType.STONE, 1.6f);
	public static final Block SCHIST = rock("schist", MapColor.COLOR_LIGHT_GRAY, SoundType.STONE, 1.6f);
	public static final Block GNEISS = rock("gneiss", MapColor.TERRACOTTA_LIGHT_GRAY, SoundType.STONE, 2.0f);
	// Lower crust and mantle.
	public static final Block GABBRO = rock("gabbro", MapColor.COLOR_BLACK, SoundType.DEEPSLATE, 3.0f);
	public static final Block PERIDOTITE = rock("peridotite", MapColor.TERRACOTTA_GREEN, SoundType.DEEPSLATE, 3.5f);
	public static final Block KIMBERLITE = rock("kimberlite", MapColor.TERRACOTTA_CYAN, SoundType.TUFF, 2.5f);

	public static final Block PEAT = soil("peat", MapColor.COLOR_BROWN, SoundType.MUD, p -> p.cookingFuel(PEAT_BURN_TIME));
	public static final Block TERRA_ROSSA = soil("terra_rossa", MapColor.TERRACOTTA_RED, SoundType.GRAVEL, p -> p);
	public static final Block LATERITE = soil("laterite", MapColor.TERRACOTTA_ORANGE, SoundType.GRAVEL, p -> p);
	public static final Block LOESS = soil("loess", MapColor.SAND, SoundType.GRAVEL, p -> p);

	// Shrubs and sedges, which bone meal spreads like vanilla's bush.
	public static final Block HEATHER = plant("heather", BushBlock::new, MapColor.COLOR_PURPLE, false,
		ContextIntProviders.COMPOSTABLE_LOW_MEDIUM);
	public static final Block COTTONGRASS = plant("cottongrass", BushBlock::new, MapColor.PLANT, true,
		ContextIntProviders.COMPOSTABLE_LOW);
	// Flowers.
	public static final Block LAVENDER = plant("lavender", p -> new FlowerBlock(MobEffects.REGENERATION, 6f, p),
		MapColor.COLOR_PURPLE, false, ContextIntProviders.COMPOSTABLE_MEDIUM);
	public static final Block EDELWEISS = plant("edelweiss", p -> new FlowerBlock(MobEffects.RESISTANCE, 6f, p),
		MapColor.PLANT, false, ContextIntProviders.COMPOSTABLE_MEDIUM);
	public static final Block GENTIAN = plant("gentian", p -> new FlowerBlock(MobEffects.SATURATION, 0.35f, p),
		MapColor.PLANT, false, ContextIntProviders.COMPOSTABLE_MEDIUM);

	public static final Block SALT_CRUST = register("salt_crust", Block::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.SNOW)
		.instrument(NoteBlockInstrument.BASEDRUM)
		.sound(SoundType.CALCITE)
		.requiresCorrectToolForDrops()
		.strength(0.5f), p -> p);

	public static final Block GLOWWORM_SILK = register("glowworm_silk", GlowwormSilkBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_LIGHT_BLUE)
			.noCollision()
			.instabreak()
			.lightLevel(state -> 4)
			.sound(SoundType.HANGING_ROOTS)
			.ignitedByLava()
			.pushReaction(PushReaction.POPPED), p -> p);
	public static final Block SELENITE = register("selenite", SeleniteBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.SNOW)
		.instrument(NoteBlockInstrument.HAT)
		.sound(SoundType.CALCITE)
		.requiresCorrectToolForDrops()
		.strength(0.8f)
		.noOcclusion()
		.isValidSpawn((state, level, pos, entity) -> false)
		.isRedstoneConductor((state, level, pos) -> false)
		.isSuffocating((state, level, pos) -> false)
		.isViewBlocking((state, level, pos, box) -> false), p -> p);

	private DiffusicraftBlocks() {
	}

	/** Registers the blocks (by loading this class), puts them in the Natural Blocks tab and lets the plants burn. */
	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output -> {
			output.insertAfter(Items.CALCITE, ROCKS.toArray(Block[]::new));
			output.insertAfter(Items.ROOTED_DIRT, SOILS.toArray(Block[]::new));
			output.insertAfter(Items.RED_SAND, SALT_CRUST);
			output.insertAfter(Items.AMETHYST_CLUSTER, SELENITE);
			output.insertAfter(Items.GLOW_LICHEN, GLOWWORM_SILK);
			output.insertAfter(Items.BUSH, HEATHER, COTTONGRASS);
			output.insertAfter(Items.LILY_OF_THE_VALLEY, LAVENDER, EDELWEISS, GENTIAN);
		});
		FlammableBlockRegistry flammable = FlammableBlockRegistry.getDefaultInstance();
		for (Block plant : PLANTS) {
			flammable.add(plant, 60, 100);
		}
	}

	/** Every Diffusicraft rock, in creative tab order. */
	public static List<Block> rocks() {
		return List.copyOf(ROCKS);
	}

	private static Block rock(String name, MapColor color, SoundType sound, float hardness) {
		Block block = register(name, Block::new, BlockBehaviour.Properties.of()
			.mapColor(color)
			.instrument(NoteBlockInstrument.BASEDRUM)
			.sound(sound)
			.requiresCorrectToolForDrops()
			.strength(hardness, 6.0f), p -> p);
		ROCKS.add(block);
		return block;
	}

	private static Block soil(String name, MapColor color, SoundType sound, UnaryOperator<Item.Properties> item) {
		Block block = register(name, Block::new, BlockBehaviour.Properties.of()
			.mapColor(color)
			.strength(0.5f)
			.sound(sound), item);
		SOILS.add(block);
		return block;
	}

	/** A small plant, as vanilla's flowers are; {@code grassy} ones can be built over and sway, as grass does. */
	private static Block plant(String name, Function<BlockBehaviour.Properties, Block> factory, MapColor color,
							   boolean grassy, ResourceKey<ContextIntProvider> compost) {
		BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
			.mapColor(color)
			.noCollision()
			.instabreak()
			.sound(SoundType.GRASS)
			.offsetType(grassy ? BlockBehaviour.OffsetType.XYZ : BlockBehaviour.OffsetType.XZ)
			.ignitedByLava()
			.pushReaction(PushReaction.POPPED);
		if (grassy) {
			properties.replaceable();
		}
		Block block = register(name, factory, properties, p -> p.compostable(compost));
		PLANTS.add(block);
		return block;
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
								  BlockBehaviour.Properties properties, UnaryOperator<Item.Properties> itemProperties) {
		Block block = Blocks.register(ResourceKey.create(Registries.BLOCK, Diffusicraft.id(name)), factory, properties);
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Diffusicraft.id(name));
		BlockItem item = new BlockItem(block, itemProperties.apply(
			new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		return block;
	}
}
