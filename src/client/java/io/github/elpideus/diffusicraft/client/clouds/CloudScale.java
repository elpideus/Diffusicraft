package io.github.elpideus.diffusicraft.client.clouds;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * How many times larger than vanilla's the clouds of a Diffusicraft world are drawn: as many times as its dimension
 * type raises them above the sea. They float about 3000 m up in each size's heights, at y = 4678 at 1:1, 36 times as
 * high above the sea as vanilla's. Vanilla's clouds fade out within 2048 blocks of the camera, so that high they would
 * show only overhead; grown with their height, cells, reach and fog alike, they look from the ground as vanilla's do
 * and reach as far towards the horizon.
 *
 * <p>The cloud renderer draws vanilla's layer as seen from the camera shrunk by the scale towards the sea at the
 * world's origin ({@link #camera}), and grows back what it tells the cloud shader. Other dimension types keep vanilla's
 * clouds, however high they set them.
 */
public final class CloudScale {
	/** The height of vanilla's clouds. */
	private static final float VANILLA_HEIGHT = EnvironmentAttributes.CLOUD_HEIGHT.defaultValue();

	private CloudScale() {
	}

	/** How many times larger than vanilla's the clouds of {@code level} are drawn: 1 outside Diffusicraft's worlds. */
	public static float of(@Nullable ClientLevel level) {
		if (level == null || !level.dimensionTypeRegistration().unwrapKey()
			.map(key -> key.identifier().getNamespace().equals(Diffusicraft.MOD_ID))
			.orElse(false)) {
			return 1;
		}
		float height = level.dimensionType().attributes().applyModifier(EnvironmentAttributes.CLOUD_HEIGHT,
			VANILLA_HEIGHT);
		return Math.max(1, (height - WorldScale.SEA_LEVEL) / (VANILLA_HEIGHT - WorldScale.SEA_LEVEL));
	}

	/** Where {@code camera} stands among vanilla's clouds, when they are drawn {@code scale} times larger. */
	public static Vec3 camera(Vec3 camera, float scale) {
		return new Vec3(camera.x / scale, shrink(camera.y, scale), camera.z / scale);
	}

	/** The height among vanilla's clouds of {@code y}, when they are drawn {@code scale} times larger. */
	public static double shrink(double y, float scale) {
		return WorldScale.SEA_LEVEL + (y - WorldScale.SEA_LEVEL) / scale;
	}
}
