package dev.octoberjourney;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One on/off button per switchable mod, with a note that changes apply on the next start. */
final class SwitchesScreen extends Screen {
	private final Screen parent;

	SwitchesScreen(Screen parent) {
		super(Component.literal("October Journey"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = 260, x = (this.width - w) / 2, y = 24;
		addRenderableWidget(new StringWidget(x, y, w, 12, this.title, this.font));
		y += 18;
		MultiLineTextWidget intro = new MultiLineTextWidget(x, y,
				Component.literal("Switch these mods on or off. Changes apply the next time you start the game."), this.font)
				.setMaxWidth(w).setCentered(true);
		addRenderableWidget(intro);
		y += 30;
		List<Switches.Toggle> toggles = Switches.load();
		if (toggles.isEmpty()) {
			addRenderableWidget(new MultiLineTextWidget(x, y,
					Component.literal("Nothing to switch yet: the pack hasn't finished its first update.").withStyle(ChatFormatting.GRAY),
					this.font).setMaxWidth(w).setCentered(true));
			y += 24;
		}
		for (Switches.Toggle t : toggles) {
			CycleButton<Boolean> b = CycleButton.onOffBuilder(t.wanted())
					.withTooltip(v -> Tooltip.create(Component.literal(t.description() + "\n\nPack default: " + (t.pack() ? "on" : "off")
							+ ". Now: " + (t.loaded() ? "loaded" : "not loaded") + ".")))
					.create(x, y, w, 20, Component.literal(t.name()), (btn, on) -> Switches.save(t.slug(), on));
			addRenderableWidget(b);
			y += 24;
		}
		y += 8;
		addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose()).bounds(x + w / 4, y, w / 2, 20).build());
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreenAndShow(parent);
	}
}
