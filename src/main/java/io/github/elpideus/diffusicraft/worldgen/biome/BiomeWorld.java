package io.github.elpideus.diffusicraft.worldgen.biome;

import io.github.elpideus.diffusicraft.mixin.RandomStateAccessor;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.density.HollowsFunction;
import io.github.elpideus.diffusicraft.worldgen.geology.Geology;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * What {@link ClimateBiomeSource} needs to know about a Diffusicraft world: its terrain, its scale, its Hollows and its
 * rocks.
 *
 * <p>A biome source is shared by every world that uses a preset, so it learns which world it is resolving for from
 * the climate sampler it is handed. Mixins attach the world to each {@link RandomState} of Diffusicraft's noise
 * settings, and from there to every {@link Climate.Sampler} it creates.
 */
public record BiomeWorld(TerrainField field, WorldScale scale, Hollows hollows, Geology geology) {
	/** Implemented by {@link RandomState} and {@link Climate.Sampler} through mixins. */
	public interface Carrier {
		@Nullable BiomeWorld diffusicraft$biomeWorld();

		void diffusicraft$setBiomeWorld(@Nullable BiomeWorld world);
	}

	/** The world of a random state created for Diffusicraft's noise settings. */
	public static BiomeWorld of(RandomState state, WorldScale scale, int minY) {
		long hollowsSeed = ((RandomStateAccessor) (Object) state).diffusicraft$random()
			.fromHashOf(HollowsFunction.SEED_ID).nextLong();
		TerrainField field = TerrainFields.forState(state);
		return new BiomeWorld(field, scale, Hollows.of(hollowsSeed, minY),
			new Geology(field.geologySeed(), minY, scale.metersPerBlock(), scale.verticalMetersPerBlock()));
	}

	/** The world a climate sampler samples, or {@code null} if it is not a Diffusicraft world. */
	public static @Nullable BiomeWorld of(Climate.Sampler sampler) {
		return ((Carrier) (Object) sampler).diffusicraft$biomeWorld();
	}
}
