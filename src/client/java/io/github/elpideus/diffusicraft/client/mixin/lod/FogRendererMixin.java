package io.github.elpideus.diffusicraft.client.mixin.lod;

import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the fog close in at the end of the far terrain instead of at the end of the chunks. The haze of the air, which
 * vanilla ends at 1024 blocks in most biomes, stretches as far as the far terrain does, so that the land at its end
 * fades as land at 1024 blocks did; a biome's fog that ends within the chunks stays as it is, and so does the fog
 * under water, in lava or powder snow, and of blindness and darkness.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	/** Where vanilla's haze of the air ends, in the biomes that do not set their own. */
	private static final float AIR_FOG_END = 1024;

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void diffusicraft$reachFarTerrain(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
		float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> info) {
		int far = FarTerrain.reach();
		if (far <= 0) {
			return;
		}
		FogData fog = info.getReturnValue();
		fog.renderDistanceStart = far * 0.75F;
		fog.renderDistanceEnd = far;
		if (camera.getFluidInCamera() != FogType.NONE || camera.entity() instanceof LivingEntity living
			&& (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
			return;
		}
		if (far > AIR_FOG_END && fog.environmentalEnd >= renderDistanceInChunks * 16) {
			float scale = far / AIR_FOG_END;
			fog.environmentalStart *= scale;
			fog.environmentalEnd *= scale;
		}
		fog.skyEnd = Math.max(fog.skyEnd, Math.min(far, 512));
	}
}
