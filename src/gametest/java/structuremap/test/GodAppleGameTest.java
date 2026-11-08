package structuremap.test;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import structuremap.ChestContentsScreen;
import structuremap.StructureHistory;
import structuremap.StructureListScreen;
import structuremap.StructureMapClient;
import structuremap.StructureMapSettings;
import structuremap.StructureMapSettingsScreen;
import structuremap.Waypoints;
import structuremap.StructureMapClient.ChestFind;
import structuremap.StructureMapClient.LootFind;

/**
 * The chest simulation against the real thing: for real desert pyramids and an ancient city, the
 * server generates them and every simulated chest must match a real one (position, loot table and
 * loot seed), and opening the real chest must give the simulated items. Then the god apple search
 * must point at a chest that really holds one.
 */
public class GodAppleGameTest implements FabricClientGameTest {
	private static final long SEED = -6_421_009_337_154_802_113L;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(Long.toString(SEED)))
			.create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode spectator @a");
			check(Long.valueOf(SEED).equals(StructureMapClient.seed()), "Structure Map doesn't have the world seed");

			// The nearest three pyramids, then an ancient city.
			int chests = 0;
			for (StructureMapClient.Found pyramid : StructureMapClient.locateStructures("minecraft:desert_pyramid", Level.OVERWORLD, BlockPos.ZERO, 3).join()) {
				chests += compare(world, BuiltinStructures.DESERT_PYRAMID, pyramid.pos());
			}
			for (StructureMapClient.Found city : StructureMapClient.locateStructures("minecraft:ancient_city", Level.OVERWORLD, BlockPos.ZERO, 2).join()) {
				chests += compare(world, BuiltinStructures.ANCIENT_CITY, city.pos());
			}
			log("all " + chests + " simulated chests match the server's, seeds and items");

			// The search itself: its answer must be a real god apple.
			for (String structure : new String[] {"minecraft:desert_pyramid", "minecraft:ancient_city"}) {
				long started = System.nanoTime();
				var search = StructureMapClient.findLoot(structure, Items.ENCHANTED_GOLDEN_APPLE, Level.OVERWORLD, BlockPos.ZERO, 1,
					structure.endsWith("pyramid") ? 300 : 30);
				// Wait a tick at a time, so the game window keeps responding.
				for (int tick = 0; tick < 20 * 300 && !search.isDone(); tick++) {
					context.waitTick();
				}
				check(search.isDone(), structure + " search took over 5 minutes");
				List<LootFind> found = search.join();
				log(String.format(java.util.Locale.ROOT, "%s search took %.1fs", structure, (System.nanoTime() - started) / 1e9));
				check(!found.isEmpty(), "no " + structure + " with a god apple found");
				LootFind find = found.getFirst();
				BlockPos chest = find.chests().getFirst().pos();
				int real = world.getServer().computeOnServer(server -> {
					ServerLevel level = server.overworld();
					level.getChunk(chest);
					return count(open(level, chest), Items.ENCHANTED_GOLDEN_APPLE.builtInRegistryHolder().key().identifier());
				});
				check(real == find.chests().getFirst().items().stream().filter(s -> s.is(Items.ENCHANTED_GOLDEN_APPLE)).mapToInt(ItemStack::getCount).sum(),
					"the server's chest at " + chest + " has " + real + " god apples, not what was predicted");
				log(structure + ": god apple search says " + chest + ", and the server's chest really has " + real);
			}

			context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate godapples pyramid 2"));
			context.waitTicks(200);
			context.takeScreenshot("structuremap-godapples");

			structureList(context);
			checkingOff(context);
		}
	}

	/** Simulates the structure nearest the centre and checks it against what the server generates. Returns the chest count. */
	private static int compare(TestSingleplayerContext world, ResourceKey<Structure> key, BlockPos centre) {
		BlockPos start = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			Holder<Structure> holder = server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
			Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, HolderSet.direct(holder), centre, 100, false);
			return result.getFirst();
		});
		if (!COMPARED.add(start)) {
			log(key.identifier() + " at " + start + " was already compared");
			return 0;
		}
		int chunkX = SectionPos.blockToSectionCoord(start.getX());
		int chunkZ = SectionPos.blockToSectionCoord(start.getZ());
		List<ChestFind> simulated = StructureMapClient.simulateChests(key.identifier(), Level.OVERWORLD, chunkX, chunkZ).join();
		check(!simulated.isEmpty(), "no chests simulated for " + key.identifier() + " at chunk " + chunkX + ", " + chunkZ);

		List<String> problems = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			// Generate everything the structure covers, as a player walking up would.
			for (int cx = chunkX - 10; cx <= chunkX + 10; cx++) {
				for (int cz = chunkZ - 10; cz <= chunkZ + 10; cz++) {
					level.getChunk(cx, cz);
				}
			}
			List<String> found = new ArrayList<>();
			int[] uncertainMatched = new int[2];
			for (ChestFind chest : simulated) {
				if (!chest.certain()) {
					// Only counted: these can differ, which is why the search leaves them out.
					BlockEntity entity = level.getBlockEntity(chest.pos());
					uncertainMatched[1]++;
					if (entity instanceof RandomizableContainer container && container.getLootTableSeed() == chest.seed()) {
						uncertainMatched[0]++;
					}
					continue;
				}
				BlockEntity entity = level.getBlockEntity(chest.pos());
				if (!(entity instanceof RandomizableContainer container) || container.getLootTable() == null) {
					found.add("no loot chest at " + chest.pos());
					continue;
				}
				if (!container.getLootTable().identifier().equals(chest.table()) || container.getLootTableSeed() != chest.seed()) {
					found.add("chest at " + chest.pos() + " is " + container.getLootTable().identifier() + "/" + container.getLootTableSeed()
						+ ", simulated " + chest.table() + "/" + chest.seed());
					continue;
				}
				Map<String, Integer> realItems = totals(open(level, chest.pos()));
				Map<String, Integer> simulatedItems = totals(chest.items());
				if (!realItems.equals(simulatedItems)) {
					found.add("chest at " + chest.pos() + " holds " + realItems + ", simulated " + simulatedItems);
				}
			}
			if (uncertainMatched[1] > 0) {
				log(uncertainMatched[1] + " uncertain chest(s), " + uncertainMatched[0] + " of them matched anyway");
			}
			return found;
		});
		check(problems.isEmpty(), key.identifier() + " at chunk " + chunkX + ", " + chunkZ + ": " + problems);
		long certain = simulated.stream().filter(ChestFind::certain).count();
		log(key.identifier() + " at chunk " + chunkX + ", " + chunkZ + ": all " + certain + " certain chests match, seeds and items ("
			+ (simulated.size() - certain) + " uncertain)");
		return simulated.size();
	}

	/** Checking off: waypoints disabled or deleted as set, searches skip it, hiding, unchecking, and the chat commands. */
	private static void checkingOff(ClientGameTestContext context) {
		StructureHistory.Entry village = StructureHistory.entries(SEED).stream()
			.filter(entry -> entry.id().equals("minecraft:village_plains")).findFirst().orElseThrow();
		Identifier villageId = Identifier.parse(village.id());
		BlockPos villagePos = village.pos();
		context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate waypoints add structure " + villagePos.getX() + " "
			+ villagePos.getZ() + " " + village.id()));
		context.waitTicks(5);
		check(Boolean.FALSE.equals(context.computeOnClient(mc -> Waypoints.waypointDisabled(villageId, villagePos))), "no village waypoint to start with");

		// The setting, from its own screen.
		context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate settings"));
		waitUntil(context, 5, () -> net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof StructureMapSettingsScreen,
			() -> "settings didn't open");
		context.runOnClient(mc -> {
			((StructureMapSettingsScreen) mc.gui.screen()).choose(StructureMapSettings.CheckedOff.DISABLE);
			mc.gui.screen().onClose();
		});

		// Check it off in the menu: disabled waypoint, and the next search finds a different village.
		context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate menu"));
		waitUntil(context, 5, () -> net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof StructureListScreen, () -> "menu didn't open");
		context.waitTicks(2);
		String status = context.computeOnClient(mc -> {
			list().setDone(rowOf(village), true);
			return list().status();
		});
		check(StructureHistory.isDone(SEED, "minecraft:overworld", village.id(), village.chunkX(), village.chunkZ()), "not checked off");
		check(status.startsWith("Checked off the Village Plains"), "menu status: " + status);
		check(Boolean.TRUE.equals(context.computeOnClient(mc -> Waypoints.waypointDisabled(villageId, villagePos))), "waypoint not disabled: " + status);
		log("checked off in the menu: " + status);
		context.takeScreenshot("structuremap-checked-off");

		BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition());
		StructureMapClient.Found next = StructureMapClient.locateStructure("minecraft:village_plains", Level.OVERWORLD, origin).join();
		check(next.pos() != null && !next.pos().equals(villagePos), "search still found the checked-off village: " + next.pos());
		log("searching again skips it: next village at " + next.pos() + " instead of " + villagePos);

		List<StructureHistory.Entry> shown = context.computeOnClient(mc -> {
			list().setHideDone(true);
			return list().shownEntries();
		});
		check(shown.stream().noneMatch(StructureHistory.Entry::done), "hidden switch still shows checked-off rows");
		context.runOnClient(mc -> list().setHideDone(false));

		context.runOnClient(mc -> list().setDone(rowOf(village), false));
		check(Boolean.FALSE.equals(context.computeOnClient(mc -> Waypoints.waypointDisabled(villageId, villagePos))), "unchecking didn't re-enable it");
		log("hide switch works; unchecking switched the waypoint back on");

		// Delete instead.
		StructureMapSettings.setCheckedOff(StructureMapSettings.CheckedOff.DELETE);
		context.runOnClient(mc -> list().setDone(rowOf(village), true));
		check(context.computeOnClient(mc -> Waypoints.waypointDisabled(villageId, villagePos)) == null, "waypoint not deleted");
		context.runOnClient(mc -> list().setDone(rowOf(village), false));
		check(Boolean.FALSE.equals(context.computeOnClient(mc -> Waypoints.waypointDisabled(villageId, villagePos))), "unchecking didn't put it back");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		StructureMapSettings.setCheckedOff(StructureMapSettings.CheckedOff.DISABLE);
		log("delete setting: checking off deleted the waypoint, unchecking put it back");

		// The chat buttons' commands.
		StructureHistory.Entry stronghold = StructureHistory.entries(SEED).stream()
			.filter(entry -> entry.id().equals("minecraft:stronghold")).findFirst().orElseThrow();
		String done = "structurelocate done " + stronghold.pos().getX() + " " + stronghold.pos().getZ() + " minecraft:stronghold";
		context.runOnClient(mc -> mc.player.connection.sendCommand(done));
		context.waitTicks(2);
		check(StructureHistory.isDone(SEED, "minecraft:overworld", "minecraft:stronghold", stronghold.chunkX(), stronghold.chunkZ()), "[Done] didn't check it off");
		context.runOnClient(mc -> mc.player.connection.sendCommand(done.replace(" done ", " undone ")));
		context.waitTicks(2);
		check(!StructureHistory.isDone(SEED, "minecraft:overworld", "minecraft:stronghold", stronghold.chunkX(), stronghold.chunkZ()), "[Undo] didn't");
		log("[Done] and [Undo] from chat work");

		// The god apple search skips a checked-off city.
		LootFind first = StructureMapClient.findLoot("minecraft:ancient_city", Items.ENCHANTED_GOLDEN_APPLE, Level.OVERWORLD, BlockPos.ZERO, 1, 300).join().getFirst();
		context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate done " + first.structure().getX() + " " + first.structure().getZ()
			+ " minecraft:ancient_city"));
		context.waitTicks(2);
		LootFind second = StructureMapClient.findLoot("minecraft:ancient_city", Items.ENCHANTED_GOLDEN_APPLE, Level.OVERWORLD, BlockPos.ZERO, 1, 300).join().getFirst();
		check(!second.structure().equals(first.structure()), "god apple search still found the checked-off city");
		log("god apple search skips the checked-off city at " + first.structure() + ", next is " + second.structure());
		// Leave nothing checked off for the /locate comparison that runs next.
		StructureHistory.clear(SEED);
	}

	private static int rowOf(StructureHistory.Entry entry) {
		List<StructureHistory.Entry> rows = list().shownEntries();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).id().equals(entry.id()) && rows.get(i).chunkX() == entry.chunkX() && rows.get(i).chunkZ() == entry.chunkZ()) {
				return i;
			}
		}
		throw new AssertionError("no row for " + entry);
	}
	/** The located structures menu: history from commands, tags, filters, sorting, copying and the chest view. */
	private static void structureList(ClientGameTestContext context) {
		StructureHistory.clear(SEED);
		for (String command : new String[] {"structurelocate structure minecraft:village_plains", "structurelocate structure minecraft:stronghold",
			"structurelocate structure minecraft:desert_pyramid", "structurelocate godapples ancient_city 2"}) {
			context.runOnClient(mc -> mc.player.connection.sendCommand(command));
			context.waitTicks(5);
		}
		waitUntil(context, 120, () -> {
			List<StructureHistory.Entry> entries = StructureHistory.entries(SEED);
			return entries.size() >= 5 && entries.stream().noneMatch(entry -> entry.tag() == StructureHistory.Tag.CHECKING);
		}, () -> "history: " + StructureHistory.entries(SEED));
		List<StructureHistory.Entry> history = StructureHistory.entries(SEED);
		StructureHistory.Entry pyramid = history.stream().filter(entry -> entry.id().equals("minecraft:desert_pyramid")).findFirst().orElseThrow();
		check(pyramid.tag() == StructureHistory.Tag.GOD_APPLE && pyramid.apples() == 1, "nearest pyramid not tagged as a god apple: " + pyramid);
		check(history.stream().filter(entry -> entry.id().equals("minecraft:ancient_city")).allMatch(entry -> entry.tag() == StructureHistory.Tag.GOD_APPLE),
			"god apple cities not tagged");
		check(history.stream().filter(entry -> !entry.hasLoot()).allMatch(entry -> entry.tag() == StructureHistory.Tag.NONE), "village or stronghold tagged");
		log("history from commands: " + history.size() + " structures, pyramid tagged god apple by its background check");

		context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate menu"));
		waitUntil(context, 5, () -> net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof StructureListScreen, () -> "menu didn't open");
		context.waitTicks(2);
		List<StructureHistory.Entry> shown = context.computeOnClient(mc -> list().shownEntries());
		check(shown.size() == history.size(), "menu shows " + shown.size() + " of " + history.size());
		BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition());
		for (int i = 1; i < shown.size(); i++) {
			check(distance(origin, shown.get(i - 1)) <= distance(origin, shown.get(i)), "not closest first: " + shown);
		}
		context.takeScreenshot("structuremap-list");

		List<StructureHistory.Entry> pyramids = context.computeOnClient(mc -> {
			list().setType("minecraft:desert_pyramid");
			return list().shownEntries();
		});
		check(!pyramids.isEmpty() && pyramids.stream().allMatch(entry -> entry.id().equals("minecraft:desert_pyramid")), "type filter: " + pyramids);
		List<StructureHistory.Entry> apples = context.computeOnClient(mc -> {
			list().setType("All structures");
			list().setApplesOnly(true);
			return list().shownEntries();
		});
		check(!apples.isEmpty() && apples.stream().allMatch(entry -> entry.tag() == StructureHistory.Tag.GOD_APPLE), "god apples only: " + apples);
		List<String> byType = context.computeOnClient(mc -> {
			list().setApplesOnly(false);
			list().setSort(StructureListScreen.Sort.TYPE);
			return list().shownEntries().stream().map(StructureHistory.Entry::id).toList();
		});
		List<String> sorted = new ArrayList<>(byType);
		sorted.sort(java.util.Comparator.comparing(id -> id.substring(id.indexOf(':') + 1)));
		check(byType.equals(sorted), "not sorted by type: " + byType);
		List<StructureHistory.Entry> byApples = context.computeOnClient(mc -> {
			list().clickApplesHeading();
			return list().shownEntries();
		});
		check(context.computeOnClient(mc -> list().sort()) == StructureListScreen.Sort.MOST_APPLES, "heading click didn't sort");
		for (int i = 1; i < byApples.size(); i++) {
			check(apples(byApples.get(i - 1)) >= apples(byApples.get(i)), "not most god apples first: " + byApples);
		}
		check(apples(byApples.getLast()) == 0, "structures without god apples aren't at the bottom: " + byApples);
		log("clicking the God apples heading sorts most first, none last: "
			+ byApples.stream().map(entry -> entry.id().substring(10) + "=" + apples(entry)).toList());
		context.takeScreenshot("structuremap-most-apples");
		log("menu: closest first, type filter, god apples only and sort by type all work");

		String copied = context.computeOnClient(mc -> {
			list().setSort(StructureListScreen.Sort.CLOSEST);
			list().clickRow(0);
			// The test game's clipboard isn't the real one, so check what the screen says it copied.
			return list().status();
		});
		check(copied.startsWith("Copied " + StructureListScreen.coordinates(shown.getFirst()) + " ("), "clicking a row: '" + copied + "'");
		log("clicking a row copied " + copied);

		int pyramidRow = context.computeOnClient(mc -> {
			List<StructureHistory.Entry> rows = list().shownEntries();
			for (int i = 0; i < rows.size(); i++) {
				if (rows.get(i).id().equals("minecraft:desert_pyramid") && rows.get(i).tag() == StructureHistory.Tag.GOD_APPLE) {
					return i;
				}
			}
			return -1;
		});
		context.runOnClient(mc -> list().openChests(pyramidRow));
		waitUntil(context, 30, () -> net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof ChestContentsScreen chests
			&& chests.chests() != null, () -> "chest view didn't load");
		int chestApples = context.computeOnClient(mc -> ((ChestContentsScreen) mc.gui.screen()).chests().stream()
			.flatMap(chest -> chest.items().stream()).filter(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE)).mapToInt(ItemStack::getCount).sum());
		check(chestApples == 1, "chest view shows " + chestApples + " god apples");
		context.waitTicks(2);
		context.takeScreenshot("structuremap-chests");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof StructureListScreen), "Back didn't return to the list");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		check(java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("structuremap-history.txt")),
			"history not saved");
		log("chest view shows the god apple, Back returns to the list, history saved");
	}

	private static int apples(StructureHistory.Entry entry) {
		return entry.tag() == StructureHistory.Tag.GOD_APPLE ? entry.apples() : 0;
	}

	private static StructureListScreen list() {
		return (StructureListScreen) net.minecraft.client.Minecraft.getInstance().gui.screen();
	}

	private static double distance(BlockPos origin, StructureHistory.Entry entry) {
		BlockPos pos = StructureListScreen.shownPos(entry);
		return Math.hypot(origin.getX() - pos.getX(), origin.getZ() - pos.getZ());
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, java.util.function.BooleanSupplier done,
		java.util.function.Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
	}

	private static final java.util.Set<BlockPos> COMPARED = new java.util.HashSet<>();

	/** What a real chest holds once its loot is rolled, as when a player opens it. */
	private static List<ItemStack> open(ServerLevel level, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof RandomizableContainer container)) {
			return List.of();
		}
		container.unpackLootTable(null);
		List<ItemStack> items = new ArrayList<>();
		Container inventory = (Container) entity;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (!inventory.getItem(slot).isEmpty()) {
				items.add(inventory.getItem(slot));
			}
		}
		return items;
	}

	private static int count(List<ItemStack> items, Identifier item) {
		return totals(items).getOrDefault(item.toString(), 0);
	}

	/** Item id to total count: the server splits stacks across slots, so only totals compare. */
	private static Map<String, Integer> totals(List<ItemStack> items) {
		Map<String, Integer> totals = new TreeMap<>();
		for (ItemStack stack : items) {
			totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return totals;
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new AssertionError(failure);
		}
	}

	private static void log(String text) {
		System.out.println("[structuremap-gametest] " + text);
	}

}
