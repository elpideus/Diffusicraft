package io.github.elpideus.diffusicraft.client.screen;

import io.github.elpideus.diffusicraft.runtime.RuntimeManifest;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static io.github.elpideus.diffusicraft.client.screen.ResourceManagerScreen.tr;

/**
 * Lists what a runtime install downloads and under which license, with a button to read each license, before
 * anything is downloaded. NVIDIA's libraries come with their own license terms, which the user has to accept.
 */
final class LicenseConfirmScreen extends ConfirmScreen {
	private final List<RuntimeManifest.Component> components;

	LicenseConfirmScreen(RuntimeManifest.Runtime runtime, List<RuntimeManifest.Component> components, long downloadBytes, BooleanConsumer callback) {
		super(callback, tr("install.title", runtime.name()), message(components, downloadBytes), tr("install.accept"), CommonComponents.GUI_CANCEL);
		this.components = components;
	}

	private static Component message(List<RuntimeManifest.Component> components, long downloadBytes) {
		MutableComponent text = tr("install.message", Formats.bytes(downloadBytes));
		for (RuntimeManifest.Component component : components) {
			text.append("\n• ").append(component.name()).append(Component.literal(" (" + component.license() + ")").withStyle(ChatFormatting.GRAY));
		}
		return text.append("\n\n").append(tr("install.agree"));
	}

	@Override
	protected void addAdditionalText() {
		LinearLayout links = layout.addChild(LinearLayout.vertical().spacing(2));
		links.defaultCellSetting().alignHorizontallyCenter();
		for (RuntimeManifest.Component component : components) {
			if (component.licenseUrl() != null) {
				Component label = tr("install.readLicense", component.license());
				links.addChild(Button.builder(label, ConfirmLinkScreen.confirmLink(this, component.licenseUrl()))
					.size(Math.min(width - 40, font.width(label) + 24), 16)
					.build());
			}
		}
	}
}
