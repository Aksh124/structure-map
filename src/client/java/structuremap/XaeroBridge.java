package structuremap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.level.Level;
import structuremap.Waypoints.Mark;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Everything that touches Xaero's Minimap. Only loaded when that mod is installed, and only
 * called on the client thread.
 *
 * <p>Waypoints go into the current set of the Minimap world you're in, as if you had added them
 * yourself, so they show on the minimap, in the world and on Xaero's World Map, and Xaero saves
 * them with the rest of your waypoints.
 */
final class XaeroBridge {
	private static final WaypointColor[] COLORS = {
		WaypointColor.RED, WaypointColor.GOLD, WaypointColor.YELLOW, WaypointColor.GREEN, WaypointColor.AQUA,
		WaypointColor.BLUE, WaypointColor.PURPLE, WaypointColor.PINK, WaypointColor.LIME, WaypointColor.LIGHT_BLUE,
		WaypointColor.MAGENTA, WaypointColor.WHITE};

	private XaeroBridge() {}

	private static WaypointColor color(Mark mark) {
		return mark.colorKey() == Waypoints.ANCIENT_CITY_COLOR ? WaypointColor.DARK_AQUA : COLORS[Math.floorMod(mark.colorKey(), COLORS.length)];
	}

	/** The colour a mark gets, by name, for the game test. */
	static String colorName(Mark mark) {
		return color(mark).name();
	}

	private static MinimapSession session() {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null || session.getWorldManager().getCurrentWorld() == null) {
			throw new IllegalStateException("Xaero's Minimap isn't ready yet. Try again in a moment.");
		}
		return session;
	}

	/** A key for the Minimap world you're in, used to remember which waypoints are ours. */
	static String worldKey() {
		return session().getWorldManager().getCurrentWorld().getFullPath().toString();
	}

	/** Adds the marks that aren't there already and returns the ones it added. */
	static List<Mark> add(Collection<Mark> marks) {
		MinimapSession session = session();
		MinimapWorld world = session.getWorldManager().getCurrentWorld();
		WaypointSet set = world.getCurrentWaypointSet();
		List<Mark> added = new ArrayList<>();
		for (Mark mark : marks) {
			if (find(world, mark) != null) {
				continue;
			}
			Waypoint waypoint = new Waypoint(mark.x(), mark.hasY() ? mark.y() : 64, mark.z(), mark.name(), mark.symbol(),
				color(mark));
			waypoint.setYIncluded(mark.hasY());
			set.add(waypoint);
			added.add(mark);
		}
		if (!added.isEmpty()) {
			save(session, world);
		}
		return added;
	}

	/** Removes these marks from every set in the current world and returns how many were there. */
	static int remove(Collection<Mark> marks) {
		MinimapSession session = session();
		MinimapWorld world = session.getWorldManager().getCurrentWorld();
		int removed = 0;
		for (Mark mark : marks) {
			for (WaypointSet set : world.getIterableWaypointSets()) {
				Waypoint waypoint = find(set, mark);
				if (waypoint != null) {
					set.remove(waypoint);
					removed++;
				}
			}
		}
		if (removed > 0) {
			save(session, world);
		}
		return removed;
	}

	/** Disables or re-enables these marks in every set of the current world. Returns how many it changed. */
	static int setDisabled(Collection<Mark> marks, boolean disabled) {
		MinimapSession session = session();
		MinimapWorld world = session.getWorldManager().getCurrentWorld();
		int changed = 0;
		for (Mark mark : marks) {
			for (WaypointSet set : world.getIterableWaypointSets()) {
				Waypoint waypoint = find(set, mark);
				if (waypoint != null && waypoint.isDisabled() != disabled) {
					waypoint.setDisabled(disabled);
					changed++;
				}
			}
		}
		if (changed > 0) {
			save(session, world);
		}
		return changed;
	}
	/** Whether this mark's waypoint is disabled, or null if there isn't one. For the game test. */
	static Boolean disabled(Mark mark) {
		Waypoint waypoint = find(session().getWorldManager().getCurrentWorld(), mark);
		return waypoint == null ? null : waypoint.isDisabled();
	}

	/** Sets the seed for Xaero's slime chunk overlay if you're in the Overworld and it differs. */
	static boolean setSlimeChunkSeed(long seed) {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null) {
			return false;
		}
		MinimapWorld world = session.getWorldManager().getCurrentWorld();
		if (world == null || !Level.OVERWORLD.equals(world.getDimId()) || Long.valueOf(seed).equals(world.getSlimeChunkSeed())) {
			return false;
		}
		world.setSlimeChunkSeed(seed);
		save(session, world);
		return true;
	}

	private static Waypoint find(MinimapWorld world, Mark mark) {
		for (WaypointSet set : world.getIterableWaypointSets()) {
			Waypoint waypoint = find(set, mark);
			if (waypoint != null) {
				return waypoint;
			}
		}
		return null;
	}

	private static Waypoint find(WaypointSet set, Mark mark) {
		for (Waypoint waypoint : set.getWaypoints()) {
			if (waypoint.getX() == mark.x() && waypoint.getZ() == mark.z() && mark.name().equals(waypoint.getName())) {
				return waypoint;
			}
		}
		return null;
	}

	private static void save(MinimapSession session, MinimapWorld world) {
		try {
			session.getWorldManagerIO().saveWorld(world);
		} catch (IOException e) {
			throw new UncheckedIOException("Couldn't save Xaero's waypoints", e);
		}
	}
}
