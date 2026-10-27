package io.github.elpideus.diffusicraft.mixin.tectonic;

import io.github.elpideus.diffusicraft.compat.tectonic.TectonicBlending;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks the chunks of a Diffusicraft world current for Tectonic before Tectonic reads them (see
 * {@link TectonicBlending}). Its own injection at the head of {@code parse} has the default priority, so it runs after
 * this one.
 */
@Mixin(value = SerializableChunkData.class, priority = 900)
abstract class SerializableChunkDataMixin {
	@Inject(method = "parse", at = @At("HEAD"))
	private static void diffusicraft$noTectonicBlending(LevelHeightAccessor levelHeight,
		PalettedContainerFactory containerFactory, CompoundTag chunkData,
		CallbackInfoReturnable<SerializableChunkData> cir) {
		if (levelHeight instanceof ServerLevel level && DiffusicraftWorldgen.usesModels(level.getChunkSource().getGenerator())) {
			TectonicBlending.markCurrent(chunkData);
		}
	}
}
