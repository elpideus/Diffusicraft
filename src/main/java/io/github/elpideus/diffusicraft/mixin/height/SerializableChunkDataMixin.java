package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.height.AirSections;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Saves the Y of chunk sections outside −128..127 as an int. Vanilla stores it as a byte, which caps saved worlds at
 * 4096 blocks whatever the dimension allows. Sections in byte range keep the vanilla byte tag.
 *
 * <p>Leaves the empty sections of tall dimensions out of saves and fills them back in on load; see
 * {@link AirSections}.
 */
@Mixin(SerializableChunkData.class)
abstract class SerializableChunkDataMixin {
	@ModifyVariable(method = "parse", at = @At("STORE"), name = "y")
	private static int diffusicraft$readSectionY(int y, @Local(name = "sectionTag") CompoundTag sectionTag) {
		// A byte tag reads back as the same int.
		return sectionTag.getIntOr("Y", y);
	}

	@Inject(method = "copyOf", at = @At(value = "NEW",
		target = "net/minecraft/world/level/chunk/storage/SerializableChunkData"))
	private static void diffusicraft$skipAirSections(ServerLevel level, ChunkAccess chunk,
		CallbackInfoReturnable<SerializableChunkData> cir,
		@Local(name = "sectionData") List<SerializableChunkData.SectionData> sectionData) {
		if (AirSections.skipsIn(level)) {
			AirSections.skip(sectionData);
		}
	}

	@Inject(method = "parse", at = @At(value = "NEW",
		target = "net/minecraft/world/level/chunk/storage/SerializableChunkData"))
	private static void diffusicraft$fillAirSections(LevelHeightAccessor levelHeight,
		PalettedContainerFactory containerFactory, CompoundTag chunkData,
		CallbackInfoReturnable<SerializableChunkData> cir,
		@Local(name = "sectionData") List<SerializableChunkData.SectionData> sectionData) {
		AirSections.fill(levelHeight, containerFactory, sectionData);
	}

	@WrapOperation(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;putByte(Ljava/lang/String;B)V"))
	private void diffusicraft$writeSectionY(CompoundTag sectionTag, String key, byte value, Operation<Void> original,
		@Local SerializableChunkData.SectionData section) {
		if ("Y".equals(key) && section.y() != value) {
			sectionTag.putInt(key, section.y());
		} else {
			original.call(sectionTag, key, value);
		}
	}
}
