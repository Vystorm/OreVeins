/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The admin actions behind {@code /oreveins}. The command and the optional web
 * page both call these methods, so they share checks, limits and feedback
 * texts. Every method runs on the server thread.
 */
final class AdminActions {
    static final int MAX_RADIUS = 32;

    /** One feedback line: a message key plus name/value placeholder pairs. */
    record Line(String key, Object... placeholders) {
    }

    /** Result of an action: whether it did something, and its feedback lines. */
    record Outcome(boolean success, List<Line> lines) {
        Outcome {
            lines = List.copyOf(lines);
        }

        static Outcome ok(Line... lines) {
            return new Outcome(true, List.of(lines));
        }

        static Outcome failed(Line... lines) {
            return new Outcome(false, List.of(lines));
        }
    }

    /** Result of the last finished region-file scan of {@code regenerate all}. */
    record ScanReport(int worlds, int found, int queued, int failedRegionFiles, long finishedAt) {
    }

    /** Reloads config.yml and the language files; see {@link OreVeinsPlugin#reloadSettings()}. */
    interface Reloader {
        Outcome reload();
    }

    private final Supplier<GeneratorSettings> settings;
    private final ExistingChunkRetrofitter retrofitter;
    private final Reloader reloader;
    private volatile ScanReport lastScan;

    AdminActions(Supplier<GeneratorSettings> settings, ExistingChunkRetrofitter retrofitter, Reloader reloader) {
        this.settings = settings;
        this.retrofitter = retrofitter;
        this.reloader = reloader;
    }

    Outcome reload() {
        return reloader.reload();
    }

    /**
     * The status lines of {@code /oreveins status}. {@code allWorlds} is the
     * localized text for an empty world list.
     */
    Outcome status(String allWorlds) {
        GeneratorSettings current = settings.get();
        String worlds = current.enabledWorlds().isEmpty() ? allWorlds
                : String.join(", ", current.enabledWorlds());
        Line summary = new Line("status.summary",
                "passes", current.veins().size(),
                "replace", current.replaceExistingOres(),
                "retrofit", current.retrofitExistingChunks(),
                "processed", retrofitter.processedChunks(),
                "manual", retrofitter.manuallyRegeneratedChunks(),
                "queued", retrofitter.queuedChunks(),
                "scanning", retrofitter.allScanRunning(),
                "worlds", worlds);
        if (retrofitter.campaignActive()) {
            return Outcome.ok(summary, new Line("status.regenerate-all",
                    "done", retrofitter.campaignDone(), "remaining", retrofitter.campaignRemaining()));
        }
        return Outcome.ok(summary);
    }

    Outcome regenerateLoaded() {
        return queued(retrofitter.regenerateLoadedChunks());
    }

    /**
     * Starts {@code regenerate all}. {@code onScanFinished} gets the lines for
     * the end of the background region-file scan (on the server thread).
     */
    Outcome regenerateAll(Consumer<Outcome> onScanFinished) {
        boolean started = retrofitter.regenerateAllChunks(result -> {
            lastScan = new ScanReport(result.worlds(), result.foundChunks(), result.queuedChunks(),
                    result.failedRegionFiles(), System.currentTimeMillis());
            onScanFinished.accept(Outcome.ok(
                    new Line("regenerate.all.scan-finished",
                            "worlds", result.worlds(), "found", result.foundChunks(),
                            "queued", result.queuedChunks(), "failed", result.failedRegionFiles()),
                    new Line("regenerate.all.no-new-chunks")));
        }, false);
        return started ? Outcome.ok(new Line("regenerate.all.scanning"))
                : Outcome.failed(new Line("regenerate.all.already-running"));
    }

    Outcome cancel() {
        return retrofitter.cancelCampaign() ? Outcome.ok(new Line("regenerate.cancel.done"))
                : Outcome.failed(new Line("regenerate.cancel.none"));
    }

    /** Regenerates the loaded chunks in a square radius around a player. */
    Outcome regenerateRadius(Player player, int radius) {
        if (radius < 0 || radius > MAX_RADIUS) {
            return Outcome.failed(new Line("regenerate.radius.out-of-range"));
        }
        return queued(retrofitter.regenerateNearbyChunks(player.getWorld(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4, radius));
    }

    /** Parses a radius argument; an empty result means it is not a whole number. */
    static OptionalInt parseRadius(String argument) {
        try {
            return OptionalInt.of(Integer.parseInt(argument.trim()));
        } catch (NumberFormatException | NullPointerException exception) {
            return OptionalInt.empty();
        }
    }

    Outcome regenerateChunk(String worldName, String x, String z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return Outcome.failed(new Line("regenerate.chunk.unknown-world", "world", worldName));
        }
        int chunkX;
        int chunkZ;
        try {
            chunkX = Integer.parseInt(x);
            chunkZ = Integer.parseInt(z);
        } catch (NumberFormatException exception) {
            return Outcome.failed(new Line("regenerate.chunk.bad-coordinates"));
        }
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return Outcome.failed(new Line("regenerate.chunk.not-loaded"));
        }
        return queued(retrofitter.regenerateChunk(world.getChunkAt(chunkX, chunkZ)) ? 1 : 0);
    }

    ScanReport lastScan() {
        return lastScan;
    }

    ExistingChunkRetrofitter retrofitter() {
        return retrofitter;
    }

    GeneratorSettings settings() {
        return settings.get();
    }

    static Outcome queued(int added) {
        Line queued = new Line("regenerate.queued", "count", added);
        return added == 0 ? Outcome.ok(queued, new Line("regenerate.nothing-queued")) : Outcome.ok(queued);
    }
}
