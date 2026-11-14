package io.github.elpideus.diffusicraft.compat;

import io.github.elpideus.diffusicraft.Diffusicraft;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.gui.FabricStatusTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Stops the game before Minecraft loads if a mod listed under {@code conflicts} in fabric.mod.json is installed. Those
 * mods change how chunks are generated, loaded or lit, which Diffusicraft does its own way. Under {@code breaks} Fabric
 * Loader would stop them by itself, but it gives no reason and suggests replacing Diffusicraft instead of them.
 */
public final class IncompatibleMods {
	private static final String TITLE = "Diffusicraft can't run together with these mods";
	private static final String REASON =
		"They change how Minecraft generates, loads or lights chunks, and Diffusicraft handles that itself.";
	private static final String WHAT_TO_DO = "Remove them from your mods folder and start the game again.";

	private IncompatibleMods() {
	}

	public static void stopIfInstalled() {
		FabricLoader loader = FabricLoader.getInstance();
		List<String> installed = new ArrayList<>();
		for (ModDependency dependency : loader.getModContainer(Diffusicraft.MOD_ID).orElseThrow().getMetadata()
			.getDependencies()) {
			if (dependency.getKind() == ModDependency.Kind.CONFLICTS) {
				loader.getModContainer(dependency.getModId()).ifPresent(mod -> installed.add(describe(mod)));
			}
		}
		if (!installed.isEmpty()) {
			// A dedicated server has no window, and without one Fabric Loader only exits.
			Diffusicraft.LOGGER.error("{}:\n  - {}\n{}\n{}", TITLE, String.join("\n  - ", installed), REASON,
				WHAT_TO_DO);
			FabricGuiEntry.displayError(TITLE, null, tree -> fill(tree, installed), true);
		}
	}

	/** The mod's name, and the file to remove when the mod is a file of its own. */
	private static String describe(ModContainer mod) {
		String name = mod.getMetadata().getName();
		ModOrigin origin = mod.getOrigin();
		return origin.getKind() == ModOrigin.Kind.PATH
			? name + " (" + origin.getPaths().getFirst().getFileName() + ")"
			: name;
	}

	/** The one tab of the window, in place of the one for a stack trace, as there is none. */
	private static void fill(FabricStatusTree tree, List<String> installed) {
		tree.tabs.clear();
		FabricStatusTree.FabricStatusNode list = tree.addTab("Incompatible mods").node;
		installed.forEach(mod -> list.addMessage(mod, FabricStatusTree.FabricTreeWarningLevel.ERROR));
		list.addMessage(REASON, FabricStatusTree.FabricTreeWarningLevel.NONE);
		list.addMessage(WHAT_TO_DO, FabricStatusTree.FabricTreeWarningLevel.NONE);
	}
}
