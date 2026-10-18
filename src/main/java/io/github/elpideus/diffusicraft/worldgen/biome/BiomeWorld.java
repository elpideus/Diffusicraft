package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * What {@link ClimateBiomeSource} needs to know about a Diffusicraft world: its terrain, its scale and its Hollows.
 *
 * <p>A biome source is shared by every world that uses a preset, so it learns which world it is resolving for from
 * the climate sampler it is handed. Mixins attach the world to each {@link RandomState} of Diffusicraft's noise
 * settings, and from there to every {@link Climate.Sampler} it creates.
 */
public record BiomeWorld(TerrainField field, WorldScale scale, Hollows hollows) {
	/** Implemented by {@link RandomState} and {@link Climate.Sampler} through mixins. */
	public interface Carrier {
		@Nullable BiomeWorld diffusicraft$biomeWorld();

		void diffusicraft$setBiomeWorld(@Nullable BiomeWorld world);
	}

	/** The world of a random state created for Diffusicraft's noise settings. */
	public static BiomeWorld of(RandomState state, WorldScale scale, int minY) {
		long hollowsSeed = ((RandomStateAccessor) (Object) state).diffusicraft$random()
			.fromHashOf(HollowsFunction.SEED_ID).nextLong();
		return new BiomeWorld(TerrainFields.forState(state), scale, Hollows.of(hollowsSeed, minY));
	}

	/** The world a climate sampler samples, or {@code null} if it is not a Diffusicraft world. */
	public static @Nullable BiomeWorld of(Climate.Sampler sampler) {
		return ((Carrier) (Object) sampler).diffusicraft$biomeWorld();
	}
}
