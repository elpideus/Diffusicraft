package io.github.elpideus.diffusicraft.block;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The rocks the geology needs that vanilla lacks. Each one drops itself, like granite, and the hard ones count as stone
 * for tools and furnaces (see the item tags), because a sedimentary basin can hide plain stone a hundred blocks down.
 */
public final class DiffusicraftBlocks {
	private static final List<Block> ROCKS = new ArrayList<>();

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

	private DiffusicraftBlocks() {
	}

	/** Registers the blocks (by loading this class) and puts them in the Natural Blocks tab. */
	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output ->
			output.insertAfter(Items.CALCITE, ROCKS.toArray(Block[]::new)));
	}

	/** Every Diffusicraft rock, in creative tab order. */
	public static List<Block> rocks() {
		return List.copyOf(ROCKS);
	}

	private static Block rock(String name, MapColor color, SoundType sound, float hardness) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Diffusicraft.id(name));
		Block block = Blocks.register(blockKey, BlockBehaviour.Properties.of()
			.mapColor(color)
			.instrument(NoteBlockInstrument.BASEDRUM)
			.sound(sound)
			.requiresCorrectToolForDrops()
			.strength(hardness, 6.0f));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Diffusicraft.id(name));
		BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		ROCKS.add(block);
		return block;
	}
}
