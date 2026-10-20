package io.github.elpideus.diffusicraft.client.mixin.voxy;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyTerrain;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps with each of Voxy's worlds the terrain it is filled from. Applied only when Voxy is installed. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine", remap = false)
abstract class WorldEngineMixin implements VoxyTerrain.Carrier {
	@Unique
	private volatile @Nullable VoxyTerrain diffusicraft$terrain;

	@Override
	public @Nullable VoxyTerrain diffusicraft$terrain() {
		return diffusicraft$terrain;
	}

	@Override
	public void diffusicraft$setTerrain(VoxyTerrain terrain) {
		this.diffusicraft$terrain = terrain;
	}
}
