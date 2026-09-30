# OreVeins

Ore vein generation for **Paper / Purpur 26.2** worlds.

This is a maintained fork of **OreVeins by Kevin Mendoza** (2014, Bukkit,
GPL-2.0-or-later). The original plugin was written for the old 0..127 world
height. This version is a new implementation for the modern Paper API and
Java 25, built with the original as its reference. It is released under the
same license: **GPL-2.0-or-later** (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).

## What it does

- **Replaces the generator's ores.** While a chunk is generated, OreVeins
  removes the ores the world generator placed (overworld and Nether) and then
  runs its own configurable vein passes. Both ore sets never stack.
- **Fewer, bigger deposits.** The default configuration has 28 passes with
  vanilla Y ranges and vanilla biome rules, but every vein is 3x the vanilla
  vein size. Attempts are tuned so each ore ends up at about the vanilla
  amount of blocks per chunk, spread over roughly a third as many deposits.
- **Deep geology (optional).** Rare, large obsidian, magma and buried lava
  bodies in the deepslate layer. Delete those three passes if you do not want
  them.
- **No ore-shaped holes.** Removed ores are filled with the most common
  neighbouring block (stone, deepslate, tuff, granite, sandstone, ...), so
  X-ray texture packs do not reveal stone blobs where ores used to be.
- **Any world height.** Y values are written for a reference height and are
  scaled proportionally to each world's real min/max height at runtime.
- **Biome filters** per pass, with vanilla keys (`minecraft:badlands`) and
  custom generator keys (for example Iris `carving/drip`).
- **Deepslate variants** are placed automatically when the host block is
  deepslate or tuff.
- **Existing worlds**: optional one-time retrofit of old chunks when they
  load, plus admin commands to regenerate chunks, up to the whole world.
  A full-world run survives restarts.
- **English and German** messages; German is picked automatically for
  players whose client language is German.
