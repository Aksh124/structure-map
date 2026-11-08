package structuremap;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.datafixers.util.Pair;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import structuremap.LocalWorldgen.Data;
import structuremap.LocalWorldgen.Generator;

public final class StructureMapClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Structure Map");
		thread.setDaemon(true);
		return thread;
	});

	/** A finished search: the position (null if nothing was found) and the id that matched. */
	public record Found(BlockPos pos, Identifier id) {}

	private static CompletableFuture<Data> data;
	/** Only touched on the worker thread. */
	private static Generator generator;

	private static volatile Long seed;
	private static volatile String seedSource;

	/** Most waypoints one command adds. */
	static final int MAX_WAYPOINTS = 50;

	private static int ticks;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
		if (Waypoints.available()) {
			// Xaero's slime chunk overlay needs the seed, and the Minimap world can change at any time.
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				Long current = seed;
				if (current != null && client.player != null && ++ticks % 100 == 0) {
					try {
						if (Waypoints.shareSlimeChunkSeed(current)) {
							LOGGER.info("Gave Xaero's Minimap the seed for its slime chunk overlay");
						}
					} catch (RuntimeException e) {
						LOGGER.warn("Couldn't give Xaero's Minimap the seed", e);
					}
				}
			});
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(StructureMapClient::onJoin));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			seed = null;
			seedSource = null;
		});
	}

	public static Long seed() {
		return seed;
	}

	public static String seedSource() {
		return seedSource;
	}

	/** Forgets the seed for this session and this server. Client thread. */
	public static void clearSeed() {
		seed = null;
		seedSource = null;
		forgetSeed();
	}

	/** Called on the client thread, from the command, Seed Cracker or the saved seeds. */
	public static void setSeed(long value, String source, boolean announce) {
		seed = value;
		seedSource = source;
		saveSeed(value);
		data();
		if (announce) {
			message(Component.literal("Using seed ").append(copyable(Long.toString(value))).append(" from " + source + "."));
		}
	}

	private static void onJoin() {
		Long saved = savedSeed();
		if (saved != null) {
			setSeed(saved, "the last session on this server", false);
			return;
		}
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			setSeed(server.getWorldGenSettings().options().seed(), "this singleplayer world", false);
		}
	}

	/** Starts loading vanilla's worldgen data the first time anything needs it. */
	private static synchronized CompletableFuture<Data> data() {
		if (data == null) {
			Path cache = FabricLoader.getInstance().getGameDir().resolve("structuremap-cache");
			data = CompletableFuture.supplyAsync(() -> {
				try {
					long started = System.nanoTime();
					Data loaded = LocalWorldgen.load(cache);
					LOGGER.info("Loaded vanilla worldgen data in {} ms", (System.nanoTime() - started) / 1_000_000);
					return loaded;
				} catch (IOException e) {
					throw new IllegalStateException("Couldn't set up the template cache in " + cache, e);
				}
			}, WORKER);
		}
		return data;
	}

	private static Generator generatorFor(Data loaded, long forSeed, ResourceKey<Level> dimension) {
		if (generator == null || generator.seed() != forSeed || !generator.dimension().equals(dimension)) {
			generator = LocalWorldgen.create(loaded, forSeed, dimension);
		}
		return generator;
	}

	/** Nearest structure (an id, or a #tag) to the origin. Completes off the client thread. */
	public static CompletableFuture<Found> locateStructure(String name, ResourceKey<Level> dimension, BlockPos origin) {
		Long forSeed = seed;
		if (forSeed == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(noSeedMessage()));
		}
		return data().thenApplyAsync(loaded -> {
			Registry<Structure> registry = loaded.registries().lookupOrThrow(Registries.STRUCTURE);
			HolderSet<Structure> targets = resolve(registry, name);
			Generator gen = generatorFor(loaded, forSeed, dimension);
			if (gen == null) {
				throw new IllegalStateException("This dimension isn't part of the default world preset.");
			}
			Pair<BlockPos, Holder<Structure>> result = Locator.nearestStructure(loaded, gen, targets, origin);
			return result == null ? new Found(null, null)
				: new Found(result.getFirst(), result.getSecond().unwrapKey().map(ResourceKey::identifier).orElse(null));
		}, WORKER);
	}

	/** The nearest {@code count} structures (an id, or a #tag), closest first. Completes off the client thread. */
	public static CompletableFuture<List<Found>> locateStructures(String name, ResourceKey<Level> dimension, BlockPos origin, int count) {
		Long forSeed = seed;
		if (forSeed == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(noSeedMessage()));
		}
		return data().thenApplyAsync(loaded -> {
			Registry<Structure> registry = loaded.registries().lookupOrThrow(Registries.STRUCTURE);
			HolderSet<Structure> targets = resolve(registry, name);
			Generator gen = generatorFor(loaded, forSeed, dimension);
			if (gen == null) {
				throw new IllegalStateException("This dimension isn't part of the default world preset.");
			}
			List<Found> found = new ArrayList<>();
			for (Pair<BlockPos, Holder<Structure>> result : Locator.nearestStructures(loaded, gen, targets, origin, count)) {
				found.add(new Found(result.getFirst(), result.getSecond().unwrapKey().map(ResourceKey::identifier).orElse(null)));
			}
			return found;
		}, WORKER);
	}

	/** Nearest biome (an id, or a #tag) to the origin, searched in 3D like /locate biome. */
	public static CompletableFuture<Found> locateBiome(String name, ResourceKey<Level> dimension, BlockPos origin) {
		Long forSeed = seed;
		if (forSeed == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(noSeedMessage()));
		}
		return data().thenApplyAsync(loaded -> {
			Registry<Biome> registry = loaded.registries().lookupOrThrow(Registries.BIOME);
			HolderSet<Biome> targets = resolve(registry, name);
			Predicate<Holder<Biome>> wanted = targets::contains;
			Generator gen = generatorFor(loaded, forSeed, dimension);
			if (gen == null) {
				throw new IllegalStateException("This dimension isn't part of the default world preset.");
			}
			Pair<BlockPos, Holder<Biome>> result = Locator.nearestBiome(gen, wanted, origin);
			return result == null ? new Found(null, null)
				: new Found(result.getFirst(), result.getSecond().unwrapKey().map(ResourceKey::identifier).orElse(null));
		}, WORKER);
	}

	private static <T> HolderSet<T> resolve(Registry<T> registry, String name) {
		String text = name.trim().toLowerCase(Locale.ROOT);
		boolean isTag = text.startsWith("#");
		Identifier id = Identifier.tryParse(isTag ? text.substring(1) : text);
		if (id == null) {
			throw new IllegalArgumentException("Not a valid id: " + name);
		}
		if (isTag) {
			return registry.get(TagKey.create(registry.key(), id))
				.map(set -> (HolderSet<T>) set)
				.orElseThrow(() -> new IllegalArgumentException("Unknown tag: #" + id));
		}
		return registry.get(ResourceKey.create(registry.key(), id))
			.map(holder -> (HolderSet<T>) HolderSet.direct(holder))
			.orElseThrow(() -> new IllegalArgumentException("Unknown id: " + id));
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("structurelocate")
			.executes(context -> {
				context.getSource().sendFeedback(Component.literal(
					"/structurelocate structure <id or #tag>, /structurelocate biome <id or #tag>, /structurelocate seed [value]"
						+ (Waypoints.available() ? ", /structurelocate waypoints <count> <id or #tag>, /structurelocate waypoints clear" : "")));
				return Command.SINGLE_SUCCESS;
			})
			.then(literal("waypoints")
				.then(literal("clear").executes(context -> clearWaypoints(context.getSource())))
				.then(literal("add")
					.then(literal("structure").then(argument("x", IntegerArgumentType.integer())
						.then(argument("z", IntegerArgumentType.integer()).then(argument("id", StringArgumentType.greedyString())
							.executes(context -> addWaypoint(context.getSource(), StringArgumentType.getString(context, "id"),
								new BlockPos(IntegerArgumentType.getInteger(context, "x"), 0, IntegerArgumentType.getInteger(context, "z")), false))))))
					.then(literal("biome").then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
						.then(argument("z", IntegerArgumentType.integer()).then(argument("id", StringArgumentType.greedyString())
							.executes(context -> addWaypoint(context.getSource(), StringArgumentType.getString(context, "id"),
								new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
									IntegerArgumentType.getInteger(context, "z")), true))))))))
				.then(argument("count", IntegerArgumentType.integer(1, MAX_WAYPOINTS))
					.then(argument("structure", StringArgumentType.greedyString())
						.suggests((context, builder) -> suggest(builder, Registries.STRUCTURE))
						.executes(context -> addNearestWaypoints(context.getSource(), StringArgumentType.getString(context, "structure"),
							IntegerArgumentType.getInteger(context, "count"))))))
			.then(literal("structure").then(argument("structure", StringArgumentType.greedyString())
				.suggests((context, builder) -> suggest(builder, Registries.STRUCTURE))
				.executes(context -> search(context.getSource(), StringArgumentType.getString(context, "structure"), false))))
			.then(literal("biome").then(argument("biome", StringArgumentType.greedyString())
				.suggests((context, builder) -> suggest(builder, Registries.BIOME))
				.executes(context -> search(context.getSource(), StringArgumentType.getString(context, "biome"), true))))
			.then(literal("seed")
				.executes(context -> {
					Long current = seed;
					context.getSource().sendFeedback(current == null
						? Component.literal(noSeedMessage())
						: Component.literal("Seed: ").append(copyable(Long.toString(current))).append(" (from " + seedSource + ")"));
					return Command.SINGLE_SUCCESS;
				})
				.then(literal("clear").executes(context -> {
					clearSeed();
					context.getSource().sendFeedback(Component.literal("Seed cleared for this server."));
					return Command.SINGLE_SUCCESS;
				}))
				.then(argument("value", StringArgumentType.greedyString()).executes(context -> {
					String text = StringArgumentType.getString(context, "value");
					OptionalLong parsed = WorldOptions.parseSeed(text);
					if (parsed.isEmpty()) {
						context.getSource().sendError(Component.literal("That isn't a seed."));
						return 0;
					}
					setSeed(parsed.getAsLong(), "/structurelocate seed", true);
					return Command.SINGLE_SUCCESS;
				}))));
	}

	private static int search(FabricClientCommandSource source, String name, boolean biome) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return 0;
		}
		if (seed == null) {
			source.sendError(Component.literal(noSeedMessage()));
			return 0;
		}
		BlockPos origin = mc.player.blockPosition();
		ResourceKey<Level> dimension = mc.level.dimension();
		source.sendFeedback(Component.literal("Searching for " + name + "...").withStyle(ChatFormatting.GRAY));

		long started = System.nanoTime();
		CompletableFuture<Found> search = biome ? locateBiome(name, dimension, origin) : locateStructure(name, dimension, origin);
		search.whenComplete((found, error) -> mc.execute(() -> {
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				message(Component.literal(cause.getMessage()).withStyle(ChatFormatting.RED));
				return;
			}
			if (found.pos() == null) {
				message(Component.literal("No " + name + " found within range."));
				return;
			}
			BlockPos pos = found.pos();
			int distance = (int) Math.round(Math.sqrt(biome
				? origin.distSqr(pos)
				: Math.pow(origin.getX() - pos.getX(), 2) + Math.pow(origin.getZ() - pos.getZ(), 2)));
			String coords = biome ? pos.getX() + " " + pos.getY() + " " + pos.getZ() : pos.getX() + " ~ " + pos.getZ();
			MutableComponent line = Component.literal("Nearest " + found.id() + ": ").append(copyable(coords))
				.append(String.format(Locale.ROOT, " (%d blocks away, %.1fs)", distance, (System.nanoTime() - started) / 1e9));
			if (Waypoints.available() && found.id() != null) {
				String command = biome
					? "/structurelocate waypoints add biome " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + found.id()
					: "/structurelocate waypoints add structure " + pos.getX() + " " + pos.getZ() + " " + found.id();
				line.append(" ").append(Component.literal("[+ Waypoint]").withStyle(style -> style
					.withColor(ChatFormatting.YELLOW)
					.withClickEvent(new ClickEvent.RunCommand(command))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Add a waypoint in Xaero's Minimap")))));
			}
			message(line);
		}));
		return Command.SINGLE_SUCCESS;
	}

	private static int addWaypoint(FabricClientCommandSource source, String id, BlockPos pos, boolean biome) {
		if (!Waypoints.available()) {
			source.sendError(Component.literal("Xaero's Minimap isn't installed."));
			return 0;
		}
		Identifier parsed = Identifier.tryParse(id.trim());
		if (parsed == null) {
			source.sendError(Component.literal("Not a valid id: " + id));
			return 0;
		}
		Waypoints.Mark mark = biome ? Waypoints.forBiome(parsed, pos) : Waypoints.forStructure(parsed, pos);
		try {
			List<Waypoints.Mark> added = Waypoints.add(List.of(mark));
			message(Component.literal(added.isEmpty()
				? "There's already a " + mark.name() + " waypoint there."
				: "Added a " + mark.name() + " waypoint at " + pos.getX() + (biome ? " " + pos.getY() + " " : " ~ ") + pos.getZ() + "."));
		} catch (RuntimeException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int addNearestWaypoints(FabricClientCommandSource source, String name, int count) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return 0;
		}
		if (!Waypoints.available()) {
			source.sendError(Component.literal("Xaero's Minimap isn't installed."));
			return 0;
		}
		if (seed == null) {
			source.sendError(Component.literal(noSeedMessage()));
			return 0;
		}
		BlockPos origin = mc.player.blockPosition();
		source.sendFeedback(Component.literal("Searching for the nearest " + count + " " + name + "...").withStyle(ChatFormatting.GRAY));
		long started = System.nanoTime();
		locateStructures(name, mc.level.dimension(), origin, count).whenComplete((found, error) -> mc.execute(() -> {
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				message(Component.literal(cause.getMessage()).withStyle(ChatFormatting.RED));
				return;
			}
			if (found.isEmpty()) {
				message(Component.literal("No " + name + " found within range."));
				return;
			}
			List<Waypoints.Mark> marks = new ArrayList<>();
			for (Found each : found) {
				if (each.id() != null) {
					marks.add(Waypoints.forStructure(each.id(), each.pos()));
				}
			}
			try {
				int added = Waypoints.add(marks).size();
				int furthest = (int) Math.round(Math.sqrt(Locator.horizontalDistanceSqr(origin, found.getLast().pos())));
				message(Component.literal(String.format(Locale.ROOT, "Added %d waypoint%s for %d %s within %d blocks%s (%.1fs).",
					added, added == 1 ? "" : "s", found.size(), name, furthest,
					added < found.size() ? ", " + (found.size() - added) + " already there" : "",
					(System.nanoTime() - started) / 1e9)));
			} catch (RuntimeException e) {
				message(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
			}
		}));
		return Command.SINGLE_SUCCESS;
	}

	private static int clearWaypoints(FabricClientCommandSource source) {
		if (!Waypoints.available()) {
			source.sendError(Component.literal("Xaero's Minimap isn't installed."));
			return 0;
		}
		try {
			int removed = Waypoints.clear();
			source.sendFeedback(Component.literal("Removed " + removed + " Structure Map waypoint" + (removed == 1 ? "" : "s") + " from this world."));
		} catch (RuntimeException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, ResourceKey<? extends Registry<?>> key) {
		CompletableFuture<Data> loading = data();
		if (!loading.isDone() || loading.isCompletedExceptionally()) {
			return builder.buildFuture();
		}
		Registry<?> registry = loading.join().registries().lookupOrThrow(key);
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		List<String> options = new ArrayList<>();
		registry.keySet().forEach(id -> options.add(id.toString()));
		registry.getTags().forEach(tag -> options.add("#" + tag.key().location()));
		options.stream().filter(option -> option.contains(typed)).sorted().forEach(builder::suggest);
		return builder.buildFuture();
	}

	private static String noSeedMessage() {
		return "No seed yet. Set one with /structurelocate seed <value>, or let Seed Cracker find it.";
	}

	private static MutableComponent copyable(String text) {
		return Component.literal(text).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(text)));
	}

	private static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.empty()
				.append(Component.literal("[Structure Map] ").withStyle(ChatFormatting.AQUA))
				.append(text));
		}
		LOGGER.info(text.getString());
	}

	// Seeds are remembered per server in config/structuremap-seeds.properties.

	private static Path seedFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("structuremap-seeds.properties");
	}

	private static String serverKey() {
		Minecraft mc = Minecraft.getInstance();
		ServerData server = mc.getCurrentServer();
		if (server != null) {
			return "server." + server.ip;
		}
		MinecraftServer singleplayer = mc.getSingleplayerServer();
		return singleplayer != null ? "singleplayer." + singleplayer.getWorldData().getLevelName() : null;
	}

	private static Properties readSeeds() {
		Properties properties = new Properties();
		if (Files.exists(seedFile())) {
			try (Reader reader = Files.newBufferedReader(seedFile(), StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("Couldn't read {}", seedFile(), e);
			}
		}
		return properties;
	}

	private static void writeSeeds(Properties properties) {
		try (Writer writer = Files.newBufferedWriter(seedFile(), StandardCharsets.UTF_8)) {
			properties.store(writer, "Structure Map seeds, by server");
		} catch (IOException e) {
			LOGGER.warn("Couldn't write {}", seedFile(), e);
		}
	}

	private static Long savedSeed() {
		String key = serverKey();
		String value = key == null ? null : readSeeds().getProperty(key);
		return Optional.ofNullable(value).map(v -> {
			try {
				return Long.parseLong(v);
			} catch (NumberFormatException e) {
				return null;
			}
		}).orElse(null);
	}

	private static void saveSeed(long value) {
		String key = serverKey();
		if (key != null) {
			Properties properties = readSeeds();
			properties.setProperty(key, Long.toString(value));
			writeSeeds(properties);
		}
	}

	private static void forgetSeed() {
		String key = serverKey();
		if (key != null) {
			Properties properties = readSeeds();
			properties.remove(key);
			writeSeeds(properties);
		}
	}
}
