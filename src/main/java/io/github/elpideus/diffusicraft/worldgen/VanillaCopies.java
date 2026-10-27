package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Diffusicraft's copies of vanilla's world generation data, under {@code diffusicraft:minecraft/}, which mods and data
 * packs that replace vanilla's files (Terralith, Tectonic and the like) leave alone. {@code tools/vanilla_copies.py}
 * writes them and points Diffusicraft's own files at them. Two kinds of data are looked up by their vanilla names
 * instead, and swapped here in Diffusicraft worlds: noises, which their names seed, and the carvers that biomes list.
 */
public final class VanillaCopies {
	private static final String FOLDER = "minecraft/";
	/** Vanilla's carvers by key, as copied; filled when a server starts. */
	private static volatile Map<ResourceKey<WorldCarver>, WorldCarver> carvers = Map.of();

	private VanillaCopies() {
	}

	/** {@code noises}, with vanilla's noises looked up in Diffusicraft's copies, under their vanilla names. */
	public static HolderGetter<NormalNoise> noises(HolderGetter<NormalNoise> noises) {
		return new HolderGetter<>() {
			@Override
			public Optional<Holder.Reference<NormalNoise>> get(ResourceKey<NormalNoise> id) {
				Optional<Holder.Reference<NormalNoise>> copy = copy(id).flatMap(noises::get);
				return copy.isPresent() ? copy : noises.get(id);
			}

			@Override
			public Optional<HolderSet.Named<NormalNoise>> get(TagKey<NormalNoise> id) {
				return noises.get(id);
			}
		};
	}

	/** The copy of a vanilla carver, or the carver itself for anyone else's. */
	public static WorldCarver carver(Holder<WorldCarver> carver) {
		if (carver instanceof Holder.Reference<WorldCarver> reference) {
			WorldCarver copy = carvers.get(reference.key());
			if (copy != null) {
				return copy;
			}
		}
		return carver.value();
	}

	/** Looks up the copies of vanilla's carvers in a starting server's registries. */
	public static void load(RegistryAccess registries) {
		Map<ResourceKey<WorldCarver>, WorldCarver> copies = new HashMap<>();
		registries.lookupOrThrow(Registries.CARVER).listElements().forEach(carver -> {
			Identifier id = carver.key().identifier();
			if (id.getNamespace().equals(Diffusicraft.MOD_ID) && id.getPath().startsWith(FOLDER)) {
				copies.put(ResourceKey.create(Registries.CARVER,
					Identifier.withDefaultNamespace(id.getPath().substring(FOLDER.length()))), carver.value());
			}
		});
		carvers = Map.copyOf(copies);
	}

	public static void clear() {
		carvers = Map.of();
	}

	private static <T> Optional<ResourceKey<T>> copy(ResourceKey<T> key) {
		Identifier id = key.identifier();
		return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
			? Optional.of(ResourceKey.create(key.registryKey(), Diffusicraft.id(FOLDER + id.getPath())))
			: Optional.empty();
	}
}
