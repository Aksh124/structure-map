package structuremap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A world border set for Structure Map, per server and dimension, in
 * config/structuremap-borders.properties. Searches skip structures outside it.
 */
public final class WorldBorders {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("structuremap-borders.properties");

	/** A square border, in block coordinates. */
	public record Border(double minX, double minZ, double maxX, double maxZ) {
		public static Border around(double centerX, double centerZ, double radius) {
			return new Border(centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
		}

		public boolean contains(BlockPos pos) {
			return pos.getX() >= minX && pos.getX() < maxX && pos.getZ() >= minZ && pos.getZ() < maxZ;
		}

		public String describe() {
			double radius = (maxX - minX) / 2;
			return String.format(Locale.ROOT, "%,.0f blocks from %.0f, %.0f (x %.0f to %.0f, z %.0f to %.0f)", radius,
				(minX + maxX) / 2, (minZ + maxZ) / 2, minX, maxX, minZ, maxZ);
		}
	}

	private WorldBorders() {}

	/** The border set for this server and dimension, or null. */
	public static synchronized Border get(String server, String dimension) {
		if (server == null) {
			return null;
		}
		String value = read().getProperty(server + "|" + dimension);
		if (value == null) {
			return null;
		}
		try {
			String[] parts = value.split(",");
			return new Border(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
				Double.parseDouble(parts[3]));
		} catch (RuntimeException e) {
			LOGGER.warn("Ignoring a bad border in {}: {}", FILE, value);
			return null;
		}
	}

	/** Sets the border for this server and dimension, or removes it if {@code border} is null. */
	public static synchronized void set(String server, String dimension, Border border) {
		if (server == null) {
			return;
		}
		Properties properties = read();
		String key = server + "|" + dimension;
		if (border == null) {
			properties.remove(key);
		} else {
			properties.setProperty(key, border.minX() + "," + border.minZ() + "," + border.maxX() + "," + border.maxZ());
		}
		try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
			properties.store(writer, "Structure Map world borders: server|dimension = minX,minZ,maxX,maxZ");
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}

	/** Read once, then kept: searches check the border for every structure they look at. */
	private static Properties cached;

	private static Properties read() {
		if (cached != null) {
			return cached;
		}
		Properties properties = new Properties();
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("Couldn't read {}", FILE, e);
			}
		}
		cached = properties;
		return properties;
	}
}
