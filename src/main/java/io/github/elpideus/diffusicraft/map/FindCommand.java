package io.github.elpideus.diffusicraft.map;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.worldgen.Sights;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * {@code /diffusicraft find <sight>}: the nearest of the sights {@link Sights} knows, the Hollows and their caves, with
 * its coordinates to click for a {@code /tp} and a link to it on the relief map. {@code /diffusicraft find} alone lists
 * them. The same players can run it as {@code /diffusicraft map}: like the map, it changes nothing in the world. The
 * search runs off the server thread, which it would hold up while the models compute the terrain it checks.
 */
final class FindCommand {
	private FindCommand() {
	}

	static LiteralArgumentBuilder<CommandSourceStack> node() {
		LiteralArgumentBuilder<CommandSourceStack> find = Commands.literal("find")
			.executes(context -> list(context.getSource()));
		for (Sights.Sight sight : Sights.Sight.values()) {
			find.then(Commands.literal(sight.id()).executes(context -> find(context.getSource(), sight)));
		}
		return find;
	}

	private static int list(CommandSourceStack source) {
		List<Component> sights = new ArrayList<>();
		for (Sights.Sight sight : Sights.Sight.values()) {
			String command = "/" + Diffusicraft.MOD_ID + " find " + sight.id();
			sights.add(Component.literal(sight.id()).withStyle(style -> style.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(name(sight)))));
		}
		source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.find.list",
			"Sights to find: %s", ComponentUtils.formatList(sights, sight -> sight)), false);
		return sights.size();
	}

	private static int find(CommandSourceStack source, Sights.Sight sight) {
		ServerLevel level = source.getLevel();
		Sights sights = Sights.of(level);
		if (sights == null) {
			source.sendFailure(Component.translatableWithFallback("diffusicraft.find.not_diffusicraft",
				"Diffusicraft does not generate this dimension."));
			return 0;
		}
		BlockPos from = BlockPos.containing(source.getPosition());
		MinecraftServer server = source.getServer();
		source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.find.looking",
			"Looking for the nearest %s…", name(sight)).withStyle(ChatFormatting.GRAY), false);
		long start = System.nanoTime();
		// The place is checked against the terrain there, which the models may take seconds to compute.
		CompletableFuture.supplyAsync(() -> sights.nearest(sight, from.getX(), from.getZ()), Util.backgroundExecutor())
			.whenComplete((found, error) -> server.execute(() -> {
				if (error != null) {
					// The world closed under the search.
					if (server.isRunning()) {
						Diffusicraft.LOGGER.warn("Could not find the nearest {}", sight.id(), error);
						source.sendFailure(Component.translatableWithFallback("diffusicraft.find.failed",
							"Could not find the nearest %s: see the log", name(sight)));
					}
					return;
				}
				Diffusicraft.LOGGER.info("Finding the nearest {} took {} ms", sight.id(),
					(System.nanoTime() - start) / 1_000_000);
				report(source, level, sights, sight, from, found);
			}));
		return 1;
	}

	private static void report(CommandSourceStack source, ServerLevel level, Sights sights, Sights.Sight sight,
							   BlockPos from, Sights.@Nullable Found found) {
		if (found == null) {
			source.sendFailure(Component.translatableWithFallback("diffusicraft.find.none", "No %s within %s blocks",
				name(sight), String.format(Locale.ROOT, "%,d", sights.radius(sight))));
			return;
		}
		BlockPos pos = found.pos();
		int distance = Mth.floor(Math.hypot(pos.getX() - from.getX(), pos.getZ() - from.getZ()));
		Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates",
			pos.getX(), pos.getY(), pos.getZ())).withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
		Component map = mapLink(source, level, pos);
		source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.find.found",
			"The nearest %s is at %s, %s blocks away%s", describe(found), coordinates, distance, map), false);
		switch (found.footing()) {
			case LAKE -> source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.find.lake",
				"Its floor lies under water: the place is just over it.").withStyle(ChatFormatting.GRAY), false);
			case LAVA -> source.sendSuccess(() -> Component.translatableWithFallback("diffusicraft.find.lava",
				"Its floor lies under a lava sea: the place is just over it.").withStyle(ChatFormatting.GRAY), false);
			case FLOOR -> {
			}
		}
	}

	/** The sight found, with the kind of hollow or the cave biome it is in where its name does not say. */
	private static Component describe(Sights.Found found) {
		List<Component> details = new ArrayList<>();
		if (found.sight().kind() == null) {
			details.add(name(found.chamber().kind()));
		}
		if (found.sight().biome() == null && found.biome() != null) {
			details.add(biome(found.biome()));
		}
		MutableComponent name = name(found.sight()).copy();
		return details.isEmpty() ? name : name.append(" (").append(ComponentUtils.formatList(details,
			detail -> detail)).append(")");
	}

	private static Component name(Sights.Sight sight) {
		if (sight.biome() != null) {
			return biome(sight.biome());
		}
		Hollows.Kind kind = sight.kind();
		return kind != null ? name(kind) : Component.translatableWithFallback("diffusicraft.sight." + sight.id(),
			"Hollow Lake");
	}

	private static Component name(Hollows.Kind kind) {
		String id = kind.name().toLowerCase(Locale.ROOT);
		String[] words = id.split("_");
		StringBuilder fallback = new StringBuilder();
		for (String word : words) {
			fallback.append(fallback.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
				.append(word.substring(1));
		}
		return Component.translatableWithFallback("diffusicraft.sight." + id, fallback.toString());
	}

	private static Component biome(String biome) {
		Identifier id = Identifier.parse(biome);
		return Component.translatable("biome." + id.getNamespace() + "." + id.getPath());
	}

	/** A link to the place on the relief map, after a space, or nothing if the map is off. */
	private static Component mapLink(CommandSourceStack source, ServerLevel level, BlockPos pos) {
		URI uri;
		try {
			uri = ReliefMap.link(source.getServer(), source.getPlayer(), level, pos.getX(), pos.getZ());
		} catch (IOException | UncheckedIOException e) {
			Diffusicraft.LOGGER.warn("Could not start the relief map", e);
			return Component.empty();
		}
		if (uri == null) {
			return Component.empty();
		}
		return Component.literal(" ").append(ComponentUtils.wrapInSquareBrackets(Component.translatableWithFallback(
			"diffusicraft.find.map", "Map")).withStyle(style -> style.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.OpenUrl(uri))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("diffusicraft.find.map.open",
				"Show it on the relief map")))));
	}
}
