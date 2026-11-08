package structuremap;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Vanilla's worldgen data and the objects a server builds from it, made on the client.
 *
 * <p>The client never receives structure, noise or structure-set registries, so they are loaded
 * from the game's built-in data pack exactly as the create-world screen does.
 */
final class LocalWorldgen {
	/** Registries and templates, loaded once and shared by every seed and dimension. */
	record Data(CloseableResourceManager resources, RegistryAccess.Frozen registries, WorldDimensions dimensions,
		StructureTemplateManager templates, ReloadableServerRegistries.Holder loot) {}

	/** One dimension of one seed, as a server would set it up. */
	record Generator(long seed, ResourceKey<Level> dimension, ChunkGenerator chunkGenerator, RandomState randomState,
		ChunkGeneratorStructureState structureState, LevelHeightAccessor height) {}

	private LocalWorldgen() {}

	/** Slow (a few seconds): call off the client thread. */
	static Data load(Path cacheDirectory) throws IOException {
		// Templates for jigsaw structures come from the data pack; the storage is only for player-saved ones.
		LevelStorageSource.LevelStorageAccess storage = LevelStorageSource.createDefault(cacheDirectory).createAccess("templates");

		WorldLoader.InitConfig config = new WorldLoader.InitConfig(
			new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(), WorldDataConfiguration.DEFAULT, false, true),
			Commands.CommandSelection.INTEGRATED,
			LevelBasedPermissionSet.GAMEMASTER);

		return WorldLoader.<WorldDimensions, Data>load(
			config,
			context -> new WorldLoader.DataLoadOutput<>(WorldPresets.createNormalWorldDimensions(context.datapackWorldgen()), context.datapackDimensions()),
			(resources, reloadable, registries, dimensions) -> {
				RegistryAccess.Frozen access = registries.compositeAccess();
				StructureTemplateManager templates = new StructureTemplateManager(resources, storage, DataFixers.getDataFixer(),
					access.lookupOrThrow(Registries.BLOCK));
				return new Data(resources, access, dimensions, templates, reloadable.fullRegistries());
			},
			Util.backgroundExecutor(),
			Runnable::run).join();
	}

	/** Mirrors ServerChunkCache's setup for one dimension. Null for a dimension the preset doesn't have. */
	static Generator create(Data data, long seed, ResourceKey<Level> dimension) {
		LevelStem stem = data.dimensions().get(Registries.levelToLevelStem(dimension)).orElse(null);
		if (stem == null) {
			return null;
		}
		ChunkGenerator chunkGenerator = stem.generator();
		var noise = data.registries().lookupOrThrow(Registries.NOISE);
		RandomState randomState = chunkGenerator instanceof NoiseBasedChunkGenerator noiseBased
			? RandomState.create(noiseBased.generatorSettings().value(), noise, seed)
			: RandomState.create(NoiseGeneratorSettings.dummy(), noise, seed);
		ChunkGeneratorStructureState structureState =
			chunkGenerator.createState(data.registries().lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
		structureState.ensureStructuresGenerated();

		DimensionType type = stem.type().value();
		return new Generator(seed, dimension, chunkGenerator, randomState, structureState,
			LevelHeightAccessor.create(type.minY(), type.height()));
	}
}
