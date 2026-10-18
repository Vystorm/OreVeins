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
    private final FailedChunks failures;
    private final RegenerationHistory history;
    private UUID enqueueHistory;
    private String requestActor = "system";
    private final Set<UUID> scanningHistories = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failedScans = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cancelledHistories = ConcurrentHashMap.newKeySet();
    private boolean historyWriteFailed;
    private volatile boolean stopping;

    <T> T attributed(String actor, Supplier<T> action) {
        String previous = requestActor;
        requestActor = actor;
        try { return action.get(); } finally { requestActor = previous; }
    }
    List<RegenerationHistory.Entry> history() { return history.snapshot(); }
    boolean historyAvailable() { return history.readable() && !historyWriteFailed; }
    private <T> T manualRun(String scope, Supplier<T> action) {
        UUID previous = enqueueHistory;
        UUID id = history.begin(requestActor, scope);
        enqueueHistory = id;
        try { return action.get(); }
        finally { enqueueHistory = previous; finishHistoryIfDrained(id); }
    }
    private void finishHistoryIfDrained(UUID id) {
        if (id == null || scanningHistories.contains(id) || queue.stream().anyMatch(job -> id.equals(job.historyId))
                || activeJob != null && id.equals(activeJob.historyId)) return;
        var entry = history.snapshot().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
        if (entry == null || entry.status() != RegenerationHistory.Status.ACTIVE) return;
        history.finish(id, cancelledHistories.remove(id) ? RegenerationHistory.Status.CANCELLED
                : failedScans.remove(id) || entry.failed() > 0 ? RegenerationHistory.Status.FAILED : RegenerationHistory.Status.COMPLETED);
    }
    private void flushHistory() {
        if (!history.readable()) return;
        try { history.flush(); historyWriteFailed = false; }
        catch (IOException failure) {
            if (!historyWriteFailed) plugin.getLogger().log(Level.WARNING, "Regeneration history cannot be saved (world checkpoint is separate)", failure);
            historyWriteFailed = true;
        }
    }
    private boolean failureStorageBlocked;
    private RegenerationExclusions exclusions = RegenerationExclusions.EMPTY;
    private long protectedChunksSkipped;
    void exclusions(RegenerationExclusions exclusions) { this.exclusions = java.util.Objects.requireNonNull(exclusions); }
    long protectedChunksSkipped() { return protectedChunksSkipped; }
    private RetrofitSchedule schedule = RetrofitSchedule.defaults();
    void schedule(RetrofitSchedule schedule) { this.schedule = java.util.Objects.requireNonNull(schedule); }
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
        this.failures = new FailedChunks(plugin.getDataFolder().toPath().resolve("failed-chunks.properties"));
        this.failureStorageBlocked = !failures.readable();
        this.history = new RegenerationHistory(plugin.getDataFolder().toPath().resolve("regeneration-history.properties"));
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::processTick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushHistory, 100L, 100L);
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
        stopping = true;
        for (var row : history.snapshot()) if (row.status() == RegenerationHistory.Status.ACTIVE) history.finish(row.id(), RegenerationHistory.Status.INTERRUPTED);
        flushHistory();
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
        return manualRun("loaded", () -> enqueueLoadedChunks(true));
    }

    int regenerateNearbyChunks(World world, int centerChunkX, int centerChunkZ, int radius) {
        return manualRun("radius:" + world.getUID() + ":" + centerChunkX + ":" + centerChunkZ + ":" + radius,
                () -> enqueueNearby(world, centerChunkX, centerChunkZ, radius));
    }
    private int enqueueNearby(World world, int centerChunkX, int centerChunkZ, int radius) {
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
        return manualRun("chunk:" + chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ(), () -> enqueueExisting(chunk, true));
    }

    boolean regenerateAllChunks(Consumer<AllScanResult> completion, boolean resume) {
        if (!resume && progress.active()) return false;
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
        UUID runId = history.begin(requestActor, resume ? "all-resumed-from-checkpoint" : "all-enabled-worlds");
        if (runId != null) scanningHistories.add(runId);
        try {
            GeneratorSettings settings = settingsSupplier.get();
            List<WorldScanTarget> targets = Bukkit.getWorlds().stream()
                    .filter(world -> settings != null
                            && settings.enables(world.getName(), world.getEnvironment()))
                    .map(world -> new WorldScanTarget(world.getUID(), world.getName(), regionDirectories(world)))
                    .toList();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> scanAllAsync(targets, runId, completion));
        } catch (RuntimeException | Error failure) {
            allScanRunning.set(false);
            if (runId != null) {
                failedScans.add(runId);
                scanningHistories.remove(runId);
                history.finish(runId, RegenerationHistory.Status.FAILED);
            }
            throw failure;
        }
        return true;
    }

    /** Scans region files off-thread; all Bukkit-facing completion work returns to the server thread. */
    private void scanAllAsync(List<WorldScanTarget> targets, UUID runId, Consumer<AllScanResult> completion) {
        int found = 0;
        int added = 0;
        int failedFiles = 0;
        try {
            for (WorldScanTarget target : targets) {
                if (stopping) break;
                RegionChunkScanner.ScanResult scan = RegionChunkScanner.scan(target.regionDirectories(),
                        (key, path, error) -> plugin.getLogger().warning(text(key,
                                "world", target.worldName(), "path", path, "error", error)));
                failedFiles += scan.failedFiles();
                found += scan.chunks().size();
                for (RegionChunkScanner.ChunkCoordinate coordinate : scan.chunks()) {
                    if (stopping) break;
                    if (progress.isDone(target.worldId(), coordinate.x(), coordinate.z())) continue;
                    ChunkRef reference = new ChunkRef(target.worldId(), coordinate.x(), coordinate.z());
                    if (queued.add(reference)) {
                        if (stopping) {
                            queued.remove(reference);
                            break;
                        }
                        campaignRemaining.incrementAndGet();
                        history.queued(runId, 1);
                        RetrofitJob job = new RetrofitJob(reference, true, true, true, runId);
                        queue.add(job);
                        added++;
                        // stop() may have cleared the queue just before this insertion.
                        if (stopping && queue.remove(job)) {
                            queued.remove(reference);
                            campaignRemaining.decrementAndGet();
                        }
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            // Preserve already discovered work, but make the incomplete scan visible in run history.
            failedFiles++;
            plugin.getLogger().log(Level.SEVERE, "The regenerate-all region scan stopped unexpectedly", failure);
        } finally {
            AllScanResult result = new AllScanResult(found, added, targets.size(), failedFiles);
            if (runId != null) {
                if (failedFiles > 0) failedScans.add(runId);
                scanningHistories.remove(runId);
            }
            allScanRunning.set(false);
            if (stopping) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (stopping || !plugin.isEnabled()) return;
                    try { completion.accept(result); }
                    finally { finishHistoryIfDrained(runId); finishCampaignIfDrained(); }
                });
            } catch (RuntimeException | Error schedulingFailure) {
                // Usually shutdown raced with the scan. stop() has already latched active rows as interrupted.
                if (!stopping) {
                    plugin.getLogger().log(Level.WARNING, "Could not schedule regenerate-all completion on the server thread", schedulingFailure);
                    if (runId != null) {
                        failedScans.add(runId);
                        history.finish(runId, RegenerationHistory.Status.FAILED);
                    }
                }
            }
        }
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
                history.cancelled(job.historyId, 1);
                if (job.historyId != null) cancelledHistories.add(job.historyId);
                return true;
            }
            return false;
        });
        if (activeJob != null && activeJob.campaign) {
            activeJob.campaign = false;
            campaignRemaining.decrementAndGet();
            if (activeJob.historyId != null) cancelledHistories.add(activeJob.historyId);
        }
        for (UUID id : List.copyOf(cancelledHistories)) finishHistoryIfDrained(id);
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
                || (!forced && (isMarked(chunk, settings.retrofitVersion())
                        || failures.contains(world.getUID(), chunk.getX(), chunk.getZ())))) {
            return false;
        }
        ChunkRef reference = new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(reference)) {
            UUID runId = forced ? enqueueHistory : null;
            history.queued(runId, 1);
            queue.add(new RetrofitJob(reference, forced, false, false, runId));
            return true;
        }
        return false;
    }

    private final ChunkStartLimiter chunkStartLimiter = new ChunkStartLimiter();

    private void processTick() {
        GeneratorSettings settings = settingsSupplier.get();
        RetrofitSchedule currentSchedule = schedule;
        if (settings == null || failureStorageBlocked || !currentSchedule.allowed(java.time.Instant.now())) {
            return;
        }
        long tickStarted = System.nanoTime();
        for (int index = 0; index < settings.retrofitSectionsPerTick() && !failureStorageBlocked; index++) {
            // Never interrupt a section or placement halfway through; budget is checked between work units.
            if (index > 0 && System.nanoTime() - tickStarted >= currentSchedule.budgetNanos()) return;
            RetrofitJob job = activeJob;
            if (job == null) {
                if (queue.isEmpty() || !chunkStartLimiter.tryStart(System.nanoTime(), currentSchedule.maxChunksPerMinute())) return;
                job = queue.poll();
                activeJob = job;
            }
            if (job == null) {
                return;
            }
            ChunkRef reference = job.reference;
            World world = Bukkit.getWorld(reference.worldId());
            if (world == null) {
                recordFailure(job, "world-unavailable");
                finishJob(null, job);
                continue;
            }
            if (!settings.enables(world.getName(), world.getEnvironment())) { finishJob(world, job); continue; }
            if (exclusions.blocks(world.getName(), reference.worldId(), reference.x(), reference.z())) {
                protectedChunksSkipped++;
                job.historyResult = RegenerationHistory.Status.COMPLETED; // counted separately as protected/skipped below
                job.protectedSkipped = true;
                // A protection newly enabled during a partially processed chunk cannot undo earlier removals.
                if (job.nextSectionY != Integer.MIN_VALUE) recordFailure(job, "protected-after-partial-work");
                if (job.campaign) {
                    try { progress.markDone(reference.worldId(), reference.x(), reference.z()); }
                    catch (IOException failure) { failureStorageBlocked = true; recordFailure(job, "checkpoint-failed"); }
                }
                finishJob(world, job);
                continue;
            }
            Chunk chunk;
            if (world.isChunkLoaded(reference.x(), reference.z())) {
                chunk = world.getChunkAt(reference.x(), reference.z());
            } else if (job.loadIfNeeded && world.isChunkGenerated(reference.x(), reference.z())) {
                chunk = world.getChunkAt(reference.x(), reference.z(), false);
                if (chunk == null) {
                    recordFailure(job, "chunk-unavailable");
                    finishJob(world, job);
                    continue;
                }
            } else {
                if (job.forced) recordFailure(job, "chunk-unavailable");
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
                job.historyResult = RegenerationHistory.Status.COMPLETED;
                if (job.campaign) {
                    try {
                        progress.markDone(reference.worldId(), reference.x(), reference.z());
                    } catch (IOException exception) {
                        failureStorageBlocked = true;
                        throw new java.io.UncheckedIOException(exception);
                    }
                }
                if (job.forced) {
                    manuallyRegeneratedChunks++;
                }
                try { failures.remove(reference.worldId(), reference.x(), reference.z()); }
                catch (IOException failure) { failureStorageBlocked = true; plugin.getLogger().log(Level.SEVERE, "Failure index cannot be saved; regeneration paused", failure); }
                finishJob(world, job);
                if (processedChunks == 1 || processedChunks % 100 == 0) {
                    plugin.getLogger().info(text("retrofit.progress",
                            "processed", processedChunks, "queued", queued.size()));
                }
            } catch (RuntimeException exception) {
                recordFailure(job, exception instanceof java.io.UncheckedIOException ? "checkpoint-failed" : "generation-failed");
                finishJob(world, job);
                plugin.getLogger().log(Level.SEVERE, text("retrofit.failed",
                        "x", reference.x(), "z", reference.z(), "world", world.getName()), exception);
            }
        }
    }

    List<FailedChunks.Failure> failedChunks() { return failures.snapshot(); }
    boolean failureStorageBlocked() { return failureStorageBlocked; }

    /** Explicit admin retry, bounded to 100 failed chunks; it can load existing chunks but never creates them. */
    int retryFailedChunks() {
        return manualRun("retry-failed-max100", this::enqueueFailedChunks);
    }
    private int enqueueFailedChunks() {
        if (failureStorageBlocked) return 0;
        GeneratorSettings settings = settingsSupplier.get(); int added = 0;
        for (var failure : failures.snapshot()) {
            if (added >= 100) break;
            var key = failure.key(); World world = Bukkit.getWorld(key.world());
            if (world == null || settings == null || !settings.enables(world.getName(), world.getEnvironment())) continue;
            ChunkRef reference = new ChunkRef(key.world(), key.x(), key.z());
            if (queued.add(reference)) { history.queued(enqueueHistory, 1); queue.add(new RetrofitJob(reference, true, true, false, enqueueHistory)); added++; }
        }
        return added;
    }

    private void recordFailure(RetrofitJob job, String reason) {
        job.historyResult = RegenerationHistory.Status.FAILED;
        try { failures.add(job.reference.worldId(), job.reference.x(), job.reference.z(), reason); }
        catch (IOException failure) {
            failureStorageBlocked = true;
            plugin.getLogger().log(Level.SEVERE, "Failure index cannot be saved; regeneration paused", failure);
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
        if (job.historyResult == RegenerationHistory.Status.FAILED) history.failed(job.historyId);
        else if (job.protectedSkipped || job.historyResult == null) history.skipped(job.historyId);
        else history.completed(job.historyId);
        if (activeJob == job) {
            activeJob = null;
        }
        finishHistoryIfDrained(job.historyId);
        if (job.campaign) {
            campaignRemaining.decrementAndGet();
            finishCampaignIfDrained();
        }
    }

    static Set<Path> regionDirectories(World world) {
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
        private boolean campaign;
        private final UUID historyId;
        private RegenerationHistory.Status historyResult;
        private boolean protectedSkipped;
        private boolean ticketAdded;
        private int nextSectionY = Integer.MIN_VALUE;

        private RetrofitJob(ChunkRef reference, boolean forced, boolean loadIfNeeded, boolean campaign, UUID historyId) {
            this.reference = reference;
            this.forced = forced;
            this.loadIfNeeded = loadIfNeeded;
            this.campaign = campaign;
            this.historyId = historyId;
        }
    }
}
