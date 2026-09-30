/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class ExistingChunkRetrofitter implements Listener {
    private static final long RETROFIT_SALT = 0x4f72655665696e73L;

    private final JavaPlugin plugin;
    private final Supplier<GeneratorSettings> settingsSupplier;
    private final Supplier<Messages> messages;
    private final NamespacedKey markerKey;
    private final Queue<RetrofitJob> queue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkRef> queued = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean allScanRunning = new AtomicBoolean();
    private final RegenerationProgress progress;
    private final AtomicInteger campaignRemaining = new AtomicInteger();
    private RetrofitJob activeJob;
    private long processedChunks;
    private long manuallyRegeneratedChunks;

    ExistingChunkRetrofitter(JavaPlugin plugin, Supplier<GeneratorSettings> settingsSupplier,
            Supplier<Messages> messages) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.messages = messages;
        this.markerKey = new NamespacedKey(plugin, "retrofit-version");
        this.progress = new RegenerationProgress(plugin.getDataFolder().toPath());
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::processTick, 1L, 1L);
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                enqueueExisting(chunk, false);
            }
        }
        if (progress.active()) {
            // Worlds are not loaded yet while a STARTUP plugin enables.
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean started = regenerateAllChunks(result -> plugin.getLogger().info(
                        text("regenerate.all.resumed", "done", progress.doneCount(),
                                "queued", result.queuedChunks(), "found", result.foundChunks())), true);
                if (!started) {
                    plugin.getLogger().warning(text("regenerate.all.resume-failed"));
                }
            });
        }
    }

    void stop() {
        progress.close();
        queue.clear();
        queued.clear();
        activeJob = null;
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        GeneratorSettings settings = settingsSupplier.get();
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (settings == null || !settings.enables(world.getName(), world.getEnvironment())) {
            return;
        }
        if (event.isNewChunk()) {
            // Freshly generated chunks already got the configured veins, so they
            // must never be retrofitted later, even if retrofit is enabled after.
            mark(chunk, settings.retrofitVersion());
        } else if (settings.retrofitExistingChunks()) {
            enqueueExisting(chunk, false);
        }
    }

    void enqueueLoadedChunks() {
        enqueueLoadedChunks(false);
    }

    int regenerateLoadedChunks() {
        return enqueueLoadedChunks(true);
    }

    int regenerateNearbyChunks(World world, int centerChunkX, int centerChunkZ, int radius) {
        int added = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            if (Math.abs(chunk.getX() - centerChunkX) <= radius
                    && Math.abs(chunk.getZ() - centerChunkZ) <= radius
                    && enqueueExisting(chunk, true)) {
                added++;
            }
        }
        return added;
    }

    boolean regenerateChunk(Chunk chunk) {
        return enqueueExisting(chunk, true);
    }

    boolean regenerateAllChunks(Consumer<AllScanResult> completion, boolean resume) {
        if (!allScanRunning.compareAndSet(false, true)) {
            return false;
        }
        try {
            if (resume) {
                progress.resume();
            } else {
                progress.begin();
            }
        } catch (IOException exception) {
            allScanRunning.set(false);
            plugin.getLogger().log(Level.SEVERE, text("regenerate.all.store-failed"), exception);
            return false;
        }
        GeneratorSettings settings = settingsSupplier.get();
        List<WorldScanTarget> targets = Bukkit.getWorlds().stream()
                .filter(world -> settings != null
                        && settings.enables(world.getName(), world.getEnvironment()))
                .map(world -> new WorldScanTarget(world.getUID(), world.getName(), regionDirectories(world)))
                .toList();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int found = 0;
            int added = 0;
            int failedFiles = 0;
            for (WorldScanTarget target : targets) {
                RegionChunkScanner.ScanResult result = RegionChunkScanner.scan(target.regionDirectories(),
                        (key, path, error) -> plugin.getLogger().warning(text(key,
                                "world", target.worldName(), "path", path, "error", error)));
                failedFiles += result.failedFiles();
                found += result.chunks().size();
                for (RegionChunkScanner.ChunkCoordinate coordinate : result.chunks()) {
                    if (progress.isDone(target.worldId(), coordinate.x(), coordinate.z())) {
                        continue;
                    }
                    ChunkRef reference = new ChunkRef(target.worldId(), coordinate.x(), coordinate.z());
                    if (queued.add(reference)) {
                        campaignRemaining.incrementAndGet();
                        queue.add(new RetrofitJob(reference, true, true, true));
                        added++;
                    }
                }
            }
            AllScanResult result = new AllScanResult(found, added, targets.size(), failedFiles);
            allScanRunning.set(false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                completion.accept(result);
                finishCampaignIfDrained();
            });
        });
        return true;
    }

    boolean allScanRunning() {
        return allScanRunning.get();
    }

    boolean campaignActive() {
        return progress.active();
    }

    int campaignDone() {
        return progress.doneCount();
    }

    int campaignRemaining() {
        return campaignRemaining.get();
    }

    /** Drops the queued chunks of a running regenerate all and forgets its progress. */
    boolean cancelCampaign() {
        if (!progress.active() || allScanRunning.get()) {
            return false;
        }
        queue.removeIf(job -> {
            if (job.campaign && job != activeJob) {
                queued.remove(job.reference);
                campaignRemaining.decrementAndGet();
                return true;
            }
            return false;
        });
        finishCampaign();
        return true;
    }

    private void finishCampaignIfDrained() {
        if (progress.active() && !allScanRunning.get() && campaignRemaining.get() <= 0) {
            plugin.getLogger().info(text("regenerate.all.finished-log", "done", progress.doneCount()));
            finishCampaign();
        }
    }

    private void finishCampaign() {
        try {
            progress.finish();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, text("regenerate.all.delete-failed"), exception);
        }
    }

    private int enqueueLoadedChunks(boolean forced) {
        int added = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (enqueueExisting(chunk, forced)) {
                    added++;
                }
            }
        }
        return added;
    }

    int queuedChunks() {
        return queued.size();
    }

    long processedChunks() {
        return processedChunks;
    }

    long manuallyRegeneratedChunks() {
        return manuallyRegeneratedChunks;
    }

    private boolean enqueueExisting(Chunk chunk, boolean forced) {
        GeneratorSettings settings = settingsSupplier.get();
        World world = chunk.getWorld();
        if (settings == null || (!forced && !settings.retrofitExistingChunks())
                || !settings.enables(world.getName(), world.getEnvironment())
                || (!forced && isMarked(chunk, settings.retrofitVersion()))) {
            return false;
        }
        ChunkRef reference = new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(reference)) {
            queue.add(new RetrofitJob(reference, forced, false, false));
            return true;
        }
        return false;
    }

    private void processTick() {
        GeneratorSettings settings = settingsSupplier.get();
        if (settings == null) {
            return;
        }
        for (int index = 0; index < settings.retrofitSectionsPerTick(); index++) {
            RetrofitJob job = activeJob;
            if (job == null) {
                job = queue.poll();
                activeJob = job;
            }
            if (job == null) {
                return;
            }
            ChunkRef reference = job.reference;
            World world = Bukkit.getWorld(reference.worldId());
            if (world == null) {
                finishJob(null, job);
                continue;
            }
            Chunk chunk;
            if (world.isChunkLoaded(reference.x(), reference.z())) {
                chunk = world.getChunkAt(reference.x(), reference.z());
            } else if (job.loadIfNeeded && world.isChunkGenerated(reference.x(), reference.z())) {
                chunk = world.getChunkAt(reference.x(), reference.z(), false);
                if (chunk == null) {
                    finishJob(world, job);
                    continue;
                }
            } else {
                finishJob(world, job);
                continue;
            }
            if (job.loadIfNeeded && !job.ticketAdded) {
                world.addPluginChunkTicket(reference.x(), reference.z(), plugin);
                job.ticketAdded = true;
            }
            if (!job.forced && isMarked(chunk, settings.retrofitVersion())) {
                finishJob(world, job);
                continue;
            }
            try {
                if (job.nextSectionY == Integer.MIN_VALUE) {
                    job.nextSectionY = world.getMinHeight();
                }
                if ((settings.replaceExistingOres() || job.forced)
                        && job.nextSectionY < world.getMaxHeight()) {
                    int sectionEnd = Math.min(job.nextSectionY + 16, world.getMaxHeight());
                    VeinPopulator.removeRegenerableBlocks(
                            world, chunk, job.nextSectionY, sectionEnd, settings);
                    job.nextSectionY = sectionEnd;
                    continue;
                }
                long seed = world.getSeed()
                        ^ (reference.x() * 341873128712L)
                        ^ (reference.z() * 132897987541L)
                        ^ RETROFIT_SALT;
                VeinPopulator.regenerateExisting(world, chunk, new Random(seed), settings);
                mark(chunk, settings.retrofitVersion());
                processedChunks++;
                if (job.campaign) {
                    try {
                        progress.markDone(reference.worldId(), reference.x(), reference.z());
                    } catch (IOException exception) {
                        plugin.getLogger().log(Level.WARNING, text("regenerate.all.record-failed"), exception);
                    }
                }
                if (job.forced) {
                    manuallyRegeneratedChunks++;
                }
                finishJob(world, job);
                if (processedChunks == 1 || processedChunks % 100 == 0) {
                    plugin.getLogger().info(text("retrofit.progress",
                            "processed", processedChunks, "queued", queued.size()));
                }
            } catch (RuntimeException exception) {
                finishJob(world, job);
                plugin.getLogger().log(Level.SEVERE, text("retrofit.failed",
                        "x", reference.x(), "z", reference.z(), "world", world.getName()), exception);
            }
        }
    }

    private String text(String key, Object... placeholders) {
        return messages.get().console(key, placeholders);
    }

    private void finishJob(World world, RetrofitJob job) {
        if (job.ticketAdded && world != null) {
            world.removePluginChunkTicket(job.reference.x(), job.reference.z(), plugin);
        }
        queued.remove(job.reference);
        if (activeJob == job) {
            activeJob = null;
        }
        if (job.campaign) {
            campaignRemaining.decrementAndGet();
            finishCampaignIfDrained();
        }
    }

    private Set<Path> regionDirectories(World world) {
        Path worldPath = world.getWorldPath().toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(worldPath.resolve("region"));
        candidates.add(worldPath.resolve("dimensions")
                .resolve(world.getKey().getNamespace())
                .resolve(world.getKey().getKey())
                .resolve("region"));
        if (world.getEnvironment() == World.Environment.NETHER) {
            candidates.add(worldPath.resolve("DIM-1").resolve("region"));
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            candidates.add(worldPath.resolve("DIM1").resolve("region"));
        }
        candidates.removeIf(path -> !Files.isDirectory(path));
        return Set.copyOf(candidates);
    }

    private boolean isMarked(Chunk chunk, int version) {
        Integer marker = chunk.getPersistentDataContainer().get(markerKey, PersistentDataType.INTEGER);
        return marker != null && marker >= version;
    }

    private void mark(Chunk chunk, int version) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.INTEGER, version);
    }

    private record ChunkRef(UUID worldId, int x, int z) {
    }

    record AllScanResult(int foundChunks, int queuedChunks, int worlds, int failedRegionFiles) {
    }

    private record WorldScanTarget(UUID worldId, String worldName, Set<Path> regionDirectories) {
    }

    private static final class RetrofitJob {
        private final ChunkRef reference;
        private final boolean forced;
        private final boolean loadIfNeeded;
        private final boolean campaign;
        private boolean ticketAdded;
        private int nextSectionY = Integer.MIN_VALUE;

        private RetrofitJob(ChunkRef reference, boolean forced, boolean loadIfNeeded, boolean campaign) {
            this.reference = reference;
            this.forced = forced;
            this.loadIfNeeded = loadIfNeeded;
            this.campaign = campaign;
        }
    }
}
