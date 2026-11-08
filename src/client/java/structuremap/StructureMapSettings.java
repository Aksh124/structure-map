package structuremap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Structure Map's settings, in config/structuremap.properties. */
public final class StructureMapSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("structuremap.properties");

	/** What happens to a structure's Xaero waypoint when you check the structure off. */
	public enum CheckedOff {
		DISABLE("Disable it"), DELETE("Delete it"), KEEP("Leave it alone");

		public final String label;

		CheckedOff(String label) {
			this.label = label;
		}
	}

	private static CheckedOff checkedOff;

	private StructureMapSettings() {}

	public static synchronized CheckedOff checkedOff() {
		if (checkedOff == null) {
			checkedOff = CheckedOff.DISABLE;
			if (Files.exists(FILE)) {
				Properties properties = new Properties();
				try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
					properties.load(reader);
					checkedOff = CheckedOff.valueOf(properties.getProperty("checkedOffWaypoints", "DISABLE"));
				} catch (IOException | IllegalArgumentException e) {
					LOGGER.warn("Couldn't read {}", FILE, e);
				}
			}
		}
		return checkedOff;
	}

	public static synchronized void setCheckedOff(CheckedOff value) {
		checkedOff = value;
		Properties properties = new Properties();
		properties.setProperty("checkedOffWaypoints", value.name());
		try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
			properties.store(writer, "Structure Map. checkedOffWaypoints: DISABLE, DELETE or KEEP");
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}
}
