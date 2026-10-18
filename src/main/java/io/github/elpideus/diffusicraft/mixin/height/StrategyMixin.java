package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.height.NarrowLinearConfiguration;
import io.github.elpideus.diffusicraft.height.SharedContainerData;
import io.github.elpideus.diffusicraft.height.SharedPalette;
import net.minecraft.util.Mth;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps the {@link SharedContainerData} of one registry: block states, or the biomes of one set of registries. Also
 * picks a {@link NarrowLinearConfiguration} for loaded sections with a few blocks.
 */
@Mixin(Strategy.class)
abstract class StrategyMixin<T> implements SharedContainerData.Source<T> {
	@Unique private final ConcurrentMap<T, PalettedContainer.Data<T>> diffusicraft$sharedData = new ConcurrentHashMap<>();

	@Shadow public abstract int entryCount();

	@Shadow protected abstract Configuration getConfigurationForBitCount(int entryBits);

	/** Loads sections with a few blocks narrower; see {@link NarrowLinearConfiguration}. Saves still use 4 bits. */
	@ModifyExpressionValue(method = "getConfigurationForPaletteSize", at = @At(value = "INVOKE", target = "Lnet/"
		+ "minecraft/world/level/chunk/Strategy;getConfigurationForBitCount(I)Lnet/minecraft/world/level/chunk/"
		+ "Configuration;"))
	private Configuration diffusicraft$narrow(Configuration configuration, @Local(argsOnly = true) int paletteSize) {
		return NarrowLinearConfiguration.narrowed(configuration, Mth.ceillog2(paletteSize));
	}

	@Override
	public PalettedContainer.Data<T> diffusicraft$sharedData(T value) {
		PalettedContainer.Data<T> shared = diffusicraft$sharedData.get(value);
		return shared != null ? shared : diffusicraft$sharedData.computeIfAbsent(value, v -> new PalettedContainer.Data<>(
			getConfigurationForBitCount(0), new ZeroBitStorage(entryCount()), new SharedPalette<>(v)));
	}
}
