package io.github.elpideus.diffusicraft.worldgen.biome;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Vanilla's overworld biome table as Minecraft's own code builds it, whatever other mods do to that code. Wilder Wild
 * and the other mods on FrozenLib mix into {@link OverworldBiomeBuilder} itself when Biolith is missing: they add
 * their biomes and move some of vanilla's, the jungle, the cherry grove and the beaches. So the builder's class file
 * is read as it is in the game's jar and defined again in a class loader of its own, which no mixin reaches, and the
 * table is built with that copy. Everything the copy uses comes from the game's classes as they are, which mixins leave
 * alone here: the copy only reads their public constants and calls their public factories.
 *
 * <p>Should that fail, the table is the game's, without the biomes of other mods.
 */
final class VanillaBiomeTable {
	private static final Logger LOGGER = LoggerFactory.getLogger(VanillaBiomeTable.class);
	private static final String BUILDER = OverworldBiomeBuilder.class.getName();

	private VanillaBiomeTable() {
	}

	/** The table, built the first time it is asked for. */
	static List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> get() {
		return Built.TABLE;
	}

	private static final class Built {
		static final List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> TABLE = build();
	}

	private static List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> build() {
		try {
			return pristine();
		} catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
			LOGGER.warn("Could not build vanilla's own overworld biome table; using the game's, without other mods' "
				+ "biomes", e);
			return MultiNoiseBiomeSourceParameterList.knownPresets()
				.get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values().stream()
				.filter(entry -> entry.getSecond().identifier().getNamespace().equals("minecraft"))
				.toList();
		}
	}

	/** The table built by a copy of the builder's class as the game's jar has it. */
	static List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> pristine() throws IOException,
		ReflectiveOperationException {
		ClassLoader game = OverworldBiomeBuilder.class.getClassLoader();
		byte[] bytes;
		try (InputStream in = game.getResourceAsStream(BUILDER.replace('.', '/') + ".class")) {
			if (in == null) {
				throw new IOException("No class file for " + BUILDER);
			}
			bytes = in.readAllBytes();
		}
		Class<?> builder = new Pristine(game, bytes).loadClass(BUILDER);
		Constructor<?> constructor = builder.getDeclaredConstructor();
		constructor.setAccessible(true);
		Method addBiomes = builder.getDeclaredMethod("addBiomes", Consumer.class);
		addBiomes.setAccessible(true);
		List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> entries = new ArrayList<>();
		Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> add = entries::add;
		addBiomes.invoke(constructor.newInstance(), add);
		return List.copyOf(entries);
	}

	/** Defines the builder from its untransformed bytes, and leaves every other class to the game's loader. */
	private static final class Pristine extends ClassLoader {
		private final byte[] builder;

		Pristine(ClassLoader game, byte[] builder) {
			super(game);
			this.builder = builder;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(BUILDER)) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				return loaded != null ? loaded : defineClass(name, builder, 0, builder.length);
			}
		}
	}
}
