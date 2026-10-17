package io.github.elpideus.diffusicraft.client.screen;

import io.github.elpideus.diffusicraft.runtime.Hardware;
import io.github.elpideus.diffusicraft.runtime.Job;
import io.github.elpideus.diffusicraft.runtime.NativeRuntime;
import io.github.elpideus.diffusicraft.runtime.Platform;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.runtime.RuntimeInstaller;
import io.github.elpideus.diffusicraft.runtime.RuntimeManifest;
import io.github.elpideus.diffusicraft.terrain.diffusion.ModelAssets;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen.tr;

/**
 * The rows of {@link ResourceManagerScreen}: the models, one row per inference runtime, the settings that choose
 * between them, and the download folder. Rows read {@link Resources} on every tick, so progress and status stay
 * live without listeners.
 */
final class ResourceList extends ContainerObjectSelectionList<ResourceList.Entry> {
	static final URI ROCM_GUIDE = URI.create("https://github.com/elpideus/Diffusicraft/blob/main/docs/RUNTIMES.md#amd-rocm");
	private static final int BUTTON_WIDTH = 74;
	private static final int SETTING_WIDTH = 150;
	private static final int ROW_HEIGHT = 40;
	private static final int SECTION_HEIGHT = 22;
	private static final int SETTING_HEIGHT = 24;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int BAR_BACKGROUND = 0xFF2B2B2B;
	private static final int BAR_FILL = 0xFF5CB85C;
	private static final List<Integer> GPU_MEMORY_STEPS_MB = List.of(0, 2048, 3072, 4096, 6144, 8192, 12288, 16384, -1);

	private final ResourceManagerScreen screen;
	private final Resources resources;

	ResourceList(ResourceManagerScreen screen, Minecraft minecraft) {
		super(minecraft, screen.width, screen.layout.getContentHeight(), screen.layout.getHeaderHeight(), ROW_HEIGHT);
		this.screen = screen;
		this.resources = screen.resources;

		addEntry(new Section(tr("section.required")), SECTION_HEIGHT);
		addEntry(new ModelsRow(), ROW_HEIGHT);
		addEntry(new Section(tr("section.runtimes")), SECTION_HEIGHT);
		addEntry(new CpuRow(), ROW_HEIGHT);
		for (RuntimeManifest.Runtime runtime : resources.manifest().runtimes()) {
			addEntry(runtime.components().isEmpty() ? new UserProvidedRuntimeRow(runtime) : new RuntimeRow(runtime), ROW_HEIGHT);
		}
		addEntry(new Section(tr("section.settings")), SECTION_HEIGHT);
		addEntry(new SettingRow(tr("setting.device"), deviceButton()), SETTING_HEIGHT);
		addEntry(new SettingRow(tr("setting.gpuMemory"), gpuMemoryButton()), SETTING_HEIGHT);
		addEntry(new SettingRow(tr("setting.showAtStartup"), startupButton()), SETTING_HEIGHT);
		addEntry(new StorageRow(), ROW_HEIGHT);
		refreshEntries();
	}

	void refreshEntries() {
		children().forEach(Entry::refresh);
	}

