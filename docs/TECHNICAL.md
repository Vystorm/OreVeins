# OreVeins: technical details

Companion to the [README](../README.md). How retrofit and regeneration work, the safety controls around them, the analysis commands and the optional web page.

## Retrofit

With `settings.retrofit-existing-chunks: true`, every chunk that existed before OreVeins and is loaded later is converted once: its ores are removed and regenerated with the current config. A marker in the chunk data stores `settings.retrofit-version`, so the chunk is not converted again. Raise that value on purpose to allow one more pass per chunk. Chunks generated while OreVeins is active are marked right away and never retrofitted.

## regenerate all

`regenerate all` reads the region files in the background, then loads existing chunks one at a time (with a plugin chunk ticket) and regenerates them. It never generates new chunks. Finished chunks are appended to `plugins/OreVeins/regenerate-all.done`. While `plugins/OreVeins/regenerate-all.active` exists, the run resumes automatically after a restart and skips finished chunks. Chunk markers and these progress files stayed compatible across the 2.5 upgrade.

Work is spread over ticks. With `retrofit-sections-per-tick: 1` a chunk is handled one 16-block section per tick, about 25 ticks per chunk on a vanilla-height world; in testing that was roughly 0.8 chunks per second at 20 TPS. Taller worlds take longer.

## Maintenance window

`settings.maintenance` sets a daily UTC start (inclusive) and end (exclusive), overnight windows included. Equal times allow the whole day; `enabled: false` removes the time restriction.

- `max-chunks-per-minute` (0 = unlimited, max 10,000) caps chunk starts in a rolling minute, failed attempts included. A chunk already started may finish. The rolling window resets on restart.
- `max-work-millis-per-tick` (1 to 20, default 4) is checked between work units, so a single section can exceed it. The section count remains an extra limit.
- Outside the window, queued and active jobs pause and the campaign checkpoint stays available.
- These controls affect retrofit and regeneration, not new world generation. `/oreveins status` reports window and rate. Invalid policies reject the reload before anything is applied.

## Excluded areas

`settings.excluded-areas` accepts up to 128 rectangles with `world` (name or UUID), `min-x`, `max-x`, `min-z` and `max-z`. Every intersecting chunk is skipped at all heights, even for explicit chunk and retry commands. The check runs before chunk loading and again before each work unit; `/oreveins status` reports skipped chunks.

- Other claim plugins are not imported automatically.
- Applies only to regeneration and retrofit, not initial generation.
- Areas are validated before a reload applies; never reverse min and max.
- A campaign records excluded chunks as handled, so removing an exclusion needs a new campaign to revisit them.
- If protection is enabled after work already began, earlier changes can't be undone. Processing stops and records `protected-after-partial-work` in the failure index.

## Failed chunks and retries

`/oreveins failed` lists up to 30 failures with attempt counts and whether processing is blocked by a storage failure. The bounded `failed-chunks.properties` index survives restarts; unreadable files are preserved and processing pauses.

`/oreveins retry confirm` queues at most 100 recorded failures without adding successful chunks. It may load existing chunks, never creates new ones, and repeats the normal ore and lava replacement on partially processed chunks. It is not a rollback. Entries disappear only after successful completion; an unavailable world stays in the list. Fix storage errors before restarting. Checkpoint failures pause the remaining queue rather than appending records after a torn write.

## History

`/oreveins history` lists the last 15 of at most 100 local manual runs, with actor (UUID or console), scope, start and end in UTC, queued, completed, failed, skipped and cancelled counts, and outcome.

- `regeneration-history.properties` is informational, flushed every five seconds and on shutdown, separate from the authoritative campaign checkpoint. A crash can lose recent counts; entries that were active become INTERRUPTED and do not trigger retries.
- A resumed all-world campaign creates a new system-attributed row referring to the existing checkpoint.
- Up to 32 simultaneous manual runs are recorded. When audit capacity or storage is unavailable, generation still uses its own safety checkpoint.
- Automatic retrofits on chunk load show up as live counters, not history rows.
- Cancelling removes waiting jobs; the chunk currently being processed finishes.

## Previews and analysis

- **`regenerate preview`** is a read-only estimate over at most 4,096 already loaded chunks. It doesn't scan disk, load chunks or change blocks.
- **`regenerate preview all`** scans region location tables off-thread in batches of at most 16 files and reports occupied entries, world height and section bounds and read errors. It never loads chunks or changes blocks and uses the same region-directory discovery as regeneration. It is an estimate, not a consistent snapshot: duplicate directories holding the same chunk and ongoing saves can differ from the actual deduplicated queue. One preview runs globally at a time with a 30-second cooldown; `regenerate preview cancel` stops only the preview.
- **`stats`** counts only ore blocks written by OreVeins since startup. It doesn't scan old chunks or persist counters, keeps at most 128 world names and shows at most 20 rows.
- **`validate`** reads the YAML on disk without changing active settings and reports invalid worlds, heights, materials, numbers and overlapping passes.
- **`compare`** compares active settings with the bundled `iris-tall-world` preset, including effective Y ranges for loaded worlds and added or removed passes. It never applies anything.
- **`simulate`** runs the real placement algorithm against synthetic host and biome fixtures; defaults are the current config, one seed and one chunk. Limits: 16 seeds, 4 chunks per seed, a 120,000 modelled candidate placement budget and a 10-second cooldown per player. Oversized requests are rejected before running. Results never enter live statistics. Connected-vein numbers count six-face same-material components inside each synthetic chunk; components are cut at chunk edges and neighbouring veins may merge, so results don't predict real veins.

The bundled default preset is tuned for vanilla-height worlds and vanilla Y and biome ranges. It is not an exact reproduction of Minecraft's vanilla ore distribution.

## Upgrading from 2.5.0 or older

Configs written by older versions have no `config-version` key. For those, OreVeins keeps using the old bundled configuration (now the tall Iris preset) as the default for every key the file doesn't set, exactly as before. The startup log mentions it and nothing is written to your config. Such files are not registered with Core's config editor, so Core never writes vanilla defaults into them.

## Web page

When Vystorm Core 0.24.0+ is installed and `web.enabled: true` is set in Core's config, OreVeins adds a page to Core's web interface. Players open it in the browser (`/web oreveins` gives a login link) or, with the Vystorm client mod, as an in-game overlay.

- **Ore guide** (every web user): one tab per enabled overworld and Nether world, a chart of where each ore spawns (share per Y level, with hover readout) and a table of all passes: Y range scaled to the world, spread, vein size, veins per chunk, most blocks per chunk, biome limits and host rock. Data comes from the loaded config and worlds and refreshes after `/oreveins reload` and when worlds load or unload.
- **Admin panel** (`oreveins.admin`): the numbers of `/oreveins status`, live `regenerate all` progress (speed and time left, pushed to the page), and buttons for reload, `regenerate all`, `regenerate loaded`, `regenerate <radius>` around yourself (online only) and `regenerate cancel`. Generation statistics and the simulator are at `/generation/stats` and `/generation/simulate`; the simulation accepts optional `preset`, `environment`, `seeds` and `chunks` JSON fields. Every action asks for confirmation; the server runs the same code as the command, rechecks the permission and logs each action to the console.
- `/oreveins web` opens the admin tab in the overlay or sends a login link.

Web texts are in the same language files, keys below `web.`. With Vystorm Core present, OreVeins creates `plugins/OreVeins/lang/en.yml` and `de.yml` as comment-only files so Core doesn't copy the full bundled texts there; add single keys to override as usual. Without Vystorm Core (or with an older one) none of this is loaded. The web UI itself lives in the Vystorm web UI project and ships with Vystorm Core.
