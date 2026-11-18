package dev.octoberjourney;

import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The launch pop-up settings, then one on/off button per switchable mod, in two columns and as many pages as the screen needs, with a note that changes
 * apply on the next start. A mod whose required mod is off is shown off and can't be switched until that one is on.
 */
final class SwitchesScreen extends Screen {
	private final Screen parent;
	private final int page;

	SwitchesScreen(Screen parent) { this(parent, 0); }

	private SwitchesScreen(Screen parent, int page) {
		super(Component.literal("Gatehouse Settings"));
		this.parent = parent;
		this.page = page;
	}

	@Override
	protected void init() {
		List<Switches.Toggle> toggles = Switches.load();
		Map<String, Boolean> on = Switches.effective(toggles);
		int cols = this.width >= 330 ? 2 : 1, bw = 150, gap = 10;
		int total = cols * bw + (cols - 1) * gap, x0 = (this.width - total) / 2, w = Math.min(310, this.width - 20), xw = (this.width - w) / 2;
		int y = 12;
		addRenderableWidget(new StringWidget(xw, y, w, 12, this.title, this.font));
		y += 16;
		addRenderableWidget(new MultiLineTextWidget(xw, y,
				Component.literal("Switch mods and the launch pop-ups on or off. Changes apply the next time you start the game.").withStyle(ChatFormatting.GRAY),
				this.font).setMaxWidth(w).setCentered(true));
		y += 24;
		if (toggles.stream().allMatch(Switches.Toggle::setting)) {
			addRenderableWidget(new MultiLineTextWidget(xw, y,
					Component.literal("Nothing to switch yet: the pack hasn't finished its first update.").withStyle(ChatFormatting.GRAY),
					this.font).setMaxWidth(w).setCentered(true));
		}
		int rows = Math.max(1, (this.height - y - 40) / 24), perPage = rows * cols;
		int pages = Math.max(1, (toggles.size() + perPage - 1) / perPage), p = Math.min(page, pages - 1);
		for (int i = p * perPage, n = 0; i < toggles.size() && n < perPage; i++, n++) {
			Switches.Toggle t = toggles.get(i);
			String blockedBy = null;
			for (String need : t.needs()) if (Boolean.FALSE.equals(on.get(need))) blockedBy = Switches.nameOf(toggles, need);
			String note = t.description() + "\n\nPack default: " + (t.pack() ? "on" : "off") + "." + (t.setting() ? "" : " Loaded now: "
					+ (t.loaded() ? "yes" : "no") + ".")
					+ (blockedBy != null ? "\nOff while " + blockedBy + " is off." : "");
			CycleButton<Boolean> b = CycleButton.onOffBuilder(blockedBy == null && t.wanted())
					.withTooltip(v -> Tooltip.create(Component.literal(note)))
					.create(x0 + (n % cols) * (bw + gap), y + (n / cols) * 24, bw, 20, Component.literal(t.name()), (btn, value) -> {
						Switches.save(t, value);
						// Mods that need this one may change too: redraw.
						Minecraft.getInstance().setScreenAndShow(new SwitchesScreen(parent, page));
					});
			b.active = blockedBy == null;
			addRenderableWidget(b);
		}
		int by = this.height - 28;
		if (pages > 1) {
			Button prev = Button.builder(Component.literal("<"), btn -> Minecraft.getInstance().setScreenAndShow(new SwitchesScreen(parent, p - 1)))
					.bounds(this.width / 2 - 155, by, 40, 20).build();
			prev.active = p > 0;
			addRenderableWidget(prev);
			addRenderableWidget(new StringWidget(this.width / 2 - 110, by + 6, 60, 12, Component.literal((p + 1) + " / " + pages), this.font));
			Button next = Button.builder(Component.literal(">"), btn -> Minecraft.getInstance().setScreenAndShow(new SwitchesScreen(parent, p + 1)))
					.bounds(this.width / 2 - 45, by, 40, 20).build();
			next.active = p < pages - 1;
			addRenderableWidget(next);
			addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose()).bounds(this.width / 2 + 5, by, 150, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose()).bounds(this.width / 2 - 75, by, 150, 20).build());
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreenAndShow(parent);
	}
}
