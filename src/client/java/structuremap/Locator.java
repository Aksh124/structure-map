package structuremap;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import structuremap.LocalWorldgen.Data;
import structuremap.LocalWorldgen.Generator;

/**
 * The searches behind /locate, run against {@link LocalWorldgen} instead of a server level.
 *
 * <p>The search order copies ChunkGenerator.findNearestMapStructure and
 * BiomeSource.findClosestBiome3d, so results match what the server's /locate reports. Where the
 * server would load a chunk to check a start, this asks the same question its generator would:
 * does the placement allow this chunk, and does the structure set's weighted pick land on the
 * structure and find a valid spot for it?
 */
final class Locator {
	/** Same as vanilla /locate structure: 100 regions in every direction. */
	static final int STRUCTURE_SEARCH_RADIUS = 100;
	/** Same as vanilla /locate biome. */
	static final int BIOME_SEARCH_RADIUS = 6400;
	static final int BIOME_HORIZONTAL_STEP = 32;
	static final int BIOME_VERTICAL_STEP = 64;

	private Locator() {}

	static Pair<BlockPos, Holder<Structure>> nearestStructure(Data data, Generator generator, HolderSet<Structure> targets, BlockPos origin) {
		Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new LinkedHashMap<>();
		for (Holder<Structure> target : targets) {
			for (StructurePlacement placement : generator.structureState().getPlacementsForStructure(target)) {
				byPlacement.computeIfAbsent(placement, p -> new LinkedHashSet<>()).add(target);
			}
		}
		if (byPlacement.isEmpty()) {
			return null;
		}

		Pair<BlockPos, Holder<Structure>> best = null;
		double bestDistance = Double.MAX_VALUE;
		List<Map.Entry<StructurePlacement, Set<Holder<Structure>>>> spreads = new ArrayList<>();

		for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
			if (entry.getKey() instanceof ConcentricRingsStructurePlacement rings) {
				Pair<BlockPos, Holder<Structure>> found = nearestInRings(data, generator, entry.getValue(), origin, rings);
				if (found != null) {
					double distance = origin.distSqr(found.getFirst());
					if (distance < bestDistance) {
						bestDistance = distance;
						best = found;
					}
				}
			} else if (entry.getKey() instanceof RandomSpreadStructurePlacement) {
				spreads.add(entry);
			}
		}

