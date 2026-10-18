package io.github.elpideus.diffusicraft.client.mixin.height;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.client.height.WindowedSectionStorage;
import io.github.elpideus.diffusicraft.height.RenderWindow;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives the client's two render grids a window of section rows around the camera instead of every row of the level
 * (see {@link WindowedSectionStorage}). {@code ViewArea} holds the render sections and {@code SectionUpdateTracker}
 * which of them need rebuilding. Both must cover the same sections: the renderer only rebuilds sections the tracker
 * reports, and looks them up in the view area without a null check.
 */
@Mixin({ViewArea.class, SectionUpdateTracker.class})
abstract class RenderGridMixin {
	@WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/client/RotatingSectionStorage"))
	@SuppressWarnings({"rawtypes", "unchecked"})
	private RotatingSectionStorage diffusicraft$window(int radius, int minSectionY, int maxSectionY,
		RotatingSectionStorage.ValueCreator valueCreator, Operation<RotatingSectionStorage> original) {
		RenderWindow window = WindowedSectionStorage.windowFor(minSectionY, maxSectionY, radius);
		return window == null
			? original.call(radius, minSectionY, maxSectionY, valueCreator)
			: new WindowedSectionStorage(radius, window, valueCreator);
	}
}
