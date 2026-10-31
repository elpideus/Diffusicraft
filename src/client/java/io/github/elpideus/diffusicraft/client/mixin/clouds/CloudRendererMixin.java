package io.github.elpideus.diffusicraft.client.mixin.clouds;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import io.github.elpideus.diffusicraft.client.clouds.CloudScale;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the clouds {@link CloudScale} times larger: vanilla's layer as seen from the camera shrunk towards the sea, with
 * where it lies from the camera and how large its cells are grown back for the cloud shader. Sodium replaces only
 * {@code buildMesh}, which works in vanilla's cells either way.
 */
@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
	/** {@code prepare} for drawing, not the one that reloads the clouds' texture. */
	private static final String PREPARE = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V";

	@Unique
	private float diffusicraft$scale = 1;

	@WrapMethod(method = PREPARE)
	private void diffusicraft$shrinkCamera(int color, CloudStatus cloudStatus, float bottomY, int range,
		Vec3 cameraPosition, long gameTime, float partialTicks, Operation<Void> original) {
		float scale = CloudScale.of(Minecraft.getInstance().level);
		diffusicraft$scale = scale;
		if (scale != 1) {
			bottomY = (float) CloudScale.shrink(bottomY, scale);
			cameraPosition = CloudScale.camera(cameraPosition, scale);
		}
		original.call(color, cloudStatus, bottomY, range, cameraPosition, gameTime, partialTicks);
	}

	/** Grows the two vectors of the shader's {@code CloudInfo}: the layer's offset from the camera, and a cell's size. */
	@WrapOperation(method = PREPARE, at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec3(FFF)Lcom/mojang/blaze3d/buffers/Std140Builder;"))
	private Std140Builder diffusicraft$growLayer(Std140Builder builder, float x, float y, float z,
		Operation<Std140Builder> original) {
		float scale = diffusicraft$scale;
		return original.call(builder, x * scale, y * scale, z * scale);
	}
}
