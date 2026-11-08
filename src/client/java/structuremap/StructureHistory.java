package structuremap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every structure the /structurelocate commands have found, per world seed, kept in
 * config/structuremap-history.txt so the list survives relaunching.
 */
public final class StructureHistory {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("structuremap-history.txt");

	/** Structures whose chests can be checked for god apples. */
	public static final List<String> LOOT_STRUCTURES = List.of("minecraft:desert_pyramid", "minecraft:ancient_city");

	public enum Tag {
		/** Not a structure with chests worth checking. */
		NONE,
		/** Its chests are being worked out. */
		CHECKING,
		GOD_APPLE,
		REGULAR
	}

	/**
	 * One found structure. {@code pos} is where /locate would point (no height unless {@code hasY});
	 * the chunk is the structure's start chunk. For a god apple, {@code chest} is the chest holding it.
	 * {@code done} means it was checked off: searches skip it.
	 */
	public record Entry(long seed, String dimension, String id, BlockPos pos, boolean hasY, int chunkX, int chunkZ, Tag tag,
		int apples, BlockPos chest, long found, boolean done) {
		String key() {
			return seed + "|" + dimension + "|" + id + "|" + chunkX + "|" + chunkZ;
		}

		public boolean hasLoot() {
			return LOOT_STRUCTURES.contains(id);
		}

		Entry withTag(Tag newTag, int newApples, BlockPos newChest) {
			return new Entry(seed, dimension, id, pos, hasY, chunkX, chunkZ, newTag, newApples, newChest, found, done);
		}

		Entry withDone(boolean newDone) {
			return new Entry(seed, dimension, id, pos, hasY, chunkX, chunkZ, tag, apples, chest, found, newDone);
		}
	}

	private static Map<String, Entry> entries;

	private StructureHistory() {}

	/** Everything found for this seed, in the order it was found. */
	public static synchronized List<Entry> entries(long seed) {
		load();
		List<Entry> list = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.seed() == seed) {
				list.add(entry);
			}
		}
		return list;
	}

	/** Adds a find, keeping what's already known about it. Returns the stored entry. */
	static synchronized Entry add(long seed, String dimension, String id, BlockPos pos, boolean hasY) {
		load();
		Entry fresh = new Entry(seed, dimension, id, pos, hasY, pos.getX() >> 4, pos.getZ() >> 4,
			LOOT_STRUCTURES.contains(id) ? Tag.CHECKING : Tag.NONE, 0, null, System.currentTimeMillis(), false);
		Entry existing = entries.get(fresh.key());
		if (existing != null) {
			return existing;
		}
		entries.put(fresh.key(), fresh);
		save();
		return fresh;
	}

	/** Records what a chest check found. */
	static synchronized void setLoot(Entry entry, int apples, BlockPos chest) {
		load();
		Entry current = entries.get(entry.key());
		if (current != null) {
			entries.put(entry.key(), current.withTag(apples > 0 ? Tag.GOD_APPLE : Tag.REGULAR, apples, chest));
			save();
		}
	}

	/** Checks a structure off, or back on. Searches skip checked-off structures. */
	public static synchronized void setDone(Entry entry, boolean done) {
		load();
		Entry current = entries.get(entry.key());
		if (current != null) {
			entries.put(entry.key(), current.withDone(done));
			save();
		}
	}

	/** Whether the structure starting in this chunk has been checked off. */
	public static synchronized boolean isDone(long seed, String dimension, String id, int chunkX, int chunkZ) {
		load();
		Entry entry = entries.get(seed + "|" + dimension + "|" + id + "|" + chunkX + "|" + chunkZ);
		return entry != null && entry.done();
	}

	/** How many structures are checked off for this seed, so searches know how many extra to look for. */
	public static synchronized int doneCount(long seed) {
		load();
		return (int) entries.values().stream().filter(entry -> entry.seed() == seed && entry.done()).count();
	}

	/** Forgets everything found for this seed. Returns how many were removed. */
	public static synchronized int clear(long seed) {
		load();
		int before = entries.size();
		entries.values().removeIf(entry -> entry.seed() == seed);
		save();
		return before - entries.size();
	}

	private static void load() {
		if (entries != null) {
			return;
		}
		entries = new LinkedHashMap<>();
		if (!Files.exists(FILE)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				try {
					String[] p = line.split("\t");
					BlockPos chest = p[9].equals("-") ? null : parsePos(p[9]);
					Entry entry = new Entry(Long.parseLong(p[0]), p[1], p[2], parsePos(p[3]), Boolean.parseBoolean(p[4]),
						Integer.parseInt(p[5]), Integer.parseInt(p[6]), Tag.valueOf(p[7]), Integer.parseInt(p[8]), chest, Long.parseLong(p[10]), p.length > 11 && Boolean.parseBoolean(p[11]));
					// A check that didn't finish before the game closed gets done again.
					entries.put(entry.key(), entry);
				} catch (RuntimeException e) {
					LOGGER.warn("Skipping a line in {}: {}", FILE, line);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Couldn't read {}", FILE, e);
		}
	}

	private static void save() {
		List<String> lines = new ArrayList<>();
		lines.add("# Structures found with /structurelocate. seed, dimension, structure, position, has height, start chunk x, z,"
			+ " tag, god apples, god apple chest, time found, checked off");
		for (Entry e : entries.values()) {
			lines.add(String.join("\t", Long.toString(e.seed()), e.dimension(), e.id(), formatPos(e.pos()), Boolean.toString(e.hasY()),
				Integer.toString(e.chunkX()), Integer.toString(e.chunkZ()), e.tag().name(), Integer.toString(e.apples()),
				e.chest() == null ? "-" : formatPos(e.chest()), Long.toString(e.found()), Boolean.toString(e.done())));
		}
		try {
			Files.write(FILE, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}

	private static String formatPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static BlockPos parsePos(String text) {
		String[] p = text.split(",");
		return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}
}
