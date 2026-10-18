package io.github.elpideus.diffusicraft.height;

import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.SingleValuePalette;

import java.util.List;

/**
 * The palette of {@link SharedContainerData}: one value, which never changes.
 *
 * <p>Vanilla's {@link SingleValuePalette} writes its value again on every lookup, even when it is the same one. Many
 * threads use a shared palette at once, so this one only reads. A different value goes to the container's resize
 * handler, which gives the container data of its own.
 */
public final class SharedPalette<T> extends SingleValuePalette<T> {
	private final T value;

	public SharedPalette(T value) {
		super(List.of(value));
		this.value = value;
	}

	@Override
	public int idFor(T value, PaletteResize<T> resizeHandler) {
		return value == this.value ? 0 : resizeHandler.onResize(1, value);
	}

	/** Never called: {@code PalettedContainerMixin} reads into new data instead. */
	@Override
	public void read(FriendlyByteBuf buffer, IdMap<T> globalMap) {
		throw new UnsupportedOperationException("A shared palette is read-only");
	}
}
