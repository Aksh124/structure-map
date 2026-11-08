package structuremap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import structuremap.StructureMapSettings.CheckedOff;

/** Structure Map's settings: /structurelocate settings, the menu's Settings button, or Mod Menu. */
public final class StructureMapSettingsScreen extends Screen {
	private final Screen parent;
	private CycleButton<CheckedOff> checkedOff;

	public StructureMapSettingsScreen(Screen parent) {
		super(Component.literal("Structure Map settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 155;
		checkedOff = addRenderableWidget(CycleButton.builder((CheckedOff value) -> Component.literal(value.label), StructureMapSettings.checkedOff())
			.withValues(CheckedOff.values())
			.create(x, height / 2 - 20, 310, 20, Component.literal("Checked-off waypoints"),
				(button, value) -> StructureMapSettings.setCheckedOff(value)));
		checkedOff.setTooltip(Tooltip.create(Component.literal("What happens to a structure's Xaero waypoint when you check it off."
			+ " Disabled waypoints stay in Xaero's list, hidden. Unchecking puts it back either way.")));
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(width / 2 - 75, height / 2 + 30, 150, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, height / 2 - 50, 0xFFFFFFFF);
		if (!Waypoints.available()) {
			graphics.centeredText(font, Component.literal("Xaero's Minimap isn't installed, so this does nothing yet."),
				width / 2, height / 2 + 6, 0xFFA0A0A0);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For the game test. */
	public void choose(CheckedOff value) {
		checkedOff.setValue(value);
		StructureMapSettings.setCheckedOff(value);
	}
}
