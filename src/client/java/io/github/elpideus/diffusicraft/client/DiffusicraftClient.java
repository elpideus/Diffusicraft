package io.github.elpideus.diffusicraft.client;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.client.lod.FarTerrain;
import io.github.elpideus.diffusicraft.client.lod.FarTerrainPipelines;
import io.github.elpideus.diffusicraft.client.lod.RemoteLods;
import io.github.elpideus.diffusicraft.client.screen.Formats;
import io.github.elpideus.diffusicraft.client.worldgen.FarTerrainLimits;
import io.github.elpideus.diffusicraft.runtime.Job;
import io.github.elpideus.diffusicraft.runtime.Resources;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Client-only entrypoint. */
public final class DiffusicraftClient implements ClientModInitializer {
	private static final SystemToast.SystemToastId DOWNLOAD_TOAST = new SystemToast.SystemToastId(8000L);

	@Override
	public void onInitializeClient() {
		// Downloads continue after their screen is closed; report how they ended wherever the player is.
		Resources.get().addListener(job -> Minecraft.getInstance().execute(() -> showResult(job)));
		RemoteLods.init();
		FarTerrainLimits.init();
		FarTerrainPipelines.init();
		FarTerrain.init();
	}

	/** Whether to show the resource screen at game start. Never lets a failure block the title screen. */
	public static boolean showResourcesAtLaunch() {
		try {
			return Resources.get().setupSuggestedAtLaunch();
		} catch (RuntimeException e) {
			Diffusicraft.LOGGER.error("Could not check Diffusicraft's resources", e);
			return false;
		}
	}

	private static void showResult(Job job) {
		Minecraft minecraft = Minecraft.getInstance();
		Component title = Component.literal(job.title());
		switch (job.state()) {
			case DONE -> SystemToast.addOrUpdate(minecraft.gui.toastManager(), DOWNLOAD_TOAST,
				Component.translatable("diffusicraft.toast.done", title),
				Component.translatable(Resources.get().restartRequired() ? "diffusicraft.toast.restart" : "diffusicraft.toast.ready"));
			case FAILED -> SystemToast.addOrUpdate(minecraft.gui.toastManager(), DOWNLOAD_TOAST,
				Component.translatable("diffusicraft.toast.failed", title),
				Component.literal(job.error() == null ? "" : Formats.error(job.error())));
			default -> {
			}
		}
	}
}
