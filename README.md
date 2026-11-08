# Structure Map

Client-side Fabric mod for Minecraft 26.2 that finds structures and biomes from the world seed.
It runs vanilla world generation on your computer, so it can find things in chunks that haven't
loaded or don't exist yet. Requires Fabric API.

## Commands

| Command | Effect |
| --- | --- |
| `/structurelocate structure <id or #tag>` | Nearest structure, e.g. `minecraft:ancient_city` or `#minecraft:village` |
| `/structurelocate biome <id or #tag>` | Nearest biome, searched in 3D like `/locate biome` |
| `/structurelocate seed` | Show the seed in use and where it came from |
| `/structurelocate seed <value>` | Set the seed. Numbers and text seeds both work, as on the create-world screen |
| `/structurelocate seed clear` | Forget the seed for this server |

Results appear in chat, and you can click the coordinates to copy them. Searches use your current
dimension, so you can search the Nether and End too. The first search takes a few seconds while
vanilla's worldgen data loads.

## Xaero's Minimap

With [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) installed (optional):

| Command | Effect |
| --- | --- |
| `/structurelocate waypoints <count> <id or #tag>` | Add waypoints for the nearest 1 to 50 structures, e.g. `/structurelocate waypoints 10 minecraft:trial_chambers` |
| `/structurelocate waypoints clear` | Remove the waypoints Structure Map added in this world. Your own waypoints are left alone |

- Every `/structurelocate structure` and `/structurelocate biome` result gets a **[+ Waypoint]**
  button in chat.
- Waypoints go into your current waypoint set, named after the structure ("Ancient City", "AC"),
  so they show on the minimap, in the world and on Xaero's World Map. You can edit or delete them
  in Xaero's waypoint menu like any other.
- Structures have no fixed height, so their waypoints have no Y. Biome waypoints do.
- Waypoints that are already there aren't added twice.
- Xaero's slime chunk overlay gets the seed automatically.

## Where the seed comes from

1. **Seed Cracker** (1.1.0 or later): when it cracks the seed, it hands it over automatically.
2. **`/structurelocate seed <value>`**, if you already know it.
3. **Saved seeds:** the seed is remembered per server in `config/structuremap-seeds.properties`,
   so you only need it once.
4. **Singleplayer:** the world's own seed is used.

## How it works

The client never receives the worldgen registries (structures, structure sets, noise), so the mod
loads them from the game's built-in data pack, as the create-world screen does. It then builds the
same objects a server would for that seed and dimension: chunk generator, random state and
structure placement state.

Searches copy vanilla's `/locate`, with the same search order and range. Where the server would
load a chunk to check a structure start, the mod asks the same question its generator would: does
the placement allow this chunk, and does the structure set's weighted pick land on this structure
with a valid spot for it?

## Limits

- **Vanilla world generation only.** It assumes the default world type and no worldgen data packs
  or mods. Large biomes, amplified or modded worlds will give wrong answers.
- **Generation, not current state.** It finds where structures generate, not what players have
  done to them since.

## Building

Requires JDK 25.

```bash
./gradlew build
```

`./gradlew runClientGameTest` makes a world with a known seed, lets Seed Cracker find it, and
checks every search against the server's own `/locate`. It then adds Xaero waypoints for the 8
nearest plains villages and checks each one against the server's structure starts, with none
missing, and also tests the chat button, `clear` and the slime chunk seed. It expects Seed Cracker
1.2.0 built at `../seed-cracker/build/libs`. Xaero's Minimap comes from Modrinth's Maven and is
only used to compile and test.
