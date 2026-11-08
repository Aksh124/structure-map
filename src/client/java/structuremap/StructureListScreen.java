package structuremap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import structuremap.StructureHistory.Tag;

/**
 * Every structure found with /structurelocate, as a table: structure, coordinates, distance and a
 * god apple tag for pyramids and ancient cities. Filter by type, sort by distance, type or when it
 * was found. Clicking a row copies its coordinates; each row has a Waypoint button, and pyramids
 * and ancient cities a Chests button.
 */
public final class StructureListScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int DARK = 0xFF707070;
	private static final int GOLD = 0xFFFFC233;
	private static final String ALL = "All structures";

	public enum Sort {
		CLOSEST("Closest first"), TYPE("By type"), NEWEST("Newest first"), MOST_APPLES("Most god apples");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private final Screen parent;
	private List<StructureHistory.Entry> all = List.of();
	private StructureList list;
	private CycleButton<String> typeButton;
	private CycleButton<Sort> sortButton;
	private CycleButton<Boolean> applesButton;
	private Button clearButton;
	private String status;
	private boolean clearArmed;
	private int ticks;
	private String type = ALL;
	private Sort sort = Sort.CLOSEST;
	private boolean applesOnly;
	private boolean hideDone;
	private CycleButton<Boolean> doneButton;

	public StructureListScreen(Screen parent) {
		super(Component.literal("Located structures"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		reload();
		// Checks a closed game didn't finish get done now.
		for (StructureHistory.Entry entry : all) {
			if (entry.tag() == Tag.CHECKING) {
				StructureMapClient.checkLoot(entry);
			}
		}

		int controlsWidth = Math.min(width - 20, 500);
		int left = (width - controlsWidth) / 2;
		int third = (controlsWidth - 12) / 4;
		List<String> types = new ArrayList<>();
		types.add(ALL);
		types.addAll(new TreeSet<>(all.stream().map(StructureHistory.Entry::id).toList()));
		if (!types.contains(type)) {
			type = ALL;
		}
		typeButton = addRenderableWidget(CycleButton.builder(StructureListScreen::typeLabel, type).withValues(types).displayOnlyValue()
			.create(left, 24, third, 20, Component.literal("Type"), (button, value) -> {
				type = value;
				refresh();
			}));
		sortButton = addRenderableWidget(CycleButton.builder((Sort value) -> Component.literal(value.label), sort)
			.withValues(Sort.values()).displayOnlyValue()
			.create(left + third + 4, 24, third, 20, Component.literal("Sort"), (button, value) -> {
				sort = value;
				refresh();
			}));
		applesButton = addRenderableWidget(CycleButton.onOffBuilder(applesOnly)
			.create(left + 2 * (third + 4), 24, third, 20, Component.literal("God apples only"), (button, value) -> {
				applesOnly = value;
				refresh();
			}));
		doneButton = addRenderableWidget(CycleButton.builder((Boolean hide) -> Component.literal(hide ? "Hidden" : "Shown"), hideDone)
			.withValues(false, true)
			.create(left + 3 * (third + 4), 24, third, 20, Component.literal("Checked off"), (button, value) -> {
				hideDone = value;
				refresh();
			}));
		doneButton.setTooltip(Tooltip.create(Component.literal("Show or hide the structures you've checked off."
			+ " Searches skip them either way.")));

		list = addRenderableWidget(new StructureList(mc, Math.min(width - 10, 540), height - 64 - 54, 64));
		list.setX((width - list.getWidth()) / 2);

		clearButton = addRenderableWidget(Button.builder(Component.literal("Clear list"), button -> clear())
			.bounds(width / 2 - 154, height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Settings"), button -> minecraft.gui.setScreen(new StructureMapSettingsScreen(this)))
			.bounds(width / 2 - 50, height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(width / 2 + 54, height - 28, 100, 20).build());
		refresh();
	}

	private void reload() {
		Minecraft mc = Minecraft.getInstance();
		Long seed = StructureMapClient.seed();
		if (seed == null || mc.level == null) {
			all = List.of();
			status = "No seed yet, so nothing has been located here.";
			return;
		}
		String dimension = mc.level.dimension().identifier().toString();
		all = StructureHistory.entries(seed).stream().filter(entry -> entry.dimension().equals(dimension)).toList();
	}

	/** Applies the filter and sort to the list. */
	private void refresh() {
		BlockPos here = playerPos();
		List<StructureHistory.Entry> shown = new ArrayList<>();
		for (StructureHistory.Entry entry : all) {
			if ((type.equals(ALL) || entry.id().equals(type)) && (!applesOnly || entry.tag() == Tag.GOD_APPLE) && !(hideDone && entry.done())) {
				shown.add(entry);
			}
		}
		Comparator<StructureHistory.Entry> closest = Comparator.comparingDouble(entry -> distanceSqr(here, shownPos(entry)));
		shown.sort(switch (sort) {
			case CLOSEST -> closest;
			case TYPE -> Comparator.comparing((StructureHistory.Entry entry) -> displayName(entry.id())).thenComparing(closest);
			case NEWEST -> Comparator.comparingLong(StructureHistory.Entry::found).reversed();
			// Most god apples first; everything without any after them, closest first.
			case MOST_APPLES -> Comparator.comparingInt((StructureHistory.Entry entry) -> -apples(entry)).thenComparing(closest);
		});
		list.show(shown);
		if (status == null || !(status.startsWith("Copied") || status.startsWith("Checked off") || status.startsWith("Unchecked"))) {
			status = all.isEmpty() ? (StructureMapClient.seed() == null ? status
				: "Nothing located in this dimension yet. Structures you find with /structurelocate appear here.")
				: shown.size() + " of " + all.size() + " shown. Click a row to copy its coordinates.";
		}
	}

	@Override
	public void tick() {
		// Pick up god apple checks as they finish.
		if (++ticks % 20 == 0 && all.stream().anyMatch(entry -> entry.tag() == Tag.CHECKING)) {
			reload();
			refresh();
		}
	}

	private void clear() {
		Long seed = StructureMapClient.seed();
		if (seed == null) {
			return;
		}
		if (!clearArmed) {
			clearArmed = true;
			clearButton.setMessage(Component.literal("Click again to clear").withStyle(ChatFormatting.RED));
			return;
		}
		int removed = StructureHistory.clear(seed);
		clearArmed = false;
		clearButton.setMessage(Component.literal("Clear list"));
		reload();
		status = "Cleared " + removed + " structure" + (removed == 1 ? "" : "s") + ".";
		refresh();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 8, WHITE);
		int[] columns = list.columns();
		int y = 52;
		graphics.text(font, Component.literal("Structure"), columns[0], y, GRAY);
		graphics.text(font, Component.literal("Coordinates"), columns[1], y, GRAY);
		graphics.text(font, Component.literal("Distance"), columns[2], y, GRAY);
		graphics.text(font, Component.literal(sort == Sort.MOST_APPLES ? "God apples ▼" : "God apples"), columns[3], y,
			sort == Sort.MOST_APPLES ? WHITE : GRAY);
		if (status != null) {
			graphics.centeredText(font, Component.literal(status), width / 2, height - 46, GRAY);
		}
	}

	/** Clicking the God apples heading sorts by most god apples. */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int[] columns = list.columns();
		if (event.y() >= 50 && event.y() < 62 && event.x() >= columns[3] && event.x() < columns[3] + font.width("God apples ▼")) {
			setSort(Sort.MOST_APPLES);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private static int apples(StructureHistory.Entry entry) {
		return entry.tag() == Tag.GOD_APPLE ? entry.apples() : 0;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// Formatting

	private static Component typeLabel(String id) {
		return Component.literal(id.equals(ALL) ? ALL : displayName(id));
	}

	/** "minecraft:village_plains" to "Village Plains". */
	public static String displayName(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		StringBuilder name = new StringBuilder();
		for (String word : path.split("[_/]")) {
			if (!word.isEmpty()) {
				name.append(name.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return name.toString();
	}

	/** Where a row points: the god apple's chest if there is one, otherwise the structure. */
	public static BlockPos shownPos(StructureHistory.Entry entry) {
		return entry.tag() == Tag.GOD_APPLE && entry.chest() != null ? entry.chest() : entry.pos();
	}

	public static String coordinates(StructureHistory.Entry entry) {
		BlockPos pos = shownPos(entry);
		boolean hasY = entry.hasY() || (entry.tag() == Tag.GOD_APPLE && entry.chest() != null);
		return pos.getX() + (hasY ? " " + pos.getY() + " " : " ~ ") + pos.getZ();
	}

	private static BlockPos playerPos() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
	}

	private static double distanceSqr(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	// For the game test.

	public List<StructureHistory.Entry> shownEntries() {
		return list.children().stream().map(row -> row.entry).toList();
	}

	public void setType(String id) {
		typeButton.setValue(id);
		type = id;
		refresh();
	}

	public void setSort(Sort value) {
		sortButton.setValue(value);
		sort = value;
		refresh();
	}

	public void setApplesOnly(boolean value) {
		applesButton.setValue(value);
		applesOnly = value;
		refresh();
	}

	/** Clicks the God apples heading the way the mouse would. */
	public void clickApplesHeading() {
		int[] columns = list.columns();
		mouseClicked(new MouseButtonEvent(columns[3] + 4, 55, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
	}

	public Sort sort() {
		return sort;
	}

	public void setDone(int index, boolean done) {
		list.children().get(index).setDone(done);
	}

	public void setHideDone(boolean value) {
		doneButton.setValue(value);
		hideDone = value;
		refresh();
	}

	public void clickRow(int index) {
		list.children().get(index).copy();
	}

	public void openChests(int index) {
		list.children().get(index).openChests();
	}

	public String status() {
		return status;
	}

	final class StructureList extends ContainerObjectSelectionList<StructureList.Row> {
		StructureList(Minecraft mc, int listWidth, int listHeight, int y) {
			super(mc, listWidth, listHeight, y, 22);
		}

		void show(List<StructureHistory.Entry> entries) {
			List<Row> rows = new ArrayList<>();
			for (StructureHistory.Entry entry : entries) {
				rows.add(new Row(entry));
			}
			replaceEntries(rows);
		}

		@Override
		public int getRowWidth() {
			return width - 14;
		}

		/** Left edge of each text column. */
		int[] columns() {
			// After the checkbox.
			int left = getRowLeft() + 24;
			int text = getRowWidth() - 116;
			return new int[] {left, left + (int) (text * 0.29), left + (int) (text * 0.60), left + (int) (text * 0.75)};
		}

		final class Row extends ContainerObjectSelectionList.Entry<Row> {
			final StructureHistory.Entry entry;
			private final Button chests;
			private final Button waypoint;
			private final Checkbox check;
			private final List<AbstractWidget> buttons = new ArrayList<>();

			Row(StructureHistory.Entry entry) {
				this.entry = entry;
				check = Checkbox.builder(Component.empty(), font).selected(entry.done()).onValueChange((box, value) -> setDone(value))
					.tooltip(Tooltip.create(Component.literal("Check it off: searches skip it, and its waypoint is updated as set in Settings")))
					.build();
				buttons.add(check);
				if (entry.hasLoot()) {
					chests = Button.builder(Component.literal("Chests"), button -> openChests()).size(50, 20).build();
					chests.setTooltip(Tooltip.create(Component.literal("What each chest holds")));
					buttons.add(chests);
				} else {
					chests = null;
				}
				waypoint = Button.builder(Component.literal("Waypoint"), button -> addWaypoint()).size(60, 20).build();
				waypoint.active = Waypoints.available();
				waypoint.setTooltip(Tooltip.create(Component.literal(Waypoints.available() ? "Add a waypoint in Xaero's Minimap"
					: "Needs Xaero's Minimap")));
				buttons.add(waypoint);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int[] columns = columns();
				int y = getContentY() + 6;
				// Up to the Chests button, or where it would be.
				int lootWidth = getContentRight() - 116 - columns[3];
				double distance = Math.sqrt(distanceSqr(playerPos(), shownPos(entry)));
				check.setPosition(getContentX() + 2, getContentY() + 1);
				check.extractRenderState(graphics, mouseX, mouseY, partialTick);
				// Checked-off rows are greyed out.
				int main = entry.done() ? DARK : WHITE;
				int dim = entry.done() ? DARK : GRAY;
				graphics.text(font, Component.literal(fit(displayName(entry.id()), columns[1] - columns[0] - 4)), columns[0], y, main);
				graphics.text(font, Component.literal(fit(coordinates(entry), columns[2] - columns[1] - 4)), columns[1], y, main);
				graphics.text(font, Component.literal(String.format(Locale.ROOT, "%,d m", Math.round(distance))), columns[2], y, dim);
				switch (entry.tag()) {
					case GOD_APPLE -> graphics.text(font, Component.literal(fit("God apple" + (entry.apples() > 1 ? " ×" + entry.apples() : ""), lootWidth)),
						columns[3], y, entry.done() ? DARK : GOLD);
					case REGULAR -> graphics.text(font, Component.literal("Regular"), columns[3], y, dim);
					case CHECKING -> graphics.text(font, Component.literal("Checking…"), columns[3], y, DARK);
					case NONE -> { }
				}
				int right = getContentRight();
				waypoint.setPosition(right - 60, getContentY());
				waypoint.extractRenderState(graphics, mouseX, mouseY, partialTick);
				if (chests != null) {
					chests.setPosition(right - 60 - 54, getContentY());
					chests.extractRenderState(graphics, mouseX, mouseY, partialTick);
				}
			}

			private String fit(String text, int maxWidth) {
				return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (super.mouseClicked(event, doubleClick)) {
					return true;
				}
				copy();
				return true;
			}

			void copy() {
				String text = coordinates(entry);
				minecraft.keyboardHandler.setClipboard(text);
				status = "Copied " + text + " (" + displayName(entry.id()) + ").";
			}

			void setDone(boolean done) {
				status = StructureMapClient.setChecked(entry, done);
				reload();
				refresh();
			}

			void openChests() {
				minecraft.gui.setScreen(new ChestContentsScreen(StructureListScreen.this, entry));
			}

			private void addWaypoint() {
				if (minecraft.player == null) {
					return;
				}
				BlockPos pos = shownPos(entry);
				minecraft.player.connection.sendCommand("structurelocate waypoints add structure " + pos.getX() + " " + pos.getZ() + " " + entry.id());
				status = "Added a waypoint for the " + displayName(entry.id()) + " at " + coordinates(entry) + ".";
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return buttons;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return buttons;
			}
		}
	}
}
