# OreVeins

OreVeins changes how ores spawn in Paper and Purpur 26.2 worlds. Instead of many small clusters you find fewer, bigger veins, three times the vanilla vein size, while the total amount of ore per chunk stays about the same. It replaces the world generator's ores in the overworld and the Nether as chunks generate, and can convert existing worlds too. No other plugins needed.

This is a maintained fork of OreVeins by Kevin Mendoza (2014, Bukkit). The original was written for the old 0 to 127 world height; this version is a new implementation for the modern Paper API and Java 25, using the original as its reference. Same license: GPL-2.0-or-later.

## Features

- **Bigger, rarer deposits.** The default config has 28 passes with vanilla Y ranges and biome rules, tuned so each ore ends up at roughly vanilla amounts per chunk, spread over about a third as many deposits.
- **No duplicate ores.** The generator's ores are removed before OreVeins places its own, so the two never stack.
- **No X-ray giveaways.** Removed ores are filled with the most common neighbouring block (stone, deepslate, tuff, granite, sandstone), so X-ray packs don't reveal stone blobs where ore used to be.
- **Deep geology.** Rare, large obsidian, magma and buried lava bodies in the deepslate layer. Delete those three passes if you don't want them.
- **Any world height.** Y values are written for a reference height and scaled to each world's real height, which makes it work on tall Iris worlds as well.
- **Biome filters** per pass, with vanilla keys (`minecraft:badlands`) and custom generator keys (for example Iris `carving/drip`). Deepslate variants are used automatically in deepslate and tuff.
- **Existing worlds.** Optional one-time conversion of old chunks when they load, plus commands to regenerate chunks around you, all loaded chunks or the whole world. A whole-world run survives restarts.
- **Safe maintenance.** Limit regeneration to a nightly time window, cap its speed, and exclude build areas so they are never touched.
- **Check before you change.** Validate the config, compare it with a preset, preview how much work a regeneration would be, or simulate the generator on synthetic test chunks without touching real worlds.
- **English and German** messages, picked by the player's client language.
- **Optional web page.** With Vystorm Core, players get an ore guide with a chart of where each ore spawns, and admins a panel with live regeneration progress.

## Installation

- Paper or Purpur 26.2 (or another Paper fork for 26.2), Java 25. Plain Spigot/CraftBukkit and Folia are not supported.
- Optional: [Vystorm Core](https://github.com/Vystorm/Vystorm_Core) 0.24.0 or newer for the web page, 0.27.0 for the in-game config editor.

1. Put `OreVeins-<version>.jar` into `plugins/`.
2. Start the server once. `plugins/OreVeins/config.yml` (vanilla-style defaults) and `plugins/OreVeins/presets/` are created.
3. Adjust the config if needed, then run `/oreveins reload`.

New chunks get OreVeins veins right away. Existing chunks stay as they are unless you turn on the retrofit or run a regenerate command. Make a backup before either. Regenerating also removes ore blocks players placed in those chunks.

## Commands

Everything needs `oreveins.admin` (default: OP). `oreveins.*` grants all of it.

| Command | What it does |
| --- | --- |
| `/oreveins status` | Passes, settings, retrofit counters, progress of a running `regenerate all` |
| `/oreveins reload` | Reload config and language files; on an error the old settings stay active |
| `/oreveins regenerate <radius>` | Regenerate loaded chunks around you (0 to 32 chunks, players only) |
| `/oreveins regenerate loaded` | Regenerate every loaded chunk in all enabled worlds |
| `/oreveins regenerate chunk <world> <x> <z>` | Regenerate one loaded chunk |
| `/oreveins regenerate all` | Regenerate every chunk on disk in all enabled worlds, resumes after restarts |
| `/oreveins regenerate cancel` | Stop a running `regenerate all` |
| `/oreveins regenerate preview [all\|cancel]` | Estimate the work, from loaded chunks or (with `all`) from the region files, without loading anything |
| `/oreveins failed`, `/oreveins retry confirm` | List chunks that failed to regenerate, queue them again |
| `/oreveins history` | Last 15 manual regeneration runs |
| `/oreveins stats [world]` | Ore blocks written since startup, per world and ore |
| `/oreveins validate` | Check the config file on disk without applying it |
| `/oreveins compare` | Compare the active config with the bundled Iris preset |
| `/oreveins simulate [preset] [seeds] [chunks] [environment]` | Run the generator on synthetic chunks, never on real worlds |
| `/oreveins web` | Open the web page (needs Vystorm Core) |

Regenerating removes all ores and the deep lava of the lava pass from a chunk and places them again. Obsidian, magma and the rest of the terrain are left alone.

## Configuration

`config.yml` is commented. With Vystorm Core 0.27.0 or newer, admins can also edit it in game with `/vadmin config OreVeins` (permission `vystorm_core.config.oreveins`); saving works like `/oreveins reload`. Configs from 2.5.0 or older (without `config-version`) are not offered there.

- `language`: `auto`, `en` or `de`.
- `settings.enabled-worlds`: world names; empty means every overworld and Nether world.
- `settings.replace-existing-ores`: remove the generator's ores first (default `true`).
- `settings.retrofit-existing-chunks`: convert old chunks once when they load (default `false`).
- `settings.retrofit-sections-per-tick`: work per tick for retrofit and regeneration (1 to 8, default 1).
- `settings.maintenance`: daily UTC window and speed limit for regeneration.
- `settings.excluded-areas`: up to 128 rectangles that retrofit and regeneration always skip.
- `veins`: the passes (material, host rock, attempts per chunk, size, Y range, distribution, biomes, chance).

**Presets.** On every start OreVeins writes `presets/iris-tall-world.yml`, tuned for Iris worlds with heights from -256 to 511. Copy it over `config.yml`, check `enabled-worlds` and reload. Edit a copy, since the presets folder is overwritten.

**Speed.** With the default of one section per tick, regeneration handles about 0.8 chunks per second at 20 TPS. A world with 100,000 chunks takes well over a day. Raise `retrofit-sections-per-tick` to go faster, watch TPS, and run big jobs when few players are online.

**Texts.** To change single messages, create `plugins/OreVeins/lang/en.yml` (or `de.yml`) with only the keys you want to override and run `/oreveins reload`.

**Upgrading from 2.5.0 or older.** Old configs keep working with their old defaults (now the Iris preset). Nothing is written to your file, and a running `regenerate all` resumes after the update.

## Building

Requires JDK 25 and Gradle 9.1 or newer:

```text
gradle clean test build
```

The jar ends up in `build/libs/OreVeins-<version>.jar`. The optional web page (source set `src/vystorm/`) is only compiled in when a Vystorm Core jar (0.24.0+) is available, either as `-PvystormCoreJar=/path/to/Vystorm_Core.jar` (or `vystormCoreJar=...` in `~/.gradle/gradle.properties`) or as `libs/Vystorm_Core*.jar` (git-ignored). Without it the build prints "building without the optional web page" and the jar works the same, minus the page.

How retrofit and regeneration work internally, the maintenance and exclusion rules, failure retries, history, simulation limits and the web page are described in [docs/TECHNICAL.md](docs/TECHNICAL.md).

## Credits and license

- Original OreVeins: Copyright (C) 2014 Kevin Mendoza, with major contributions by Kevin Song, Alex Lin, Darren Chang, Drew Parliament and Zeno Hao.
- Modern reimplementation for Paper/Purpur 26.2: Copyright (C) 2026 Esoren.

This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Changes: [CHANGELOG.md](CHANGELOG.md).
