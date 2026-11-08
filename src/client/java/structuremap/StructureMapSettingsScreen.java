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
	private Button useBorder;
	private Button removeBorder;
	private String status = "";

	public StructureMapSettingsScreen(Screen parent) {
		super(Component.literal("Structure Map settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 155;
		int top = height / 2 - 60;
		checkedOff = addRenderableWidget(CycleButton.builder((CheckedOff value) -> Component.literal(value.label), StructureMapSettings.checkedOff())
			.withValues(CheckedOff.values())
			.create(x, top, 310, 20, Component.literal("Checked-off waypoints"),
				(button, value) -> StructureMapSettings.setCheckedOff(value)));
		checkedOff.setTooltip(Tooltip.create(Component.literal("What happens to a structure's Xaero waypoint when you check it off."
			+ " Disabled waypoints stay in Xaero's list, hidden. Unchecking puts it back either way.")));

		useBorder = addRenderableWidget(Button.builder(Component.literal("Use this world's border"), button -> useWorldBorder())
			.bounds(x, top + 66, 152, 20).build());
		useBorder.setTooltip(Tooltip.create(Component.literal("Searches skip structures outside the world border, and located"
			+ " structures and Structure Map waypoints outside it are deleted. Waypoints you added yourself stay."
			+ " For a custom size, use /structurelocate border <radius> or border <x> <z> <radius>.")));
		removeBorder = addRenderableWidget(Button.builder(Component.literal("Remove border"), button -> removeBorder())
			.bounds(x + 158, top + 66, 152, 20).build());
		updateButtons();

		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(width / 2 - 75, top + 120, 150, 20).build());
	}

	private void updateButtons() {
		boolean inWorld = minecraft.level != null;
		useBorder.active = inWorld;
		removeBorder.active = inWorld && StructureMapClient.currentBorder() != null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = height / 2 - 60;
		graphics.centeredText(font, title, width / 2, top - 24, 0xFFFFFFFF);
		if (!Waypoints.available()) {
			graphics.centeredText(font, Component.literal("Xaero's Minimap isn't installed, so this does nothing yet."),
				width / 2, top + 26, 0xFFA0A0A0);
		}
		graphics.centeredText(font, Component.literal(borderText()), width / 2, top + 52, 0xFFFFFFFF);
		if (!status.isEmpty()) {
			// Long messages get two lines.
			int line = 0;
			for (var part : font.split(Component.literal(status), 400)) {
				if (line < 2) {
					graphics.centeredText(font, part, width / 2, top + 92 + line * 10, 0xFFA0FFA0);
				}
				line++;
			}
		}
	}

	private String borderText() {
		if (minecraft.level == null) {
			return "World border: join a world to set one";
		}
		WorldBorders.Border border = StructureMapClient.currentBorder();
		return border == null ? "World border: none, searches cover the whole world" : "World border: " + border.describe();
	}

	private void useWorldBorder() {
		status = StructureMapClient.setBorder(StructureMapClient.worldBorder());
		updateButtons();
	}

	private void removeBorder() {
		status = StructureMapClient.setBorder(null);
		updateButtons();
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

	/** For the game test: presses "Use this world's border" and returns the message. */
	public String pressUseWorldBorder() {
		useWorldBorder();
		return status;
	}

	/** For the game test: presses "Remove border" and returns the message. */
	public String pressRemoveBorder() {
		removeBorder();
		return status;
	}
}
