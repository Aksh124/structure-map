package structuremap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import structuremap.LocalWorldgen.Data;
import structuremap.LocalWorldgen.Generator;

/**
 * Works out what's in a structure's chests without visiting it, the way the server decides it.
 *
 * <ol>
 *   <li>The structure's pieces are generated exactly as the server does, from the seed and the
 *       start chunk.</li>
 *   <li>Each chunk the structure covers is then "decorated" like vanilla's applyBiomeDecoration:
 *       a random seeded from the world seed and chunk, then a feature seed from the structure's
 *       place in its generation step. Every piece in that chunk is placed, in order, into a
 *       stand-in world that just records blocks. Chests take their loot seed from that random as
 *       they are placed, in the same order the server does.</li>
 *   <li>Each chest's loot table is rolled with that seed, as the server does when a player first
 *       opens it.</li>
 * </ol>
 *
 * <p>Only what decides chest seeds is placed: pieces with loot chests, in order. Terrain doesn't
 * matter for that, except where an ancient city's sculk patch comes first in a chunk; those chests
 * are left out. Opened or broken chests on a server are of course not reflected.
 */
final class ChestSimulator {
	private static final Logger LOGGER = LoggerFactory.getLogger("structuremap");

	/**
	 * A loot chest. {@code certain} is false when, in its chunk, a feature piece (an ancient city's sculk
	 * patches) was placed before it: how far sculk spreads depends on caves and on the order the server
	 * generated the neighbouring chunks, and it shares the random numbers the chest's seed comes from.
	 */
	record Chest(BlockPos pos, ResourceKey<LootTable> table, long seed, boolean certain) {}

	record Loot(Chest chest, List<ItemStack> items) {}

	private static final Set<String> UNHANDLED = ConcurrentHashMap.newKeySet();

	private ChestSimulator() {}

	/** Every loot chest the structure starting in this chunk gets, or nothing if it can't generate there. */
	static List<Chest> chests(Data data, Generator gen, Holder<Structure> structure, ChunkPos startChunk) {
		StructureStart start = structure.value().generate(structure, gen.dimension(), data.registries(), gen.chunkGenerator(),
			gen.chunkGenerator().getBiomeSource(), gen.randomState(), data.templates(), gen.seed(), startChunk, 0, gen.height(),
			biome -> structure.value().biomes().contains(biome));
		if (!start.isValid()) {
			return List.of();
		}

		var registry = data.registries().lookupOrThrow(Registries.STRUCTURE);
		Map<Integer, List<Structure>> byStep = registry.stream().collect(Collectors.groupingBy(s -> s.step().ordinal()));
		int step = structure.value().step().ordinal();
		int index = byStep.getOrDefault(step, List.of()).indexOf(structure.value());

		World world = new World(data, gen);
		WorldGenLevel level = world.proxy();
		BoundingBox bounds = start.getBoundingBox();
		for (int cx = SectionPos.blockToSectionCoord(bounds.minX()); cx <= SectionPos.blockToSectionCoord(bounds.maxX()); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(bounds.minZ()); cz <= SectionPos.blockToSectionCoord(bounds.maxZ()); cz++) {
				ChunkPos chunk = new ChunkPos(cx, cz);
				WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
				BlockPos origin = SectionPos.of(chunk, gen.height().getMinSectionY()).origin();
				long decorationSeed = random.setDecorationSeed(gen.seed(), origin.getX(), origin.getZ());
				random.setFeatureSeed(decorationSeed, index, step);
				BoundingBox writable = new BoundingBox(chunk.getMinBlockX(), gen.height().getMinY() + 1, chunk.getMinBlockZ(),
					chunk.getMaxBlockX(), gen.height().getMaxY(), chunk.getMaxBlockZ());
				placeInChunk(data, start, level, gen, random, writable, chunk);
			}
		}

