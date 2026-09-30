# Changelog

## 2.7.0 (2026-09-30)

- Optional web page for Vystorm Core 0.24.0+ (soft dependency `vystorm_core`):
  a public ore guide (Y distribution chart and pass table per world, from the
  loaded config) and an admin panel for `oreveins.admin` with live
  `regenerate all` progress, reload, regenerate all/loaded/radius and cancel.
  Without Vystorm Core nothing changes.
- New command `/oreveins web` (opens the page; without Vystorm Core it says so).
- The admin commands and the web page share one implementation, so checks and
  feedback texts are identical.
- Web texts in `lang/en.yml` and `lang/de.yml` below `web.`.
- Build: the web integration is an optional source set, compiled only when a
  Vystorm Core jar is configured (see README, "Building").

## 2.6.0 (2026-09-30)

First public release of the maintained fork.

- New default `config.yml` for vanilla worlds: vanilla heights (overworld
  -64..319, Nether 0..255), vanilla Y ranges and biome keys, 3x vanilla vein
  size with attempts tuned to about the vanilla amount of each ore per chunk.
- The previous default (tall Iris worlds, Y -256..511) ships as the preset
  `presets/iris-tall-world.yml`; presets are written to `plugins/OreVeins/presets/`.
- New `config-version` key. Configs without it (2.5.0 and older) keep the old
  defaults for every key they do not set, so existing servers behave exactly
  as before. Nothing is written to existing configs.
- Retrofit of existing chunks is now off in the default config for new
  installations (existing configs keep their value).
- Chunks generated while OreVeins is active are always marked as done, so
  enabling the retrofit later never converts them a second time.
- Messages in English and German (`language: auto|en|de`); single texts can be
  overridden with `plugins/OreVeins/lang/<en|de>.yml`.
- Unknown sub-commands show a usage line; tab completion for `/oreveins`.
- `plugin.yml`: authors Kevin Mendoza and Esoren, website points to the GitHub
  repository. The jar is now named `OreVeins-<version>.jar`.
- GPL-2.0-or-later license text, NOTICE and source headers added.

## 2.5.0

- `/oreveins regenerate all` survives restarts (`regenerate-all.active` /
  `regenerate-all.done`) and resumes automatically; `/oreveins regenerate cancel`.

## 2.4.0

- Removed ores and lava are filled with the dominant block of the 3x3x3
  neighbourhood instead of plain stone/deepslate.

## 2.0.0 - 2.3.0

- New implementation for Paper/Purpur 26.2 and Java 25 based on OreVeins 1.2B.
- Retrofit of existing chunks per `retrofit-version`, manual regeneration
  commands, deep obsidian/magma/lava bodies, tuning for tall Iris worlds.