		if (!spreads.isEmpty()) {
			int chunkX = SectionPos.blockToSectionCoord(origin.getX());
			int chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
			for (int radius = 0; radius <= STRUCTURE_SEARCH_RADIUS; radius++) {
				boolean foundAny = false;
				for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : spreads) {
					RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) entry.getKey();
					Pair<BlockPos, Holder<Structure>> found =
						nearestInSpreadRing(data, generator, entry.getValue(), chunkX, chunkZ, radius, placement);
					if (found != null) {
						foundAny = true;
						double distance = origin.distSqr(found.getFirst());
						if (distance < bestDistance) {
							bestDistance = distance;
							best = found;
						}
					}
				}
				if (foundAny) {
					return best;
				}
			}
		}
		return best;
	}

	/**
	 * The nearest {@code count} structures by horizontal distance, closest first.
	 *
	 * <p>Spread regions are checked ring by ring, like {@link #nearestStructure}, but every start in
	 * a ring is kept. Starts in ring r+1 are at least r spacings away, so the search stops once that
	 * is further than the count-th result.
	 */
	static List<Pair<BlockPos, Holder<Structure>>> nearestStructures(Data data, Generator generator, HolderSet<Structure> targets,
		BlockPos origin, int count) {
		Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new LinkedHashMap<>();
		for (Holder<Structure> target : targets) {
			for (StructurePlacement placement : generator.structureState().getPlacementsForStructure(target)) {
				byPlacement.computeIfAbsent(placement, p -> new LinkedHashSet<>()).add(target);
			}
		}

		Map<BlockPos, Pair<BlockPos, Holder<Structure>>> found = new LinkedHashMap<>();
		List<Map.Entry<StructurePlacement, Set<Holder<Structure>>>> spreads = new ArrayList<>();
		for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
			if (entry.getKey() instanceof ConcentricRingsStructurePlacement rings) {
				List<ChunkPos> positions = generator.structureState().getRingPositionsFor(rings);
				if (positions == null) {
					continue;
				}
				List<ChunkPos> sorted = new ArrayList<>(positions);
				sorted.sort(java.util.Comparator.comparingDouble(chunk -> horizontalDistanceSqr(origin, rings.getLocatePos(chunk))));
				int kept = 0;
				for (ChunkPos chunk : sorted) {
					Pair<BlockPos, Holder<Structure>> start = structureAt(data, generator, entry.getValue(), rings, chunk);
					if (start != null) {
						found.putIfAbsent(start.getFirst(), start);
						if (++kept >= count) {
							break;
						}
					}
				}
			} else if (entry.getKey() instanceof RandomSpreadStructurePlacement) {
				spreads.add(entry);
			}
		}

		if (!spreads.isEmpty()) {
			int chunkX = SectionPos.blockToSectionCoord(origin.getX());
			int chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
			int minSpacing = Integer.MAX_VALUE;
			for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : spreads) {
				minSpacing = Math.min(minSpacing, ((RandomSpreadStructurePlacement) entry.getKey()).spacing());
			}
			for (int radius = 0; radius <= STRUCTURE_SEARCH_RADIUS; radius++) {
				for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : spreads) {
					RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) entry.getKey();
					for (Pair<BlockPos, Holder<Structure>> start : allInSpreadRing(data, generator, entry.getValue(), chunkX, chunkZ, radius, placement)) {
						found.putIfAbsent(start.getFirst(), start);
					}
				}
				if (found.size() >= count) {
					double kth = sortedByDistance(found.values(), origin).get(count - 1).getSecond();
					double nextRingBlocks = (radius * (double) minSpacing - 1) * 16;
					if (nextRingBlocks > 0 && nextRingBlocks * nextRingBlocks > kth) {
						break;
					}
				}
			}
		}

		List<Pair<BlockPos, Holder<Structure>>> result = new ArrayList<>();
		for (Pair<Pair<BlockPos, Holder<Structure>>, Double> entry : sortedByDistance(found.values(), origin)) {
			if (result.size() >= count) {
				break;
			}
			result.add(entry.getFirst());
		}
		return result;
	}

	private static List<Pair<Pair<BlockPos, Holder<Structure>>, Double>> sortedByDistance(
		java.util.Collection<Pair<BlockPos, Holder<Structure>>> starts, BlockPos origin) {
		List<Pair<Pair<BlockPos, Holder<Structure>>, Double>> sorted = new ArrayList<>();
		for (Pair<BlockPos, Holder<Structure>> start : starts) {
			sorted.add(Pair.of(start, horizontalDistanceSqr(origin, start.getFirst())));
		}
		sorted.sort(java.util.Comparator.comparingDouble(Pair::getSecond));
		return sorted;
	}

	static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** Every target start in the square ring of regions at this radius. */
	private static List<Pair<BlockPos, Holder<Structure>>> allInSpreadRing(Data data, Generator generator, Set<Holder<Structure>> targets,
		int chunkX, int chunkZ, int radius, RandomSpreadStructurePlacement placement) {
		List<Pair<BlockPos, Holder<Structure>>> starts = new ArrayList<>();
		int spacing = placement.spacing();
		for (int dx = -radius; dx <= radius; dx++) {
			boolean edgeX = dx == -radius || dx == radius;
			for (int dz = -radius; dz <= radius; dz++) {
				if (!edgeX && dz != -radius && dz != radius) {
					continue;
				}
				ChunkPos chunk = placement.getPotentialStructureChunk(generator.seed(), chunkX + spacing * dx, chunkZ + spacing * dz);
				Pair<BlockPos, Holder<Structure>> start = structureAt(data, generator, targets, placement, chunk);
				if (start != null) {
					starts.add(start);
				}
			}
		}
		return starts;
	}

	private static Pair<BlockPos, Holder<Structure>> nearestInRings(Data data, Generator generator, Set<Holder<Structure>> targets,
		BlockPos origin, ConcentricRingsStructurePlacement placement) {
		List<ChunkPos> positions = generator.structureState().getRingPositionsFor(placement);
		if (positions == null) {
			return null;
		}
		Pair<BlockPos, Holder<Structure>> best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos.MutableBlockPos centre = new BlockPos.MutableBlockPos();
		for (ChunkPos chunk : positions) {
			centre.set(SectionPos.sectionToBlockCoord(chunk.x(), 8), 32, SectionPos.sectionToBlockCoord(chunk.z(), 8));
			double distance = centre.distSqr(origin);
			if (best == null || distance < bestDistance) {
				Pair<BlockPos, Holder<Structure>> found = structureAt(data, generator, targets, placement, chunk);
				if (found != null) {
					best = found;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/** Checks the square ring of regions at this radius, as vanilla does. */
	private static Pair<BlockPos, Holder<Structure>> nearestInSpreadRing(Data data, Generator generator, Set<Holder<Structure>> targets,
		int chunkX, int chunkZ, int radius, RandomSpreadStructurePlacement placement) {
		int spacing = placement.spacing();
		for (int dx = -radius; dx <= radius; dx++) {
			boolean edgeX = dx == -radius || dx == radius;
			for (int dz = -radius; dz <= radius; dz++) {
				boolean edgeZ = dz == -radius || dz == radius;
				if (!edgeX && !edgeZ) {
					continue;
				}
				ChunkPos chunk = placement.getPotentialStructureChunk(generator.seed(), chunkX + spacing * dx, chunkZ + spacing * dz);
				Pair<BlockPos, Holder<Structure>> found = structureAt(data, generator, targets, placement, chunk);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** Which of the targets (if any) the generator would start in this chunk. */
	private static Pair<BlockPos, Holder<Structure>> structureAt(Data data, Generator generator, Set<Holder<Structure>> targets,
		StructurePlacement placement, ChunkPos chunk) {
		if (!placement.isStructureChunk(generator.structureState(), chunk.x(), chunk.z())) {
			return null;
		}
		for (Holder<StructureSet> set : generator.structureState().possibleStructureSets()) {
			if (set.value().placement() != placement) {
				continue;
			}
			Holder<Structure> chosen = chooseStructure(data, generator, set.value(), chunk);
			if (chosen != null && targets.contains(chosen)) {
				return Pair.of(placement.getLocatePos(chunk), chosen);
			}
		}
		return null;
	}

	/**
	 * ChunkGenerator.createStructures: one entry is generated directly; with several, entries are
	 * drawn by weight from an RNG seeded per chunk, and one that can't generate is dropped and
	 * the draw repeated.
	 */
	private static Holder<Structure> chooseStructure(Data data, Generator generator, StructureSet set, ChunkPos chunk) {
		List<StructureSet.StructureSelectionEntry> entries = set.structures();
		if (entries.size() == 1) {
			Holder<Structure> only = entries.getFirst().structure();
			return canGenerate(data, generator, only, chunk) ? only : null;
		}

		List<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(generator.structureState().getLevelSeed(), chunk.x(), chunk.z());
		int totalWeight = 0;
		for (StructureSet.StructureSelectionEntry entry : remaining) {
			totalWeight += entry.weight();
		}

		while (!remaining.isEmpty()) {
			int roll = random.nextInt(totalWeight);
			int index = 0;
			for (StructureSet.StructureSelectionEntry entry : remaining) {
				roll -= entry.weight();
				if (roll < 0) {
					break;
				}
				index++;
			}
			StructureSet.StructureSelectionEntry entry = remaining.get(index);
			if (canGenerate(data, generator, entry.structure(), chunk)) {
				return entry.structure();
			}
			remaining.remove(index);
			totalWeight -= entry.weight();
		}
		return null;
	}

	private static boolean canGenerate(Data data, Generator generator, Holder<Structure> structure, ChunkPos chunk) {
		HolderSet<Biome> biomes = structure.value().biomes();
		Structure.GenerationContext context = new Structure.GenerationContext(
			data.registries(), generator.chunkGenerator(), generator.chunkGenerator().getBiomeSource(), generator.randomState(),
			data.templates(), generator.seed(), chunk, generator.height(), biomes::contains);
		return structure.value().findValidGenerationPoint(context).isPresent();
	}

	/** BiomeSource.findClosestBiome3d with the level's height range passed in. */
	static Pair<BlockPos, Holder<Biome>> nearestBiome(Generator generator, Predicate<Holder<Biome>> target, BlockPos origin) {
		BiomeSource source = generator.chunkGenerator().getBiomeSource();
		Set<Holder<Biome>> wanted = new LinkedHashSet<>();
		for (Holder<Biome> biome : source.possibleBiomes()) {
			if (target.test(biome)) {
				wanted.add(biome);
			}
		}
		if (wanted.isEmpty()) {
			return null;
		}

		int steps = Math.floorDiv(BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP);
		int minY = generator.height().getMinY();
		int maxY = generator.height().getMaxY();
		int[] heights = Mth.outFromOrigin(origin.getY(), minY + 1, maxY + 1, BIOME_VERTICAL_STEP).toArray();
		for (BlockPos.MutableBlockPos offset : BlockPos.spiralAround(BlockPos.ZERO, steps, Direction.EAST, Direction.SOUTH)) {
			int x = origin.getX() + offset.getX() * BIOME_HORIZONTAL_STEP;
			int z = origin.getZ() + offset.getZ() * BIOME_HORIZONTAL_STEP;
			int quartX = QuartPos.fromBlock(x);
			int quartZ = QuartPos.fromBlock(z);
			for (int y : heights) {
				Holder<Biome> biome = source.getNoiseBiome(quartX, QuartPos.fromBlock(y), quartZ, generator.randomState().sampler());
				if (wanted.contains(biome)) {
					return Pair.of(new BlockPos(x, y, z), biome);
				}
			}
		}
		return null;
	}
}