		List<Chest> chests = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockEntity> entry : world.blockEntities.entrySet()) {
			if (entry.getValue() instanceof RandomizableContainer container && container.getLootTable() != null) {
				chests.add(new Chest(entry.getKey(), container.getLootTable(), container.getLootTableSeed(), true));
			}
		}
		return chests;
	}

	/**
	 * StructureStart.placeInChunk, cut down to what decides chest seeds. Placing a template only uses
	 * the random numbers for its loot chests, so pieces without any are skipped. A feature piece
	 * (sculk) uses them depending on caves and neighbouring chunks, so the chunk stops there: its
	 * later chests can't be predicted and aren't reported.
	 */
	private static void placeInChunk(Data data, StructureStart start, WorldGenLevel level, Generator gen, RandomSource random,
		BoundingBox writable, ChunkPos chunk) {
		List<StructurePiece> pieces = start.getPieces();
		if (pieces.isEmpty()) {
			return;
		}
		BoundingBox first = pieces.getFirst().getBoundingBox();
		BlockPos centre = first.getCenter();
		BlockPos reference = new BlockPos(centre.getX(), first.minY(), centre.getZ());
		for (StructurePiece piece : pieces) {
			if (!piece.getBoundingBox().intersects(writable)) {
				continue;
			}
			if (piece instanceof PoolElementStructurePiece pool) {
				if (pool.getElement() instanceof FeaturePoolElement) {
					return;
				}
				if (pool.getElement() instanceof SinglePoolElement single && !hasLoot(data, single)) {
					continue;
				}
			}
			piece.postProcess(level, null, gen.chunkGenerator(), random, writable, chunk, reference);
		}
	}

	private static final Map<Identifier, Boolean> HAS_LOOT = new ConcurrentHashMap<>();

	/** Whether a template has any loot containers (which is all that uses the random numbers when placing it). */
	@SuppressWarnings("unchecked")
	private static boolean hasLoot(Data data, SinglePoolElement element) {
		return HAS_LOOT.computeIfAbsent(element.getTemplateLocation(), location -> {
			try {
				StructureTemplate template = data.templates().getOrCreate(location);
				Field palettes = StructureTemplate.class.getDeclaredField("palettes");
				palettes.setAccessible(true);
				for (StructureTemplate.Palette palette : (List<StructureTemplate.Palette>) palettes.get(template)) {
					for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
						if (info.nbt() != null && info.nbt().contains("LootTable")) {
							return true;
						}
					}
				}
				return false;
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Can't tell, so place it: slower, but never wrong.
				return true;
			}
		});
	}

	/** What a chest holds when first opened. */
	static List<ItemStack> roll(Data data, Chest chest) {
		LootTable table = data.loot().getLootTable(chest.table());
		LootParams params = new LootParams.Builder(fakeServerLevel(data))
			.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(chest.pos()))
			.create(LootContextParamSets.CHEST);
		// What LootContext.Builder.withOptionalRandomSeed(seed).create() does, without needing a server.
		RandomSource random = chest.seed() != 0 ? RandomSource.create(chest.seed()) : RandomSource.create();
		LootContext context = newContext(params, random, data.loot().lookup());
		List<ItemStack> items = new ArrayList<>();
		table.getRandomItemsRaw(context, items::add);
		return items;
	}

	// The stand-in world

	/** Records blocks and block entities; everything else is a harmless default. */
	private static final class World implements InvocationHandler {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
		private final Data data;
		private final Generator gen;
		private final Map<Long, ProtoChunk> chunks = new HashMap<>();
		private PalettedContainerFactory containers;

		World(Data data, Generator gen) {
			this.data = data;
			this.gen = gen;
		}

		WorldGenLevel proxy() {
			return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			int count = args == null ? 0 : args.length;
			switch (method.getName()) {
				case "getBlockState":
					return blockAt((BlockPos) args[0]);
				case "getFluidState":
					return blockAt((BlockPos) args[0]).getFluidState();
				case "getChunk":
					if (count == 2 || count == 4) {
						return chunk((int) args[0], (int) args[1]);
					}
					break;
				case "hasChunk":
					return true;
				case "setBlock":
					if (count >= 3) {
						return setBlock(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
					}
					break;
				case "removeBlock":
				case "destroyBlock":
					return setBlock(((BlockPos) args[0]).immutable(), Blocks.AIR.defaultBlockState());
				case "getBlockEntity":
					if (count == 1) {
						return blockEntities.get((BlockPos) args[0]);
					}
					break;
				case "getMinY":
					return gen.height().getMinY();
				case "getHeight":
					if (count == 0) {
						return gen.height().getHeight();
					}
					if (count == 3) {
						return gen.chunkGenerator().getBaseHeight((int) args[1], (int) args[2], (Heightmap.Types) args[0], gen.height(), gen.randomState());
					}
					break;
				case "getSeed":
					return gen.seed();
				case "registryAccess":
					return data.registries();
				case "getLevel":
					return fakeServerLevel(data);
				case "getRandom":
					return RandomSource.create();
				case "isClientSide":
					return false;
				case "enabledFeatures":
					return FeatureFlags.DEFAULT_FLAGS;
				case "getBlockTicks":
				case "getFluidTicks":
					return BlackholeTickAccess.emptyLevelList();
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return "Structure Map stand-in world";
				default:
					break;
			}
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}
			if (UNHANDLED.add(method.getName())) {
				LOGGER.debug("Stand-in world: {} not simulated", method.getName());
			}
			return defaultValue(method.getReturnType());
		}

		private BlockState blockAt(BlockPos pos) {
			BlockState placed = blocks.get(pos);
			if (placed != null) {
				return placed;
			}
			return Blocks.AIR.defaultBlockState();
		}

		/** A blank chunk for the few things that note positions on it. */
		private ProtoChunk chunk(int x, int z) {
			if (containers == null) {
				containers = PalettedContainerFactory.create(data.registries());
			}
			return chunks.computeIfAbsent(ChunkPos.pack(x, z),
				key -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, gen.height(), containers, null));
		}

		private boolean setBlock(BlockPos pos, BlockState state) {
			blocks.put(pos, state);
			if (state.getBlock() instanceof EntityBlock entityBlock && state.hasBlockEntity()) {
				BlockEntity existing = blockEntities.get(pos);
				if (existing == null || !existing.getType().isValid(state)) {
					BlockEntity created = entityBlock.newBlockEntity(pos, state);
					if (created != null) {
						blockEntities.put(pos, created);
					}
				}
			} else {
				blockEntities.remove(pos);
			}
			return true;
		}
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == char.class) return (char) 0;
		return null;
	}

	// Server stand-ins for the loot code, which only asks the level for its registries.

	private static volatile ServerLevel fakeLevel;
	private static volatile Data fakeLevelData;

	/** A ServerLevel that's never set up, except for its registries: all the loot functions use it for. */
	private static ServerLevel fakeServerLevel(Data data) {
		if (fakeLevel == null || fakeLevelData != data) {
			try {
				Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
				unsafeField.setAccessible(true);
				sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
				ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
				Field registryAccess = Level.class.getDeclaredField("registryAccess");
				registryAccess.setAccessible(true);
				registryAccess.set(level, data.registries());
				fakeLevel = level;
				fakeLevelData = data;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Can't roll loot here", e);
			}
		}
		return fakeLevel;
	}

	private static Constructor<LootContext> contextConstructor;

	private static LootContext newContext(LootParams params, RandomSource random, HolderGetter.Provider resolver) {
		try {
			if (contextConstructor == null) {
				Constructor<LootContext> constructor = LootContext.class.getDeclaredConstructor(LootParams.class, RandomSource.class,
					HolderGetter.Provider.class);
				constructor.setAccessible(true);
				contextConstructor = constructor;
			}
			return contextConstructor.newInstance(params, random, resolver);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't roll loot here", e);
		}
	}
}
