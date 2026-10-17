package io.github.elpideus.diffusicraft.client.screen;

import com.mojang.blaze3d.Blaze3D;
import io.github.elpideus.diffusicraft.Diffusicraft;
import io.github.elpideus.diffusicraft.runtime.Hardware;
import io.github.elpideus.diffusicraft.runtime.Resources;
import io.github.elpideus.diffusicraft.runtime.RuntimeInstaller;
import io.github.elpideus.diffusicraft.runtime.RuntimeManifest;
import io.github.elpideus.diffusicraft.terrain.diffusion.OnnxModel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Downloads and settings for what Diffusicraft keeps outside the mod jar: the Terrain Diffusion models (required)
 * and the GPU runtimes (optional). Shown once when the game starts if something is missing (see
 * {@code GuiMixin}), and reachable later from the world creation screen's "More" tab and from Mod Menu.
 *
 * <p>Downloads run in the background: leaving the screen does not stop them, and a toast reports when they end.
 */
public final class ResourceManagerScreen extends Screen {
	private static final Component TITLE = tr("title");

	final Resources resources = Resources.get();
	final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 56, 33);
	private final @Nullable Screen parent;
	private final @Nullable Runnable next;
	private @Nullable ResourceList list;
	private @Nullable StringWidget hardwareLine;
	private @Nullable StringWidget noticeLine;
	private @Nullable Button recommendedButton;
	private Resources.@Nullable Status measuredStatus;
	private volatile long storageUsed = -1;

	private ResourceManagerScreen(@Nullable Screen parent, @Nullable Runnable next) {
		super(TITLE);
		this.parent = parent;
		this.next = next;
	}

	/** The screen shown while the game starts; {@code next} continues to the title screen. */
	public static ResourceManagerScreen atLaunch(Runnable next) {
		return new ResourceManagerScreen(null, next);
	}

	/** Opened from {@code parent}, which it returns to. */
	public static ResourceManagerScreen from(@Nullable Screen parent) {
		return new ResourceManagerScreen(parent, null);
	}

	static MutableComponent tr(String key, Object... args) {
		return Component.translatable(Diffusicraft.MOD_ID + ".resources." + key, args);
	}

	private boolean atLaunch() {
		return next != null;
	}

	@Override
	protected void init() {
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		hardwareLine = header.addChild(new StringWidget(Component.empty(), font).setMaxWidth(width - 40));
		noticeLine = header.addChild(new StringWidget(Component.empty(), font).setMaxWidth(width - 40));

		list = layout.addToContents(new ResourceList(this, minecraft));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		if (atLaunch()) {
			recommendedButton = footer.addChild(Button.builder(tr("action.downloadRecommended"), b -> downloadRecommended()).width(220).build());
			footer.addChild(Button.builder(tr("action.later"), b -> onClose()).width(100).build());
		} else {
			footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		}
		layout.visitWidgets(this::addRenderableWidget);
		updateHeaderAndFooter();
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) {
			list.updateSize(width, layout);
		}
	}

	@Override
	public void tick() {
		super.tick();
		Resources.Status status = resources.status();
		if (status != measuredStatus) {
			measuredStatus = status;
			measureStorage();
		}
		if (list != null) {
			list.refreshEntries();
		}
		updateHeaderAndFooter();
	}

	private void updateHeaderAndFooter() {
		boolean moved = setIfChanged(hardwareLine, hardwareText());
		moved |= setIfChanged(noticeLine, noticeText());
		if (moved) {
			layout.arrangeElements();
		}
		if (recommendedButton != null) {
			long bytes = recommendedDownloadBytes();
			boolean busy = !resources.jobs().isEmpty();
			recommendedButton.active = bytes > 0 && !busy;
			recommendedButton.setMessage(busy ? tr("action.downloading")
				: bytes > 0 ? tr("action.downloadRecommended.size", Formats.bytes(bytes))
				: tr("action.allInstalled"));
		}
	}

	private static boolean setIfChanged(@Nullable StringWidget widget, Component text) {
		if (widget == null || widget.getMessage().equals(text)) {
			return false;
		}
		widget.setMessage(text);
		return true;
	}

	private Component hardwareText() {
		Hardware hw = resources.hardware().getNow(null);
		if (hw == null) {
			return tr("hardware.detecting").withStyle(ChatFormatting.GRAY);
		}
		Optional<Hardware.Gpu> gpu = hw.first(Hardware.Vendor.NVIDIA)
			.or(() -> hw.first(Hardware.Vendor.AMD))
			.or(() -> hw.gpus().stream().findFirst());
		return gpu.map(g -> g.vramBytes() > 0
				? tr("hardware.gpu", g.name(), Formats.memory(g.vramBytes()), hw.cpuThreads())
				: tr("hardware.gpuNoMemory", g.name(), hw.cpuThreads()))
			.orElseGet(() -> tr("hardware.cpuOnly", hw.cpuThreads()))
			.withStyle(ChatFormatting.GRAY);
	}

	private Component noticeText() {
		Resources.Status status = resources.status();
		if (resources.restartRequired()) {
			return tr("notice.restart").withStyle(ChatFormatting.YELLOW);
		}
		if (status != null && !status.modelsReady() && resources.job(Resources.MODELS_JOB).isEmpty()) {
			return tr("notice.modelsMissing").withStyle(ChatFormatting.YELLOW);
		}
		return Component.empty();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Actions

	/** The models if missing, plus the GPU runtime for this machine's GPU if not installed. */
	private long recommendedDownloadBytes() {
		Resources.Status status = resources.status();
		if (status == null) {
			return 0;
		}
		long bytes = status.modelsReady() ? 0 : status.modelsDownloadBytes();
		return bytes + recommendedRuntime(status)
			.map(runtime -> status.runtime(runtime.id()).map(rs -> rs.downloadBytes(resources.platform())).orElse(0L))
			.orElse(0L);
	}

	private Optional<RuntimeManifest.Runtime> recommendedRuntime(Resources.Status status) {
		if (resources.config().device == OnnxModel.Device.CPU) {
			return Optional.empty();
		}
		return resources.recommendedRuntime()
			.filter(runtime -> status.runtime(runtime.id()).map(rs -> rs.state() == RuntimeInstaller.RuntimeState.MISSING).orElse(false));
	}

	private void downloadRecommended() {
		Resources.Status status = resources.status();
		if (status == null) {
			return;
		}
		if (!status.modelsReady()) {
			resources.downloadModels();
		}
		recommendedRuntime(status).ifPresent(this::confirmInstall);
	}

	/** Asks the user to accept the licenses of what will be downloaded, then installs. */
	void confirmInstall(RuntimeManifest.Runtime runtime) {
		Resources.Status status = resources.status();
		RuntimeInstaller.RuntimeStatus rs = status == null ? null : status.runtime(runtime.id()).orElse(null);
		if (rs == null) {
			return;
		}
		List<RuntimeManifest.Component> downloads = rs.components().stream()
			.filter(c -> c.state() == RuntimeInstaller.ComponentState.MISSING)
			.map(RuntimeInstaller.ComponentStatus::component)
			.toList();
		minecraft.gui.setScreen(new LicenseConfirmScreen(runtime, downloads, rs.downloadBytes(resources.platform()), accepted -> {
			if (accepted) {
				resources.installRuntime(runtime.id());
			}
			minecraft.gui.setScreen(this);
		}));
	}

	void confirmRemove(RuntimeManifest.Runtime runtime) {
		long bytes = runtime.components().stream()
			.mapToLong(c -> Files.isDirectory(resources.installer().directory(c)) ? c.installedBytes(resources.platform()) : 0)
			.sum();
		minecraft.gui.setScreen(new ConfirmScreen(remove -> {
			if (remove) {
				resources.removeRuntime(runtime.id());
			}
			minecraft.gui.setScreen(this);
		}, tr("remove.title", runtime.name()), tr("remove.message", Formats.bytes(bytes)), tr("action.remove"), CommonComponents.GUI_CANCEL));
	}

	void openFolder(Path folder) {
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			Diffusicraft.LOGGER.warn("Cannot create {}: {}", folder, e.toString());
		}
		Blaze3D.openPath(folder);
	}

	void openLink(URI uri) {
		ConfirmLinkScreen.confirmLinkNow(this, uri);
	}

	/** {@code path} with the home directory shortened to {@code ~}. */
	String shortPath(Path path) {
		String text = path.toAbsolutePath().toString();
		String home = System.getProperty("user.home");
		return home != null && !home.isEmpty() && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
	}

	long storageUsed() {
		return storageUsed;
	}

	private void measureStorage() {
		Path storage = resources.storage();
		CompletableFuture.runAsync(() -> {
			if (!Files.isDirectory(storage)) {
				storageUsed = 0;
				return;
			}
			try (Stream<Path> files = Files.walk(storage)) {
				storageUsed = files.filter(Files::isRegularFile).mapToLong(file -> {
					try {
						return Files.size(file);
					} catch (IOException e) {
						return 0;
					}
				}).sum();
			} catch (IOException | RuntimeException e) {
				storageUsed = -1;
			}
		});
	}

	@Override
	public void onClose() {
		if (next != null) {
			next.run();
		} else {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public Component getNarrationMessage() {
		return noticeLine == null ? title : CommonComponents.joinForNarration(title, noticeLine.getMessage());
	}
}