	@Override
	public int getRowWidth() {
		return Math.min(440, width - 36);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Settings

	private CycleButton<OnnxModel.Device> deviceButton() {
		return CycleButton.builder(ResourceList::deviceName, resources.config().device)
			.withValues(OnnxModel.Device.values())
			.displayOnlyValue()
			.withTooltip(device -> Tooltip.create(tr("device." + device.name().toLowerCase(Locale.ROOT) + ".tooltip")))
			.create(0, 0, SETTING_WIDTH, 20, tr("setting.device"), (button, device) -> resources.updateConfig(c -> c.device = device));
	}

	private CycleButton<Integer> gpuMemoryButton() {
		int current = resources.config().gpuMemoryLimitMb;
		List<Integer> values = new ArrayList<>(GPU_MEMORY_STEPS_MB);
		if (!values.contains(current)) {
			values.add(values.size() - 1, current);
		}
		return CycleButton.builder(this::gpuMemoryName, current)
			.withValues(values)
			.displayOnlyValue()
			.withTooltip(value -> Tooltip.create(tr("setting.gpuMemory.tooltip")))
			.create(0, 0, SETTING_WIDTH, 20, tr("setting.gpuMemory"), (button, mb) -> resources.updateConfig(c -> c.gpuMemoryLimitMb = mb));
	}

	private CycleButton<Boolean> startupButton() {
		return CycleButton.onOffBuilder(resources.config().showSetupScreen)
			.displayOnlyValue()
			.withTooltip(value -> Tooltip.create(tr("setting.showAtStartup.tooltip")))
			.create(0, 0, SETTING_WIDTH, 20, tr("setting.showAtStartup"), (button, show) -> resources.updateConfig(c -> c.showSetupScreen = show));
	}

	static Component deviceName(OnnxModel.Device device) {
		return tr("device." + device.name().toLowerCase(Locale.ROOT));
	}

	private Component gpuMemoryName(int mb) {
		if (mb < 0) {
			return tr("gpuMemory.unlimited");
		}
		if (mb > 0) {
			return Component.literal(Formats.memory((long) mb << 20));
		}
		// Only computed once the hardware probe is done, so the render thread never waits for it.
		Hardware hw = resources.hardware().getNow(null);
		long auto = hw == null ? 0 : resources.autoGpuMemoryLimit(resources.config().gpuIndex);
		return auto > 0 ? tr("gpuMemory.autoValue", Formats.memory(auto)) : tr("gpuMemory.auto");
	}

	// ---------------------------------------------------------------------------------------------------------
	// Shared status logic

	/** "In use" when this runtime is loaded, "Selected" when it will be loaded, "After restart" when it needs one. */
	private Optional<Component> useStatus(String runtimeId) {
		NativeRuntime.Activation active = NativeRuntime.active();
		Resources.Status status = resources.status();
		boolean selected = status != null && status.selectedRuntime().equals(runtimeId);
		if (active != null) {
			if (active.runtimeId().equals(runtimeId)) {
				return Optional.of(colored("status.inUse", ChatFormatting.GREEN));
			}
			return selected ? Optional.of(colored("status.afterRestart", ChatFormatting.YELLOW)) : Optional.empty();
		}
		return selected ? Optional.of(colored("status.selected", ChatFormatting.GREEN)) : Optional.empty();
	}

	private Optional<Job> failure(String jobId) {
		return resources.lastFinished(jobId).filter(job -> job.state() == Job.State.FAILED && job.error() != null);
	}

	private Optional<Hardware.Gpu> gpu(RuntimeManifest.Runtime runtime) {
		Hardware hw = resources.hardware().getNow(null);
		if (hw == null) {
			return Optional.empty();
		}
		try {
			return hw.first(Hardware.Vendor.valueOf(runtime.vendor().toUpperCase(Locale.ROOT)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static MutableComponent colored(String key, ChatFormatting color, Object... args) {
		return tr(key, args).withStyle(color);
	}

	private static Component progress(Job job) {
		if (job.state() == Job.State.QUEUED) {
			return tr("progress.queued");
		}
		String detail = job.detail();
		boolean hasDetail = detail != null && !detail.isBlank();
		if (job.total() <= 0) {
			return hasDetail ? Component.literal(detail) : tr("progress.starting");
		}
		MutableComponent text = tr("progress.bytes", Math.round(job.fraction() * 100), Formats.bytes(job.done()), Formats.bytes(job.total()));
		double rate = job.bytesPerSecond();
		if (rate > 0) {
			text.append(" · ").append(Formats.rate(rate));
		}
		long left = job.secondsLeft();
		if (left >= 0) {
			text.append(" · ").append(tr("progress.left", Formats.duration(left)));
		}
		if (hasDetail) {
			text.append(" · ").append(detail);
		}
		return text;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Entries

	abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		/** Re-reads the state shown by this entry; called every tick. */
		void refresh() {
		}
	}

	private final class Section extends Entry {
		private final Component label;

		Section(MutableComponent label) {
			this.label = label.withStyle(ChatFormatting.BOLD);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			graphics.centeredText(minecraft.font, label, getContentXMiddle(), getContentBottom() - 11, -1);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}

	/**
	 * Two lines of text on the left (name and status, then a description or the progress of a running job) and
	 * buttons on the right; hidden buttons take no space. Hovering the text shows {@link #tooltip}.
	 */
	private abstract class Row extends Entry {
		private final List<AbstractWidget> widgets = new ArrayList<>();
		Component name = Component.empty();
		Component status = Component.empty();
		Component description = Component.empty();
		@Nullable Component tooltip;
		@Nullable Job job;

		Button button(String key, Button.OnPress onPress) {
			Button button = Button.builder(tr(key), onPress).width(BUTTON_WIDTH).build();
			widgets.add(button);
			return button;
		}

		void cancelJob() {
			Job running = job;
			if (running != null) {
				running.cancel();
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = minecraft.font;
			int right = getContentRight();
			for (int i = widgets.size() - 1; i >= 0; i--) {
				AbstractWidget widget = widgets.get(i);
				if (widget.visible) {
					right -= widget.getWidth();
					widget.setPosition(right, getContentYMiddle() - widget.getHeight() / 2);
					widget.extractRenderState(graphics, mouseX, mouseY, a);
					right -= 4;
				}
			}
			int x = getContentX();
			int textWidth = right - 4 - x;
			int top = getContentY() + 4;
			int nameWidth = Math.min(font.width(name), textWidth);
			graphics.text(font, Formats.clip(font, name, textWidth), x, top, -1);
			graphics.text(font, Formats.clip(font, status, textWidth - nameWidth - 8), x + nameWidth + 8, top, GRAY);

			int second = top + 13;
			Job running = job;
			if (running == null) {
				graphics.text(font, Formats.clip(font, description, textWidth), x, second, GRAY);
			} else {
				graphics.text(font, Formats.clip(font, progress(running), textWidth), x, second, GRAY);
				int barTop = second + 11;
				graphics.fill(x, barTop, x + textWidth, barTop + 3, BAR_BACKGROUND);
				graphics.fill(x, barTop, x + (int) Math.round(textWidth * running.fraction()), barTop + 3, BAR_FILL);
			}
			if (tooltip != null && hovered && mouseX >= x && mouseX < x + textWidth) {
				graphics.setTooltipForNextFrame(font, font.split(tooltip, 320), mouseX, mouseY);
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return widgets.stream().filter(w -> w.visible).toList();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return widgets.stream().filter(w -> w.visible).toList();
		}
	}

	private final class ModelsRow extends Row {
		private final Button download = button("action.download", b -> resources.downloadModels());
		private final Button cancel = button("action.cancel", b -> cancelJob());

		ModelsRow() {
			ModelAssets.Manifest manifest = resources.models().manifest();
			name = tr("models.name");
			tooltip = tr("models.tooltip", manifest.repository(), manifest.license(), resources.models().directory().toString());
		}

		@Override
		void refresh() {
			Resources.Status s = resources.status();
			job = resources.job(Resources.MODELS_JOB).orElse(null);
			Optional<Job> failed = failure(Resources.MODELS_JOB);
			long total = resources.models().manifest().totalBytes();
			if (s == null) {
				status = colored("status.checking", ChatFormatting.GRAY);
				description = tr("models.description", Formats.bytes(total));
			} else if (s.modelsReady()) {
				status = colored("status.installed", ChatFormatting.GREEN);
				description = tr("models.installed", Formats.bytes(total));
			} else if (failed.isPresent()) {
				status = colored("status.failed", ChatFormatting.RED);
				description = Component.literal(Formats.error(failed.get().error())).withStyle(ChatFormatting.RED);
			} else {
				status = colored("status.missing", ChatFormatting.YELLOW);
				description = tr("models.description", Formats.bytes(s.modelsDownloadBytes()));
			}
			download.visible = job == null && s != null && !s.modelsReady();
			download.setMessage(tr(failed.isPresent() ? "action.retry" : "action.download"));
			cancel.visible = job != null;
		}
	}

	private final class CpuRow extends Row {
		CpuRow() {
			name = tr("cpu.name");
			tooltip = tr("cpu.tooltip");
		}

		@Override
		void refresh() {
			status = useStatus(NativeRuntime.BUNDLED).orElseGet(() -> colored("status.builtIn", ChatFormatting.GREEN));
			Hardware hw = resources.hardware().getNow(null);
			int threads = resources.config().cpuThreads;
			description = hw == null ? tr("cpu.description.unknown")
				: tr("cpu.description", threads > 0 ? threads : hw.cpuThreads());
		}
	}

	/** A runtime the mod downloads, such as NVIDIA CUDA. */
	private final class RuntimeRow extends Row {
		private final RuntimeManifest.Runtime runtime;
		private final String jobId;
		private final Button install;
		private final Button remove;
		private final Button cancel;

		RuntimeRow(RuntimeManifest.Runtime runtime) {
			this.runtime = runtime;
			this.jobId = Resources.RUNTIME_JOB_PREFIX + runtime.id();
			this.install = button("action.install", b -> screen.confirmInstall(runtime));
			this.remove = button("action.remove", b -> screen.confirmRemove(runtime));
			this.cancel = button("action.cancel", b -> cancelJob());
			name = Component.literal(runtime.name());
		}

		@Override
		void refresh() {
			Platform platform = resources.platform();
			Resources.Status s = resources.status();
			RuntimeInstaller.RuntimeStatus rs = s == null ? null : s.runtime(runtime.id()).orElse(null);
			job = resources.job(jobId).orElse(null);
			Optional<Job> failed = failure(jobId);
			Optional<Hardware.Gpu> gpu = gpu(runtime);
			boolean supported = runtime.supports(platform);
			boolean hardwareKnown = resources.hardware().isDone();

			if (!supported) {
				status = colored("status.unsupported", ChatFormatting.GRAY);
				description = tr("runtime.unsupportedPlatform", platform.id());
			} else if (rs == null) {
				status = colored("status.checking", ChatFormatting.GRAY);
				description = Component.literal(runtime.description());
			} else if (rs.state() == RuntimeInstaller.RuntimeState.READY) {
				status = useStatus(runtime.id()).orElseGet(() -> colored("status.installed", ChatFormatting.GREEN));
				long onDisk = rs.components().stream()
					.filter(c -> c.state() == RuntimeInstaller.ComponentState.INSTALLED)
					.mapToLong(c -> c.component().installedBytes(platform))
					.sum();
				MutableComponent text = tr("runtime.installed", Formats.bytes(onDisk));
				if (rs.usesSystemLibraries()) {
					text.append(" · ").append(tr("runtime.usesSystem"));
				}
				description = gpu.<Component>map(g -> Component.literal(g.name() + " · ").append(text)).orElse(text);
			} else if (failed.isPresent()) {
				status = colored("status.failed", ChatFormatting.RED);
				description = Component.literal(Formats.error(failed.get().error())).withStyle(ChatFormatting.RED);
			} else {
				Resources.CudaSupport cuda = runtime.id().equals(Resources.CUDA)
					? gpu.map(Resources::cudaSupport).orElse(Resources.CudaSupport.NOT_NVIDIA) : Resources.CudaSupport.SUPPORTED;
				String size = Formats.bytes(rs.downloadBytes(platform));
				boolean usable = gpu.isPresent() && cuda == Resources.CudaSupport.SUPPORTED;
				status = colored("status.notInstalled", usable ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
				description = switch (cuda) {
					case GPU_TOO_OLD -> tr("runtime.cuda.gpuTooOld", gpu.orElseThrow().name());
					case DRIVER_TOO_OLD -> tr("runtime.cuda.driverTooOld", Resources.CUDA_MIN_DRIVER, gpu.orElseThrow().driver());
					default -> gpu.map(g -> tr("runtime.download.gpu", g.name(), size))
						.orElseGet(() -> hardwareKnown ? tr("runtime.download.noGpu", runtime.vendor(), size) : tr("runtime.download", size));
				};
			}

			install.visible = supported && job == null && rs != null && rs.state() == RuntimeInstaller.RuntimeState.MISSING;
			install.setMessage(tr(failed.isPresent() ? "action.retry" : "action.install"));
			remove.visible = job == null && rs != null && rs.state() == RuntimeInstaller.RuntimeState.READY && rs.anyInstalled();
			NativeRuntime.Activation active = NativeRuntime.active();
			boolean loaded = active != null && active.runtimeId().equals(runtime.id());
			remove.active = !loaded;
			remove.setTooltip(loaded ? Tooltip.create(tr("runtime.remove.inUse")) : null);
			cancel.visible = job != null;
			tooltip = componentsTooltip(rs, platform);
		}

		private Component componentsTooltip(RuntimeInstaller.@Nullable RuntimeStatus rs, Platform platform) {
			MutableComponent text = Component.literal(runtime.name()).withStyle(ChatFormatting.BOLD)
				.append(Component.literal("\n" + runtime.description()).withStyle(style -> style.withBold(false)));
			if (rs == null) {
				return text;
			}
			text.append("\n");
			for (RuntimeInstaller.ComponentStatus component : rs.components()) {
				Component state = switch (component.state()) {
					case INSTALLED -> tr("component.installed");
					case SYSTEM -> tr("component.system");
					case MISSING -> tr("component.download", Formats.bytes(component.component().downloadBytes(platform)));
					case UNSUPPORTED -> tr("component.unsupported");
				};
				text.append(Component.literal("\n• " + component.component().name() + " (" + component.component().license() + "): ")
					.withStyle(style -> style.withBold(false))
					.append(state));
			}
			return text;
		}
	}

	/** A runtime whose ONNX Runtime build the user supplies, such as AMD ROCm. */
	private final class UserProvidedRuntimeRow extends Row {
		private final RuntimeManifest.Runtime runtime;
		private final Button folder;
		private final Button guide;

		UserProvidedRuntimeRow(RuntimeManifest.Runtime runtime) {
			this.runtime = runtime;
			this.folder = button("action.folder", b -> screen.openFolder(resources.installer().userProvidedDirectory()));
			this.guide = button("action.guide", b -> screen.openLink(ROCM_GUIDE));
			name = Component.literal(runtime.name());
		}

		@Override
		void refresh() {
			Resources.Status s = resources.status();
			RuntimeInstaller.RuntimeStatus rs = s == null ? null : s.runtime(runtime.id()).orElse(null);
			boolean supported = resources.platform().isLinux();
			Optional<Hardware.Gpu> gpu = gpu(runtime);
			if (!supported) {
				status = colored("status.unsupported", ChatFormatting.GRAY);
				description = tr("userRuntime.linuxOnly");
			} else if (rs != null && rs.state() == RuntimeInstaller.RuntimeState.READY) {
				status = useStatus(runtime.id()).orElseGet(() -> colored("status.installed", ChatFormatting.GREEN));
				description = tr("userRuntime.ready", screen.shortPath(rs.nativeDirectory()));
			} else {
				status = colored("status.notSetUp", ChatFormatting.GRAY);
				description = gpu.map(g -> tr("userRuntime.setup.gpu", g.name())).orElseGet(() -> tr("userRuntime.setup"));
			}
			folder.visible = supported;
			guide.visible = supported;
			tooltip = Component.literal(runtime.description()).append("\n\n")
				.append(tr("userRuntime.tooltip", resources.installer().userProvidedDirectory().toString()));
		}
	}

	private final class StorageRow extends Row {
		StorageRow() {
			button("action.open", b -> screen.openFolder(resources.storage()));
			name = tr("storage.name");
			description = Component.literal(screen.shortPath(resources.storage()));
			tooltip = tr("storage.tooltip", resources.storage().toString(), resources.configFile().toString());
		}

		@Override
		void refresh() {
			long used = screen.storageUsed();
			status = used < 0 ? Component.empty() : colored("storage.used", ChatFormatting.GRAY, Formats.bytes(used));
		}
	}

	/** A label on the left and one control on the right, like the vanilla options lists. */
	private final class SettingRow extends Entry {
		private final Component label;
		private final AbstractWidget control;

		SettingRow(Component label, AbstractWidget control) {
			this.label = label;
			this.control = control;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = minecraft.font;
			control.setPosition(getContentRight() - control.getWidth(), getContentYMiddle() - control.getHeight() / 2);
			control.extractRenderState(graphics, mouseX, mouseY, a);
			graphics.text(font, Formats.clip(font, label, control.getX() - 8 - getContentX()), getContentX(), getContentYMiddle() - 4, -1);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(control);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(control);
		}
	}
}
