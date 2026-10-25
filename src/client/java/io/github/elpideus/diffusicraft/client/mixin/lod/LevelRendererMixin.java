package io.github.elpideus.diffusicraft.client.mixin.lod;

import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the far terrain in the world's solid pass, right after the chunks, so that it shares their depth buffer. It
 * gets ready as the main pass is added to the frame, while the uniforms of the frame can still be written and before
 * the index buffers grow to what the frame asks of them.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	private @Nullable ViewArea viewArea;

	@Inject(method = "addMainPass", at = @At("HEAD"))
	private void diffusicraft$prepareFarTerrain(CallbackInfo info) {
		FarTerrain.prepare(levelRenderState.cameraRenderState, viewArea,
			Minecraft.getInstance().options.getEffectiveRenderDistance());
	}

	@Inject(method = "executeSolid", at = @At("TAIL"))
	private void diffusicraft$drawFarTerrain(ChunkSectionsToRender chunkSectionsToRender,
		FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass, CallbackInfo info) {
		FarTerrain.draw(renderPass);
	}
}
