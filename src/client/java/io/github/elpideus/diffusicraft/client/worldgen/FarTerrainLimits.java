package io.github.elpideus.diffusicraft.client.worldgen;

import io.github.elpideus.diffusicraft.client.compat.voxy.VoxyHeights;
import io.github.elpideus.diffusicraft.compat.dh.DistantHorizonsCompat;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What the installed far-terrain mods do with a level taller than they hold. Distant Horizons keeps
 * {@value DistantHorizonsCompat#MAX_HEIGHT} blocks above the bottom of a level and Voxy y = {@value VoxyHeights#MIN_Y}
 * to {@value VoxyHeights#MAX_Y}: higher ground shows cut flat in their far terrain, while the chunks around the player
 * are whole. Players are told before they create such a world and when they enter one. See docs/COMPATIBILITY.md.
 */
public final class FarTerrainLimits {
	private static final Set<ResourceKey<Level>> WARNED = new HashSet<>();
	private static @Nullable ClientLevel pending;

	private FarTerrainLimits() {
	}

	/** What the installed mods will do with a level from {@code minY} up, {@code height} blocks tall. */
	public static List<Component> warnings(int minY, int height) {
		FabricLoader loader = FabricLoader.getInstance();
		List<Component> warnings = new ArrayList<>();
		// Both limits are constants, so reading them loads neither Distant Horizons nor Voxy. The top block of a level
		// 4096 blocks tall, such as Grand, is beyond Distant Horizons too, but it is sky.
		if (loader.isModLoaded("distanthorizons") && height > DistantHorizonsCompat.MAX_HEIGHT + 1) {
			warnings.add(Component.translatable("diffusicraft.limits.distanthorizons",
				minY + DistantHorizonsCompat.MAX_HEIGHT - 1));
		}
		if (loader.isModLoaded("voxy") && (minY < VoxyHeights.MIN_Y || minY + height - 1 > VoxyHeights.MAX_Y)) {
			warnings.add(Component.translatable("diffusicraft.limits.voxy", VoxyHeights.MIN_Y, VoxyHeights.MAX_Y));
		}
		return warnings;
	}

	public static List<Component> warnings(WorldScale scale) {
		return warnings(scale.minY(), scale.height());
	}

	/** The largest size the installed mods hold whole. */
	public static WorldScale largestWhole() {
		WorldScale[] sizes = WorldScale.values();
		for (int i = sizes.length - 1; i > 0; i--) {
			if (warnings(sizes[i]).isEmpty()) {
				return sizes[i];
			}
		}
		return sizes[0];
	}

	/** Warns in the chat about each level the player enters, once per level and connection. */
	public static void init() {
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((minecraft, level) -> pending = level);
		ClientTickEvents.END_CLIENT_TICK.register(FarTerrainLimits::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			WARNED.clear();
			pending = null;
		});
	}

	/** Shows the warnings for the level entered once the chat can show them, as the player arrives. */
	private static void tick(Minecraft minecraft) {
		ClientLevel level = pending;
		if (level == null || minecraft.player == null || minecraft.level != level) {
			return;
		}
		pending = null;
		if (!WARNED.add(level.dimension())) {
			return;
		}
		for (Component warning : warnings(level.getMinY(), level.getHeight())) {
			minecraft.gui.chatListener().handleSystemMessage(Component.literal("[Diffusicraft] ")
				.withStyle(ChatFormatting.GOLD).append(warning.copy().withStyle(ChatFormatting.YELLOW)), false);
		}
	}
}
