package io.github.elpideus.diffusicraft.client.mixin.iris;

import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.elpideus.diffusicraft.client.compat.iris.IrisFarTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the far terrain under a shader pack (see {@link IrisFarTerrain}): its land as the solid pass begins, before the
 * chunks, and its water as the pass Iris begins after the pack's deferred passes does, before the translucent chunks.
 * Each is the first thing in a pass that was just begun, which binds its state again as it draws. Applied only with
 * Iris and without Distant Horizons.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "executeSolid", at = @At("HEAD"))
	private void diffusicraft$drawFarTerrain(ChunkSectionsToRender chunkSectionsToRender,
		FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass, CallbackInfo info) {
		IrisFarTerrain.drawOpaque();
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void diffusicraft$drawFarWater(ChunkSectionsToRender chunkSectionsToRender,
		FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass, CallbackInfo info) {
		IrisFarTerrain.drawWater();
	}
}
