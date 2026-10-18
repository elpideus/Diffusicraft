package io.github.elpideus.diffusicraft.mixin.height;

import net.minecraft.world.level.chunk.SingleValuePalette;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The value of a single-value palette, without the exception {@code valueFor} throws for an empty one. */
@Mixin(SingleValuePalette.class)
interface SingleValuePaletteAccessor {
	@Accessor("value")
	@Nullable Object diffusicraft$value();
}
