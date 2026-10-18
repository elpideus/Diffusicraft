package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Saves the Y of chunk sections outside −128..127 as an int. Vanilla stores it as a byte, which caps saved worlds at
 * 4096 blocks whatever the dimension allows. Sections in byte range keep the vanilla byte tag.
 */
@Mixin(SerializableChunkData.class)
abstract class SerializableChunkDataMixin {
	@ModifyVariable(method = "parse", at = @At("STORE"), name = "y")
	private static int diffusicraft$readSectionY(int y, @Local(name = "sectionTag") CompoundTag sectionTag) {
		// A byte tag reads back as the same int.
		return sectionTag.getIntOr("Y", y);
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
