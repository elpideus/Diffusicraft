package io.github.elpideus.diffusicraft.client.screen;

import com.mojang.blaze3d.Blaze3D;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.map.ReliefMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The pause menu's button for the relief map, in singleplayer, beside vanilla's icon buttons: it opens the map in the
 * browser, as {@code /diffusicraft map} links it. See docs/MAP.md.
 */
public final class ReliefMapButton {
	private static final Identifier SPRITE = Identifier.fromNamespaceAndPath(Diffusicraft.MOD_ID, "pause_menu/relief_map");
	private static final SystemToast.SystemToastId FAILED = new SystemToast.SystemToastId();

	private ReliefMapButton() {
	}

	/** Adds the button to the pause menu's row of icon buttons, when this world has a map to show. */
	public static void addTo(LinearLayout iconButtonRow) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || !ReliefMap.available(server)) {
			return;
		}
		UUID owner = minecraft.player.getUUID();
		iconButtonRow.addChild(SpriteIconButton.builder(Component.translatable("diffusicraft.map.button"),
				button -> open(minecraft, server, owner), true)
			.width(20)
			.sprite(SPRITE, 15, 15)
			.withTootip()
			.build());
	}

	/** Starts the map on the server thread, which owns the world, and opens it in the browser. */
	private static void open(Minecraft minecraft, IntegratedServer server, UUID owner) {
		server.execute(() -> {
			try {
				URI uri = ReliefMap.link(server, server.getPlayerList().getPlayer(owner));
				if (uri != null) {
					Blaze3D.openUri(uri);
				}
			} catch (IOException | UncheckedIOException e) {
				Diffusicraft.LOGGER.warn("Could not start the relief map", e);
				minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.gui.toastManager(), FAILED,
					Component.translatable("diffusicraft.map.button.failed"), Component.literal(String.valueOf(e.getMessage()))));
			}
		});
	}
}
