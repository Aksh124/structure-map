package structuremap.test;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import seedcracker.SeedCrackerClient;
import seedcracker.SeedSolver;
import seedcracker.SeedSolver.Observation;
import seedcracker.SeedSolver.StructureType;
import structuremap.StructureMapClient;
import structuremap.StructureMapClient.Found;

/**
 * End to end in a world with a known seed:
 * 1. Seed Cracker finds the seed and hands it to Structure Map.
 * 2. Every search gives the same answer as the server's own /locate code.
 */
public class StructureMapGameTest implements FabricClientGameTest {
	private static final long SEED = -6_421_009_337_154_802_113L;

	private record Query(ResourceKey<Level> dimension, String name, boolean biome, BlockPos origin) {}

	private static final BlockPos SPAWN = new BlockPos(0, 64, 0);
	private static final BlockPos AWAY = new BlockPos(2100, 64, -1700);

	private static final List<Query> QUERIES = List.of(
		new Query(Level.OVERWORLD, "minecraft:village_plains", false, SPAWN),
		new Query(Level.OVERWORLD, "#minecraft:village", false, AWAY),
		new Query(Level.OVERWORLD, "minecraft:desert_pyramid", false, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:swamp_hut", false, AWAY),
		new Query(Level.OVERWORLD, "minecraft:stronghold", false, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:stronghold", false, AWAY),
		new Query(Level.OVERWORLD, "minecraft:monument", false, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:mansion", false, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:trial_chambers", false, AWAY),
		new Query(Level.OVERWORLD, "minecraft:ancient_city", false, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:ruined_portal", false, AWAY),
		new Query(Level.OVERWORLD, "minecraft:cherry_grove", true, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:mushroom_fields", true, SPAWN),
		new Query(Level.OVERWORLD, "minecraft:deep_dark", true, AWAY),
		new Query(Level.OVERWORLD, "minecraft:badlands", true, AWAY),
		new Query(Level.OVERWORLD, "#minecraft:is_ocean", true, SPAWN),
		new Query(Level.NETHER, "minecraft:fortress", false, new BlockPos(0, 64, 0)),
		new Query(Level.NETHER, "minecraft:bastion_remnant", false, new BlockPos(300, 64, -200)),
		new Query(Level.NETHER, "minecraft:warped_forest", true, new BlockPos(0, 64, 0)));

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(Long.toString(SEED)))
			.create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode spectator @a");

			// In singleplayer the mod reads the world's own seed. Clear it so only Seed Cracker can set it.
			context.runOnClient(mc -> StructureMapClient.clearSeed());
			check(context.computeOnClient(mc -> StructureMapClient.seed()) == null, "seed wasn't cleared");

			crackWithSeedCracker(context, world);
			waitUntil(context, 30, () -> Objects.equals(StructureMapClient.seed(), SEED),
				() -> "Structure Map has seed " + StructureMapClient.seed() + " from " + StructureMapClient.seedSource());
			check("Seed Cracker".equals(StructureMapClient.seedSource()), "seed came from " + StructureMapClient.seedSource());
			log("Seed Cracker handed over the seed");

			List<String> mismatches = new ArrayList<>();
			for (Query query : QUERIES) {
				long started = System.nanoTime();
				Found ours = (query.biome()
					? StructureMapClient.locateBiome(query.name(), query.dimension(), query.origin())
					: StructureMapClient.locateStructure(query.name(), query.dimension(), query.origin())).join();
				double oursSeconds = (System.nanoTime() - started) / 1e9;

				started = System.nanoTime();
				BlockPos server = world.getServer().computeOnServer(s -> serverLocate(s, query));
				double serverSeconds = (System.nanoTime() - started) / 1e9;

				boolean same = Objects.equals(ours.pos(), server);
				log(String.format(Locale.ROOT, "%-5s %-9s %-26s from %-16s ours %-22s server %-22s (%.1fs / %.1fs)",
					same ? "OK" : "DIFF", query.dimension().identifier().getPath(), query.name(), query.origin().toShortString(),
					ours.pos() == null ? "none" : ours.pos().toShortString() + " " + ours.id(),
					server == null ? "none" : server.toShortString(), oursSeconds, serverSeconds));
				if (!same) {
					mismatches.add(query.name() + " from " + query.origin().toShortString() + ": ours " + ours.pos() + ", server " + server);
				}
			}
			check(mismatches.isEmpty(), mismatches.size() + " searches differ from /locate: " + mismatches);

			// The command itself, for the screenshot.
			world.getServer().runCommand("tp @a 0 120 0");
			world.getConnection().waitForChunksDownload();
			context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate structure minecraft:village_plains"));
			context.waitTicks(40);
			context.runOnClient(mc -> mc.player.connection.sendCommand("structurelocate biome minecraft:cherry_grove"));
			context.waitTicks(60);
			context.takeScreenshot("structuremap-commands");
			log("all " + QUERIES.size() + " searches match /locate");
		}
	}

	/** The same code path as /locate structure and /locate biome, with the same limits. */
	private static BlockPos serverLocate(MinecraftServer server, Query query) {
		ServerLevel level = server.getLevel(query.dimension());
		if (query.biome()) {
			Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
			HolderSet<Biome> targets = resolve(biomes, query.name());
			Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(targets::contains, query.origin(), 6400, 32, 64);
			return result == null ? null : result.getFirst();
		}
		Registry<Structure> structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		HolderSet<Structure> targets = resolve(structures, query.name());
		Pair<BlockPos, Holder<Structure>> result =
			level.getChunkSource().getGenerator().findNearestMapStructure(level, targets, query.origin(), 100, false);
		return result == null ? null : result.getFirst();
	}

	@SuppressWarnings("unchecked")
	private static <T> HolderSet<T> resolve(Registry<T> registry, String name) {
		if (name.startsWith("#")) {
			return registry.get(TagKey.create(registry.key(), Identifier.parse(name.substring(1)))).map(set -> (HolderSet<T>) set).orElseThrow();
		}
		return registry.get(ResourceKey.create(registry.key(), Identifier.parse(name))).map(h -> (HolderSet<T>) HolderSet.direct(h)).orElseThrow();
	}

	/** Visits the structures the server says are near spawn until Seed Cracker has the seed. */
	private static void crackWithSeedCracker(ClientGameTestContext context, TestSingleplayerContext world) {
		List<Observation> targets = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			var registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			var keys = java.util.Map.of(
				StructureType.DESERT_PYRAMID, net.minecraft.world.level.levelgen.structure.BuiltinStructures.DESERT_PYRAMID,
				StructureType.JUNGLE_TEMPLE, net.minecraft.world.level.levelgen.structure.BuiltinStructures.JUNGLE_TEMPLE,
				StructureType.SWAMP_HUT, net.minecraft.world.level.levelgen.structure.BuiltinStructures.SWAMP_HUT);
			java.util.Set<Observation> found = new java.util.LinkedHashSet<>();
			int[][] centres = {{0, 0}, {3000, 0}, {0, 3000}, {-3000, 0}, {0, -3000}, {3000, 3000}, {-3000, -3000}};
			for (int[] centre : centres) {
				for (StructureType type : StructureType.values()) {
					var result = level.getChunkSource().getGenerator().findNearestMapStructure(level,
						HolderSet.direct(registry.getOrThrow(keys.get(type))), new BlockPos(centre[0], 64, centre[1]), 60, false);
					if (result != null) {
						BlockPos pos = result.getFirst();
						found.add(new Observation(type, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
					}
					if (found.size() >= 6) {
						return new ArrayList<>(found);
					}
				}
			}
			return new ArrayList<>(found);
		});
		check(targets.size() >= SeedSolver.MIN_OBSERVATIONS, "only " + targets.size() + " structures near spawn");

		for (Observation target : targets) {
			world.getServer().runCommand("tp @a " + (target.chunkX() * 16 + 8) + " 160 " + (target.chunkZ() * 16 + 8));
			world.getConnection().waitForChunksDownload();
			waitUntil(context, 60, () -> SeedCrackerClient.observations().contains(target) || SeedCrackerClient.foundSeed() != null,
				() -> "Seed Cracker didn't detect " + target);
			if (SeedCrackerClient.foundSeed() != null) {
				break;
			}
		}
		waitUntil(context, 300, () -> SeedCrackerClient.foundSeed() != null, () -> "Seed Cracker found no seed");
		check(SeedCrackerClient.foundSeed() == SEED, "Seed Cracker found " + SeedCrackerClient.foundSeed());
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
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
