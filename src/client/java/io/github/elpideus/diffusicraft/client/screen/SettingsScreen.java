package io.github.elpideus.diffusicraft.client.screen;

import com.mojang.serialization.Codec;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.runtime.DiffusicraftConfig;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.ObjIntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Every setting of {@code config/diffusicraft.json}, opened from Mod Menu, with a button to the resource screen for the
 * downloads. A button's change is saved at once, a slider's once it stops moving, and text when the screen closes.
 * Each tooltip says when its setting takes effect. See docs/CONFIG.md.
 */
public final class SettingsScreen extends OptionsSubScreen {
	/** The far terrain's distances to choose from, in blocks: from the least to the most the configuration takes. */
	private static final int[] FAR_TERRAIN_DISTANCES = {512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384,
		24576, 32768, 49152, 65536};
	/** How far a server sends far terrain, in blocks. */
	private static final int[] FAR_TERRAIN_RADII = {0, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
	private static final int MAX_VERTICAL_RENDER_DISTANCE = 32;
	private static final int MAX_GPU_INDEX = 7;
	private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
	private static final Codec<OnnxModel.Device> DEVICE_CODEC = Codec.STRING.xmap(OnnxModel.Device::valueOf,
		OnnxModel.Device::name);

	private final Resources resources = Resources.get();
	/** What the text fields hold, saved when the screen closes so that a path is not looked at with every key. */
	private final List<Runnable> typed = new ArrayList<>();

	public SettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, tr("title"));
	}

	private static MutableComponent tr(String key, Object... args) {
		return Component.translatable(key(key), args);
	}

	private static String key(String field) {
		return Diffusicraft.MOD_ID + ".settings." + field;
	}

	@Override
	protected void addOptions() {
		typed.clear();
		DiffusicraftConfig config = resources.config();
		list.addHeader(tr("section.generation"));
		list.addSmall(
			new OptionInstance<>(key("device"),
				device -> Tooltip.create(ResourceManagerScreen.tr("device." + device.name().toLowerCase(Locale.ROOT)
					+ ".tooltip").append("\n\n").append(tr("restart"))),
				// The cycle button puts the caption before the value itself.
				(caption, device) -> ResourceList.deviceName(device),
				new OptionInstance.Enum<>(Arrays.asList(OnnxModel.Device.values()), DEVICE_CODEC),
				config.device, device -> resources.updateConfig(c -> c.device = device)),
			steps("gpuMemoryLimitMb", ResourceList.gpuMemorySteps(config.gpuMemoryLimitMb).stream()
					.mapToInt(Integer::intValue).toArray(), config.gpuMemoryLimitMb,
				mb -> ResourceList.gpuMemoryName(resources, mb), (c, mb) -> c.gpuMemoryLimitMb = mb));
		list.addSmall(
			range("gpuIndex", 0, MAX_GPU_INDEX, config.gpuIndex, index -> Component.literal(Integer.toString(index)),
				(c, index) -> c.gpuIndex = index),
			range("cpuThreads", 0, Runtime.getRuntime().availableProcessors(), config.cpuThreads,
				threads -> threads == 0 ? tr("cpuThreads.all") : Component.literal(Integer.toString(threads)),
				(c, threads) -> c.cpuThreads = threads));
		list.addSmall(
			bool("halfPrecision", config.halfPrecision, (c, on) -> c.halfPrecision = on),
			bool("generateAhead", config.generateAhead, (c, on) -> c.generateAhead = on));

		list.addHeader(tr("section.farTerrain"));
		list.addSmall(
			bool("farTerrain", config.farTerrain, (c, on) -> c.farTerrain = on),
			range("verticalRenderDistance", 0, MAX_VERTICAL_RENDER_DISTANCE, config.verticalRenderDistance,
				rows -> rows == 0 ? tr("verticalRenderDistance.renderDistance") : Component.literal(Integer.toString(rows)),
				(c, rows) -> c.verticalRenderDistance = rows));
		list.addBig(steps("farTerrainDistance", FAR_TERRAIN_DISTANCES, config.farTerrainDistance, SettingsScreen::blocks,
			(c, blocks) -> c.farTerrainDistance = blocks));
		list.addSmall(
			bool("distantHorizonsLods", config.distantHorizonsLods, (c, on) -> c.distantHorizonsLods = on),
			bool("voxyLods", config.voxyLods, (c, on) -> c.voxyLods = on));

		list.addHeader(tr("section.server"));
		list.addSmall(bool("serveFarTerrain", config.serveFarTerrain, (c, on) -> c.serveFarTerrain = on),
			bool("shareBuilds", config.shareBuilds, (c, on) -> c.shareBuilds = on));
		list.addBig(steps("farTerrainRadius", FAR_TERRAIN_RADII, config.farTerrainRadius, SettingsScreen::blocks,
			(c, blocks) -> c.farTerrainRadius = blocks));

		list.addHeader(tr("section.reliefMap"));
		list.addSmall(bool("reliefMap", config.reliefMap, (c, on) -> c.reliefMap = on));
		addText("reliefMapAddress", config.reliefMapAddress, (c, text) -> c.reliefMapAddress = text);
		addText("reliefMapPort", config.reliefMapPort == 0 ? "" : Integer.toString(config.reliefMapPort),
			(c, text) -> c.reliefMapPort = text.isBlank() ? 0 : port(text));
		addText("reliefMapUrl", config.reliefMapUrl, (c, text) -> c.reliefMapUrl = text);

		list.addHeader(tr("section.downloads"));
		list.addSmall(
			bool("showSetupScreen", config.showSetupScreen, (c, on) -> c.showSetupScreen = on),
			bool("useSystemLibraries", config.useSystemLibraries, (c, on) -> c.useSystemLibraries = on));
		addText("storagePath", config.storagePath, (c, text) -> c.storagePath = text);
		addText("rocmNativePath", config.rocmNativePath, (c, text) -> c.rocmNativePath = text);
		list.addBig(Button.builder(tr("resources"), button -> minecraft.gui.setScreen(ResourceManagerScreen.from(this)))
			.tooltip(Tooltip.create(tr("resources.tooltip")))
			.build());
	}

	@Override
	public void removed() {
		// Also on the way to the resource screen, which shows the device and the storage chosen here.
		if (list != null) {
			list.applyUnsavedChanges();
		}
		typed.forEach(Runnable::run);
		// Vanilla's options are not changed here, so they need no saving.
	}

	private static <T> OptionInstance.TooltipSupplier<T> tooltip(String field) {
		return OptionInstance.cachedConstantTooltip(tr(field + ".tooltip"));
	}

	private OptionInstance<Boolean> bool(String field, boolean value, BiConsumer<DiffusicraftConfig, Boolean> set) {
		return OptionInstance.createBoolean(key(field), tooltip(field), value,
			on -> resources.updateConfig(c -> set.accept(c, on)));
	}

	/** A slider from {@code min} to {@code max}, saved once it stops moving. */
	private OptionInstance<Integer> range(String field, int min, int max, int value, IntFunction<Component> name,
										  ObjIntConsumer<DiffusicraftConfig> set) {
		return new OptionInstance<>(key(field), tooltip(field),
			(caption, chosen) -> Options.genericValueLabel(caption, name.apply(chosen)),
			new OptionInstance.IntRange(min, max, false), Math.clamp(value, min, max),
			chosen -> resources.updateConfig(c -> set.accept(c, chosen)));
	}

	/** A slider over {@code steps}, from the step nearest {@code value}, saved once it stops moving. */
	private OptionInstance<Integer> steps(String field, int[] steps, int value, IntFunction<Component> name,
										  ObjIntConsumer<DiffusicraftConfig> set) {
		int nearest = 0;
		for (int i = 1; i < steps.length; i++) {
			if (Math.abs((long) steps[i] - value) < Math.abs((long) steps[nearest] - value)) {
				nearest = i;
			}
		}
		return range(field, 0, steps.length - 1, nearest, index -> name.apply(steps[index]),
			(c, index) -> set.accept(c, steps[index]));
	}

	/**
	 * A text field with its name beside it. Text that {@code set} cannot read, such as a port that is not a number,
	 * turns red and is not saved.
	 */
	private void addText(String field, String value, BiConsumer<DiffusicraftConfig, String> set) {
		Tooltip tooltip = Tooltip.create(tr(field + ".tooltip"));
		StringWidget label = new StringWidget(150, 20, tr(field), font);
		label.setTooltip(tooltip);
		EditBox box = new EditBox(font, 150, 20, tr(field));
		box.setMaxLength(1024);
		box.setValue(value);
		box.setHint(tr(field + ".hint"));
		box.setTooltip(tooltip);
		box.setResponder(text -> box.setTextColor(readable(text, set) ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR));
		typed.add(() -> {
			String text = box.getValue().strip();
			if (!text.equals(value) && readable(text, set)) {
				resources.updateConfig(c -> set.accept(c, text));
			}
		});
		list.addSmall(label, box);
	}

	private boolean readable(String text, BiConsumer<DiffusicraftConfig, String> set) {
		try {
			set.accept(resources.config().copy(), text);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static int port(String text) {
		int port = Integer.parseInt(text.strip());
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Not a port: " + port);
		}
		return port;
	}

	private static Component blocks(int blocks) {
		return blocks == 0 ? tr("blocks.none") : tr("blocks", blocks, blocks / 16);
	}
}
