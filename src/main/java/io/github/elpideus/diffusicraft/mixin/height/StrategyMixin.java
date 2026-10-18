package io.github.elpideus.diffusicraft.mixin.height;

import io.github.elpideus.diffusicraft.height.SharedContainerData;
import io.github.elpideus.diffusicraft.height.SharedPalette;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Keeps the {@link SharedContainerData} of one registry: block states, or the biomes of one set of registries. */
@Mixin(Strategy.class)
abstract class StrategyMixin<T> implements SharedContainerData.Source<T> {
	@Unique private final ConcurrentMap<T, PalettedContainer.Data<T>> diffusicraft$sharedData = new ConcurrentHashMap<>();

	@Shadow public abstract int entryCount();

	@Shadow protected abstract Configuration getConfigurationForBitCount(int entryBits);

	@Override
	public PalettedContainer.Data<T> diffusicraft$sharedData(T value) {
		PalettedContainer.Data<T> shared = diffusicraft$sharedData.get(value);
		return shared != null ? shared : diffusicraft$sharedData.computeIfAbsent(value, v -> new PalettedContainer.Data<>(
			getConfigurationForBitCount(0), new ZeroBitStorage(entryCount()), new SharedPalette<>(v)));
	}
}
