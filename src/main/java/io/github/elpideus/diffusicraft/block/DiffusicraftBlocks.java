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
 *   <li><b>Salt crust</b>, the white floor of the salt flats.</li>
 * </ul>
 */
public final class DiffusicraftBlocks {
	private static final List<Block> ROCKS = new ArrayList<>();
	private static final List<Block> PLANTS = new ArrayList<>();

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

	private DiffusicraftBlocks() {
	}

	/** Registers the blocks (by loading this class), puts them in the Natural Blocks tab and lets the plants burn. */
	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output -> {
			output.insertAfter(Items.CALCITE, ROCKS.toArray(Block[]::new));
			output.insertAfter(Items.RED_SAND, SALT_CRUST);
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
