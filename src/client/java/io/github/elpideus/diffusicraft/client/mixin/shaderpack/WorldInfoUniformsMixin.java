package io.github.elpideus.diffusicraft.client.mixin.shaderpack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.client.compat.iris.BlissClouds;
import io.github.elpideus.diffusicraft.client.compat.iris.ComplementaryClouds;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.IrisExclusiveUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.DoubleSupplier;

/**
 * Tells shader packs the height of the clouds where the camera is, in every world. Iris's {@code cloudHeight} uniform
 * reads it only while the level that was loaded when the pack's pipeline was built is there, and Iris builds the
 * pipeline at the title screen, before any level is: the uniform then stays at vanilla's 192 until the pack is
 * reloaded, and neither Complementary's clouds ({@link ComplementaryClouds}) nor Bliss's ({@link BlissClouds}) rise
 * with a Diffusicraft world's. Applied only with Iris, whether Distant Horizons is installed or not.
 */
@Mixin(value = IrisExclusiveUniforms.WorldInfoUniforms.class, remap = false)
abstract class WorldInfoUniformsMixin {
	@WrapOperation(method = "addWorldInfoUniforms", at = @At(value = "INVOKE",
		target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniform1f(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/DoubleSupplier;)Lnet/irisshaders/iris/gl/uniform/UniformHolder;"))
	private static UniformHolder diffusicraft$readCloudHeight(UniformHolder holder, UniformUpdateFrequency frequency,
		String name, DoubleSupplier value, Operation<UniformHolder> original) {
		if (name.equals("cloudHeight")) {
			DoubleSupplier iris = value;
			value = () -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.level == null) {
					return iris.getAsDouble();
				}
				return minecraft.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT,
					minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			};
		}
		return original.call(holder, frequency, name, value);
	}
}
