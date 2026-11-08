package structuremap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import structuremap.StructureHistory.Entry;
import structuremap.StructureMapClient.ChestFind;

/** What each chest of a listed pyramid or ancient city holds when first opened. Click a chest to copy where it is. */
public final class ChestContentsScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int GOLD = 0xFFFFC233;

	private final Screen parent;
	private final Entry entry;
	private Lines lines;
	private List<ChestFind> chests;
	private String status = "Working out the chests…";

	public ChestContentsScreen(Screen parent, Entry entry) {
		super(Component.literal(StructureListScreen.displayName(entry.id()) + " chests"));
		this.parent = parent;
		this.entry = entry;
	}

	@Override
	protected void init() {
		lines = addRenderableWidget(new Lines(Minecraft.getInstance(), Math.min(width - 10, 420), height - 40 - 64, 40));
		lines.setX((width - lines.getWidth()) / 2);
		addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
		if (chests != null) {
			lines.show(chests);
			return;
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.dimension()));
		StructureMapClient.simulateChests(Identifier.parse(entry.id()), dimension, entry.chunkX(), entry.chunkZ())
			.whenComplete((found, error) -> minecraft.execute(() -> {
				if (error != null) {
					status = "Couldn't work out the chests: " + (error.getCause() != null ? error.getCause() : error).getMessage();
					return;
				}
				chests = found;
				lines.show(found);
				status = found.size() + " chest" + (found.size() == 1 ? "" : "s") + ". Click a chest to copy where it is."
					+ (entry.id().equals("minecraft:ancient_city") ? " Chests after a sculk patch can't be predicted and aren't listed." : "");
			}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 8, WHITE);
		graphics.centeredText(font, Component.literal(StructureListScreen.coordinates(entry)), width / 2, 20, GRAY);
		graphics.textWithWordWrap(font, Component.literal(status), width / 2 - 200, height - 56, 400, GRAY);
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
	public List<ChestFind> chests() {
		return chests;
	}

	public String status() {
		return status;
	}

	final class Lines extends ObjectSelectionList<Lines.Line> {
		Lines(Minecraft mc, int listWidth, int listHeight, int y) {
			super(mc, listWidth, listHeight, y, 12);
		}

		void show(List<ChestFind> found) {
			List<Line> rows = new ArrayList<>();
			for (ChestFind chest : found) {
				BlockPos pos = chest.pos();
				boolean apple = chest.items().stream().anyMatch(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE));
				rows.add(new Line("Chest at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + (apple ? "  (god apple!)" : ""),
					apple ? GOLD : WHITE, pos));
				// Same items added up: the server splits stacks across slots anyway.
				Map<String, Integer> totals = new LinkedHashMap<>();
				Map<String, Boolean> gold = new LinkedHashMap<>();
				for (ItemStack stack : chest.items()) {
					String name = stack.getHoverName().getString();
					totals.merge(name, stack.getCount(), Integer::sum);
					gold.put(name, stack.is(Items.ENCHANTED_GOLDEN_APPLE));
				}
				if (totals.isEmpty()) {
					rows.add(new Line("    (empty)", GRAY, pos));
				}
				for (Map.Entry<String, Integer> item : totals.entrySet()) {
					rows.add(new Line("    " + item.getKey() + " ×" + item.getValue(), gold.get(item.getKey()) ? GOLD : GRAY, pos));
				}
			}
			replaceEntries(rows);
		}

		@Override
		public int getRowWidth() {
			return width - 14;
		}

		final class Line extends ObjectSelectionList.Entry<Line> {
			private final String text;
			private final int color;
			private final BlockPos chest;

			Line(String text, int color, BlockPos chest) {
				this.text = text;
				this.color = color;
				this.chest = chest;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				graphics.text(font, Component.literal(text), getContentX() + 2, getContentY() + 2, color);
			}

			@Override
			public Component getNarration() {
				return Component.literal(text);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				String coords = chest.getX() + " " + chest.getY() + " " + chest.getZ();
				minecraft.keyboardHandler.setClipboard(coords);
				status = "Copied " + coords + ".";
				return true;
			}
		}
	}
}
