package io.github.elpideus.diffusicraft.height;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import java.util.List;

/**
 * Leaves out of saves the sections that hold only air and the same biomes as the section below, and fills them back in
 * on load. Above the ground most of a tall column is such sections, and vanilla writes each of them with its palettes:
 * an empty column of 2046 sections took 213 KiB of NBT and 7 ms to encode.
 *
 * <p>Sections are left out only in dimensions that cannot open without Diffusicraft: its own dimension types (Grand
 * and Earth 1:1) and any dimension taller than vanilla allows. Every other dimension is saved as vanilla saves it, so
 * a world that stops using the mod loses nothing. A section with light data is always written.
 *
 * <p>Loading fills every missing section that has a section below it, in every dimension. Vanilla writes all the
 * sections of a column, so in its saves only a dimension that has grown taller since has sections missing; the new
 * ones then take the biomes of the old top instead of vanilla's plains.
 */
public final class AirSections {
	/** The tallest dimension vanilla allows, in blocks. */
	private static final int VANILLA_MAX_HEIGHT = 4064;

	private AirSections() {
	}

	/** Whether chunks of {@code level} leave their empty sections out of saves. */
	public static boolean skipsIn(ServerLevel level) {
		return level.getHeight() > VANILLA_MAX_HEIGHT || level.dimensionTypeRegistration().unwrapKey()
			.map(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID))
			.orElse(false);
	}

	/**
	 * Removes from {@code sections}, which run up the column in order, those that hold only air, no light and the
	 * biomes of the section just below.
	 */
	public static void skip(List<SerializableChunkData.SectionData> sections) {
		PalettedContainerRO<Holder<Biome>> below = null;
		int belowY = Integer.MIN_VALUE, kept = 0;
		for (int i = 0; i < sections.size(); i++) {
			SerializableChunkData.SectionData data = sections.get(i);
			LevelChunkSection section = data.chunkSection();
			PalettedContainerRO<Holder<Biome>> biomes = section == null ? null : section.getBiomes();
			boolean skip = section != null && below != null && belowY == data.y() - 1 && data.blockLight() == null
				&& data.skyLight() == null && onlyAir(section) && same(biomes, below);
			if (!skip) {
				sections.set(kept++, data);
			}
			below = biomes;
			belowY = data.y();
		}
		sections.subList(kept, sections.size()).clear();
	}

	/** Adds to {@code sections} the sections of the column that are missing and have a section below them. */
	public static void fill(LevelHeightAccessor height, PalettedContainerFactory factory,
		List<SerializableChunkData.SectionData> sections) {
		int min = height.getMinSectionY();
		LevelChunkSection[] column = new LevelChunkSection[height.getSectionsCount()];
		int present = 0;
		for (SerializableChunkData.SectionData data : sections) {
			if (data.chunkSection() != null) {
				column[data.y() - min] = data.chunkSection();
				present++;
			}
		}
		if (present == 0 || present == column.length) {
			return;
		}
		for (int i = 1; i < column.length; i++) {
			if (column[i] == null && column[i - 1] != null) {
				column[i] = new LevelChunkSection(factory.createForBlockStates(), column[i - 1].getBiomes().copy());
				sections.add(new SerializableChunkData.SectionData(min + i, column[i], null, null));
			}
		}
	}

	/** Whether {@code section} holds plain air only, not cave air or void air, which loading would not give back. */
	private static boolean onlyAir(LevelChunkSection section) {
		return section.hasOnlyAir() && !section.getStates().maybeHas(state -> !state.is(Blocks.AIR));
	}

	private static boolean same(PalettedContainerRO<Holder<Biome>> a, PalettedContainerRO<Holder<Biome>> b) {
		for (int y = 0; y < 4; y++) {
			for (int z = 0; z < 4; z++) {
				for (int x = 0; x < 4; x++) {
					if (a.get(x, y, z) != b.get(x, y, z)) {
						return false;
					}
				}
			}
		}
		return true;
	}
}