- No dependencies. Optional: with [Vystorm Core](#web-page-optional) a web
  page shows an ore guide to players and an admin panel.

## Requirements

- **Paper or Purpur 26.2** (or another Paper fork for 26.2). OreVeins uses
  Paper API and does **not** run on plain Spigot/CraftBukkit. Folia is not
  supported.
- **Java 25**.

## Installation

1. Put `OreVeins-<version>.jar` into `plugins/`.
2. Start the server once. `plugins/OreVeins/config.yml` (vanilla defaults)
   and `plugins/OreVeins/presets/` are created.
3. Adjust the config if needed, then `/oreveins reload`.

New chunks get OreVeins veins right away. Chunks that already existed are
**not** changed unless you enable the retrofit or run a regenerate command
(see below). Make a backup before you do either.

## Commands

All commands need `oreveins.admin` (default: op).

| Command | What it does |
| --- | --- |
| `/oreveins status` | Passes, settings, retrofit counters and the progress of a running `regenerate all`. |
| `/oreveins reload` | Reloads `config.yml` and the language files. On an error the previous settings stay active. |
| `/oreveins regenerate <radius>` | Player only: regenerates loaded chunks in a square radius (0..32 chunks) around you. |
| `/oreveins regenerate loaded` | Regenerates every currently loaded chunk in all enabled worlds. Console OK. |
| `/oreveins regenerate chunk <world> <x> <z>` | Regenerates one loaded chunk (chunk coordinates). Never loads or generates a chunk. |
| `/oreveins regenerate all` | Regenerates every chunk that exists on disk in all enabled overworld and Nether worlds (see below). |
| `/oreveins regenerate cancel` | Cancels a running `regenerate all` and discards its progress. |
| `/oreveins web` | Opens the web page (admin tab), only with Vystorm Core, see below. |

"Regenerate" removes all ore blocks and the deep source lava of the
configured lava pass from the chunk and generates only those two categories
again. Obsidian, magma and the rest of the terrain are left alone. Note that
this also removes ore blocks **players placed** in those chunks.

## Configuration

`config.yml` is commented. The important parts:

- `language`: `auto` (German for German clients, English otherwise and on the
  console), `en` or `de` (forces that language everywhere, console included).
- `settings.enabled-worlds`: world names; empty = every NORMAL and NETHER world.
- `settings.replace-existing-ores`: remove the generator's ores first (default `true`).
- `settings.retrofit-existing-chunks`: retrofit old chunks when they load (default `false`).
- `settings.retrofit-sections-per-tick`: work per tick for retrofits and regenerations (1..8, default 1).
- `settings.retrofit-version`: raise it on purpose to allow one more retrofit pass per chunk.
- `reference-heights`: the world height the vein Y values are written for.
- `veins`: the passes (material, host rock, attempts per chunk, size, Y range,
  distribution, optional biomes, spawn chance and air-exposure discard).

### Presets

On every start OreVeins writes its presets to `plugins/OreVeins/presets/`
(overwriting them, so edit a copy):

- `iris-tall-world.yml`: tuned for Iris worlds with extended height (overworld
  and Nether Y -256..511): larger Y ranges, Iris cave biome keys and rarer
  overworld ores. This was the bundled default up to 2.5.0.

To use a preset, copy it over `plugins/OreVeins/config.yml`, check
`enabled-worlds` and run `/oreveins reload`.

### Upgrading from 2.5.0 or older

Configs written by older versions have no `config-version` key. For those,
OreVeins keeps using the old bundled configuration (now the tall Iris preset)
as the default for every key the file does not set, exactly like before. The
startup log mentions it. Nothing is written to your config. Chunk markers and
the `regenerate all` progress files stay compatible, so a running
`regenerate all` resumes after the update.

### Custom texts

The bundled texts are in `lang/en.yml` and `lang/de.yml` inside the jar. To
change single texts, create `plugins/OreVeins/lang/en.yml` (or `de.yml`) with
only the keys you want to override and run `/oreveins reload`.

## Retrofit and regeneration

- **Retrofit** (`retrofit-existing-chunks: true`): every chunk that existed
  before OreVeins and is loaded later is converted once: its ores are removed
  and regenerated with the current config. A marker in the chunk data stores
  the `retrofit-version`, so the chunk is not converted again. Chunks
  generated while OreVeins is active are marked right away and never
  retrofitted.
- **`regenerate all`** reads the region files in the background, then loads
  existing chunks one at a time (with a plugin chunk ticket) and regenerates
  them. It never generates new chunks. Finished chunks are appended to
  `plugins/OreVeins/regenerate-all.done`; while
  `plugins/OreVeins/regenerate-all.active` exists, the run resumes
  automatically after a restart and skips finished chunks.

**Performance.** Work is spread over ticks: with the default
`retrofit-sections-per-tick: 1` a chunk is handled one 16-block section per
tick, about 25 ticks per chunk on a vanilla-height world. In testing that was
roughly 0.8 chunks per second at 20 TPS. A world with 100,000 chunks
therefore takes well over a day with `regenerate all`; taller worlds take
longer. Raising `retrofit-sections-per-tick` (max 8) speeds this up at the
cost of more work per tick. Keep an eye on TPS and run large jobs when few
players are online.

## Web page (optional)

OreVeins runs without any other plugin. When **Vystorm Core 0.24.0 or newer**
is installed and its web platform is enabled (`web.enabled: true` in Core's
config), OreVeins adds a page to Core's web interface. Players open it in the
browser (`/web oreveins` gives a login link) or, with the free Vystorm client
mod, as an in-game overlay.

- **Ore guide** (every web user): one tab per enabled overworld and Nether
  world, a chart of where each ore spawns (share per Y level, with a hover
  readout), and a table of all generation passes: Y range scaled to the world,
  spread, vein size, veins per chunk, most blocks per chunk, biome limits and
  host rock. The data comes from the loaded config and worlds and is refreshed
  after `/oreveins reload` and when worlds load or unload.
- **Admin panel** (`oreveins.admin`): the numbers of `/oreveins status`, live
  progress of a running `regenerate all` (speed and time left, pushed to the
  page, no polling), and buttons for reload, `regenerate all`, `regenerate
  loaded`, `regenerate <radius>` around yourself (only while online) and
  `regenerate cancel`. Every button asks for confirmation first; the server
  runs the same code as the command, checks the permission again and logs
  each action to the console.
- `/oreveins web` opens the admin tab in the overlay or sends a login link.

Web texts are in the same language files (`lang/en.yml`, `lang/de.yml`,
keys below `web.`). With Vystorm Core present, OreVeins creates
`plugins/OreVeins/lang/en.yml` and `de.yml` as comment-only files, so Core does
not copy the full bundled texts there; add single keys to override them as
before. Without Vystorm Core (or with an older one) nothing of this is loaded
and OreVeins behaves exactly as without the web page.

## Building

Requires JDK 25 and Gradle 9.1 or newer:

```text
gradle clean test build
```

The jar is written to `build/libs/OreVeins-<version>.jar`.

The optional web page (source set `src/vystorm/`) is compiled against the
Vystorm Core jar, which is not published in a Maven repository. It is built
into the jar only when a Core jar (0.24.0 or newer) is available, either

- as a Gradle property: `gradle build -PvystormCoreJar=/path/to/Vystorm_Core-0.24.0.jar`
  (or `vystormCoreJar=...` in `~/.gradle/gradle.properties`), or
- as a file `libs/Vystorm_Core*.jar` in this directory (`libs/` is git-ignored).

Without it the build prints "building without the optional web page" and the
jar works exactly the same, just without the page. The page itself (the web
UI) lives in the Vystorm web UI project and ships with Vystorm Core.

## Credits and license

- Original OreVeins: Copyright (C) 2014 Kevin Mendoza, with major
  contributions by Kevin Song, Alex Lin, Darren Chang, Drew Parliament and
  Zeno Hao.
- Modern reimplementation for Paper/Purpur 26.2: Copyright (C) 2026 Esoren.

This program is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later
version. It is distributed WITHOUT ANY WARRANTY. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
