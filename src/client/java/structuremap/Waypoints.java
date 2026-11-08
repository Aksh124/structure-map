package structuremap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xaero's Minimap waypoints for search results. Safe to use without Xaero's Minimap installed:
 * check {@link #available()} first, and nothing here loads a Xaero class until then.
 *
 * <p>Waypoints Structure Map adds are listed in {@code config/structuremap-waypoints.txt}, per
 * Minimap world, so {@code /structurelocate waypoints clear} removes only those.
 */
public final class Waypoints {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");

	private Waypoints() {}

	/** A waypoint. {@code y} is only meaningful when {@code hasY} is set (biomes; structures have no fixed height). */
	public record Mark(String name, String symbol, int x, int y, int z, boolean hasY, int colorKey) {}

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap");
	}

	public static Mark forStructure(Identifier id, BlockPos pos) {
		return new Mark(displayName(id), symbol(id), pos.getX(), pos.getY(), pos.getZ(), false, id.hashCode());
	}

	public static Mark forBiome(Identifier id, BlockPos pos) {
		return new Mark(displayName(id), symbol(id), pos.getX(), pos.getY(), pos.getZ(), true, id.hashCode());
	}

	/** {@code minecraft:village_plains} becomes "Village Plains". */
	static String displayName(Identifier id) {
		StringBuilder name = new StringBuilder();
		for (String word : id.getPath().replace('/', '_').split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!name.isEmpty()) {
				name.append(' ');
			}
			name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return name.toString();
	}

	/** Up to two initials, e.g. "AC" for Ancient City, as Xaero shows on the map. */
	static String symbol(Identifier id) {
		StringBuilder symbol = new StringBuilder();
		for (String word : displayName(id).split(" ")) {
			if (symbol.length() < 2 && !word.isEmpty()) {
				symbol.append(word.charAt(0));
			}
		}
		return symbol.toString().toUpperCase(Locale.ROOT);
	}

	/** Adds the marks to the Minimap world you're in and returns the ones that weren't there already. Client thread. */
	public static List<Mark> add(List<Mark> marks) {
		List<Mark> added = XaeroBridge.add(marks);
		if (!added.isEmpty()) {
			String world = XaeroBridge.worldKey();
			List<String> lines = readLines();
			for (Mark mark : added) {
				lines.add(encode(world, mark));
			}
			writeLines(lines);
		}
		return added;
	}

	/** Removes the waypoints Structure Map added in the Minimap world you're in. Client thread. */
	public static int clear() {
		String world = XaeroBridge.worldKey();
		List<String> kept = new ArrayList<>();
		List<Mark> ours = new ArrayList<>();
		for (String line : readLines()) {
			Mark mark = decode(world, line);
			if (mark == null) {
				kept.add(line);
			} else {
				ours.add(mark);
			}
		}
		int removed = XaeroBridge.remove(ours);
		writeLines(kept);
		return removed;
	}

	/**
	 * A structure was checked off (or back on): disables or deletes its waypoints, as the setting
	 * says, or puts them back. Waypoints at any of the positions count (a god apple's can be at
	 * the chest). Returns what was done, for a message, or null if nothing changed. Client thread.
	 */
	public static String checkedOff(Identifier id, List<BlockPos> positions, boolean done) {
		if (!available()) {
			return null;
		}
		List<Mark> marks = positions.stream().map(pos -> forStructure(id, pos)).toList();
		String world = XaeroBridge.worldKey();
		if (!done) {
			// Undo whichever happened: switch disabled ones back on, put deleted ones back.
			int enabled = XaeroBridge.setDisabled(marks, false);
			List<String> lines = readLines(deletedFile());
			List<String> kept = new ArrayList<>();
			List<Mark> restore = new ArrayList<>();
			for (String line : lines) {
				Mark mark = decode(world, line);
				if (mark != null && marks.stream().anyMatch(m -> m.x() == mark.x() && m.z() == mark.z() && m.name().equals(mark.name()))) {
					restore.add(new Mark(mark.name(), mark.symbol(), mark.x(), mark.y(), mark.z(), mark.hasY(), id.hashCode()));
				} else {
					kept.add(line);
				}
			}
			int restored = restore.isEmpty() ? 0 : add(restore).size();
			writeLines(deletedFile(), kept);
			return enabled > 0 ? "switched its waypoint back on" : restored > 0 ? "put its waypoint back" : null;
		}
		return switch (StructureMapSettings.checkedOff()) {
			case KEEP -> null;
			case DISABLE -> XaeroBridge.setDisabled(marks, true) > 0 ? "disabled its waypoint" : null;
			case DELETE -> {
				if (XaeroBridge.remove(marks) == 0) {
					yield null;
				}
				// Remembered, so unchecking can put it back.
				List<String> lines = readLines(deletedFile());
				for (Mark mark : marks) {
					lines.add(encode(world, mark));
				}
				writeLines(deletedFile(), lines);
				yield "deleted its waypoint";
			}
		};
	}

	/** The structure's waypoint at this position: whether it's disabled, or null if there isn't one. For the game test. */
	public static Boolean waypointDisabled(Identifier id, BlockPos pos) {
		return XaeroBridge.disabled(forStructure(id, pos));
	}

	private static Path deletedFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("structuremap-deleted-waypoints.txt");
	}

	/** Gives Xaero's slime chunk overlay the seed. Returns true if it changed. Client thread. */
	public static boolean shareSlimeChunkSeed(long seed) {
		return available() && XaeroBridge.setSlimeChunkSeed(seed);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("structuremap-waypoints.txt");
	}

	private static String encode(String world, Mark mark) {
		return String.join("\t", world, Integer.toString(mark.x()), Integer.toString(mark.y()), Integer.toString(mark.z()),
			Boolean.toString(mark.hasY()), mark.name(), mark.symbol());
	}

	/** The mark on this line if it belongs to this world, otherwise null. */
	private static Mark decode(String world, String line) {
		String[] parts = line.split("\t");
		if (parts.length != 7 || !parts[0].equals(world)) {
			return null;
		}
		try {
			return new Mark(parts[5], parts[6], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
				Boolean.parseBoolean(parts[4]), 0);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static List<String> readLines() {
		return readLines(file());
	}

	private static List<String> readLines(Path path) {
		if (!Files.exists(path)) {
			return new ArrayList<>();
		}
		try {
			return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.warn("Couldn't read {}", path, e);
			return new ArrayList<>();
		}
	}

	private static void writeLines(List<String> lines) {
		writeLines(file(), lines);
	}

	private static void writeLines(Path path, List<String> lines) {
		try {
			Files.write(path, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Couldn't write {}", path, e);
		}
	}
}
