package io.github.elpideus.diffusicraft.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gates optional mixins: those into other mods apply only when the mod is installed. Both mixin configs, the common
 * one and the client's, use it.
 */
public final class DiffusicraftMixinPlugin implements IMixinConfigPlugin {
	private static final String PACKAGE = "io.github.elpideus.diffusicraft.";
	/** Mixin packages into other mods, and the mod each needs. */
	private static final Map<String, String> MODS = Map.of(
		PACKAGE + "mixin.dh.", "distanthorizons",
		PACKAGE + "client.mixin.sodium.", "sodium",
		PACKAGE + "client.mixin.voxy.", "voxy");

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		for (Map.Entry<String, String> entry : MODS.entrySet()) {
			if (mixinClassName.startsWith(entry.getKey())) {
				return FabricLoader.getInstance().isModLoaded(entry.getValue());
			}
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
