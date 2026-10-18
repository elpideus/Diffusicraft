package io.github.elpideus.diffusicraft.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.height.NoThreadingDetector;
import io.github.elpideus.diffusicraft.height.SharedContainerData;
import io.github.elpideus.diffusicraft.height.SharedPalette;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.chunk.Strategy;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives single-value containers {@link SharedContainerData} and every container the {@link NoThreadingDetector}.
 *
 * <p>Lithium and FerriteCore null the detector field and overwrite {@code acquire} and {@code release}; Moonrise
 * redirects the detector's allocation to one shared instance. None of that conflicts with this, and this never
 * assumes the field is set.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerMixin<T> implements SharedContainerData.Container {
	@Shadow private volatile PalettedContainer.Data<T> data;
	@Shadow @Final private Strategy<T> strategy;

	// Not required: under Moonrise the allocation is already redirected, to a detector that costs no memory either.
	@WrapOperation(method = "<init>*", at = @At(value = "NEW", target = "net/minecraft/util/ThreadingDetector"), require = 0)
	private ThreadingDetector diffusicraft$noThreadingDetector(String name, Operation<ThreadingDetector> original) {
		return NoThreadingDetector.INSTANCE;
	}

	@Inject(method = "<init>*", at = @At("RETURN"))
	private void diffusicraft$shareCreated(CallbackInfo ci) {
		diffusicraft$share();
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void diffusicraft$shareRead(CallbackInfo ci) {
		diffusicraft$share();
	}

	/** {@code read} would otherwise read into the shared data when the configuration stays the same. */
	@ModifyVariable(method = "createOrReuseData", at = @At("HEAD"), argsOnly = true)
	private PalettedContainer.@Nullable Data<T> diffusicraft$neverReuseShared(PalettedContainer.@Nullable Data<T> oldData) {
		return oldData != null && oldData.palette() instanceof SharedPalette ? null : oldData;
	}

	@Override
	public void diffusicraft$shareIfUniform() {
		PalettedContainer.Data<T> current = data;
		BitStorage storage = current.storage();
		if (storage.getBits() == 0) {
			diffusicraft$share();
			return;
		}
		int id = storage.get(0);
		for (int i = 1; i < storage.getSize(); i++) {
			if (storage.get(i) != id) {
				return;
			}
		}
		data = diffusicraft$source().diffusicraft$sharedData(current.palette().valueFor(id));
	}

	@Override
	public boolean diffusicraft$isShared() {
		return data.palette() instanceof SharedPalette;
	}

	@Unique
	@SuppressWarnings("unchecked")
	private void diffusicraft$share() {
		if (data.palette() instanceof SingleValuePalette<T> palette && !(palette instanceof SharedPalette<T>)) {
			// Null only in a palette that was never filled; leave that to fail where vanilla fails.
			Object value = ((SingleValuePaletteAccessor) palette).diffusicraft$value();
			if (value != null) {
				data = diffusicraft$source().diffusicraft$sharedData((T) value);
			}
		}
	}

	@Unique
	@SuppressWarnings("unchecked")
	private SharedContainerData.Source<T> diffusicraft$source() {
		return (SharedContainerData.Source<T>) strategy;
	}
}
