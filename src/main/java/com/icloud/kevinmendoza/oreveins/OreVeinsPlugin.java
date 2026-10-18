/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class OreVeinsPlugin extends JavaPlugin implements Listener {
    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "stats", "simulate", "history", "failed", "retry", "validate", "compare", "regenerate");
    private static final List<String> SUBCOMMANDS_WITH_WEB = List.of("reload", "status", "stats", "simulate", "history", "failed", "retry", "validate", "compare", "regenerate", "web");
    private static final List<String> REGENERATE_TARGETS = List.of("all", "cancel", "loaded", "preview", "chunk", "8");
    private static final List<String> SIMULATION_PRESETS = List.of("current", "default", "iris-tall-world");
    private static final long SIMULATION_COOLDOWN_MILLIS = 10_000L;

    private volatile GeneratorSettings settings;
    private volatile Messages messages = Messages.bundled("auto");
    private FileConfiguration configuration;
    private VeinPopulator populator;
    private ExistingChunkRetrofitter retrofitter;
    private RegenerationExclusions exclusions = RegenerationExclusions.EMPTY;
    private RetrofitSchedule retrofitSchedule = RetrofitSchedule.defaults();
    private AdminActions actions;
    private RegionFilesPreview regionPreview;
    private org.bukkit.scheduler.BukkitTask regionPreviewTask;
    private long nextRegionPreviewNanos;
    private volatile boolean legacyConfig;
    private volatile WebIntegration web;
    private final Map<UUID, Long> simulationCooldowns = new java.util.HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource(ConfigFiles.LEGACY_DEFAULTS_RESOURCE, true);
        try {
            ConfigFiles.Loaded loaded = readConfiguration();
            messages = Messages.load(loaded.config().getString("language", "auto"),
                    getDataFolder(), this::getResource, getLogger());
            settings = GeneratorSettings.load(loaded.config(), getLogger(), messages);
            retrofitSchedule = RetrofitSchedule.load(loaded.config());
            exclusions = RegenerationExclusions.load(loaded.config());
            configuration = loaded.config();
            legacyConfig = loaded.legacy();
            if (loaded.legacy()) {
                getLogger().info(messages.console("startup.legacy-config"));
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().severe(messages.console("startup.failed", "error", exception.getMessage()));
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        populator = new VeinPopulator(() -> settings);
        retrofitter = new ExistingChunkRetrofitter(this, () -> settings, () -> messages);
        retrofitter.schedule(retrofitSchedule);
        retrofitter.exclusions(exclusions);
        actions = new AdminActions(() -> settings, retrofitter, this::reloadSettings);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(retrofitter, this);
        for (World world : Bukkit.getWorlds()) {
            attach(world);
        }
        retrofitter.start();
        getLogger().info(messages.console("startup.loaded", "count", settings.veins().size()));
        getLogger().info(messages.console(settings.retrofitExistingChunks()
                ? "startup.retrofit-on" : "startup.retrofit-off", "version", settings.retrofitVersion()));
        web = WebIntegration.load(this, actions);
    }

    @Override
    public void onDisable() {
        stopRegionPreview();
        WebIntegration current = web;
        web = null;
        if (current != null) {
            current.stop();
        }
        if (retrofitter != null) {
            retrofitter.stop();
        }
        simulationCooldowns.clear();
        if (populator == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.getPopulators().remove(populator);
        }
    }

    /**
     * Returns the configuration read by {@link ConfigFiles}; Bukkit's own
     * loader would attach the wrong defaults to configs written before 2.6.0.
     */
    @Override
    public @NotNull FileConfiguration getConfig() {
        if (configuration == null) {
            try {
                configuration = readConfiguration().config();
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }
        return configuration;
    }

    @Override
    public void reloadConfig() {
        configuration = null;
    }

    private ConfigFiles.Loaded readConfiguration() throws IOException, InvalidConfigurationException {
        return ConfigFiles.load(new File(getDataFolder(), "config.yml"), this::getResource);
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        attach(event.getWorld());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        WebIntegration current = web;
        if (current != null) {
            current.worldsChanged();
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        WebIntegration current = web;
        if (current != null) {
            current.worldsChanged();
        }
    }

    /**
     * {@code /oreveins reload}: reads config.yml and the language files again.
     * On any error the previous settings stay active.
     */
    AdminActions.Outcome reloadSettings() {
        try {
            ConfigFiles.Loaded loaded = readConfiguration();
            Messages newMessages = Messages.load(loaded.config().getString("language", "auto"),
                    getDataFolder(), this::getResource, getLogger());
            GeneratorSettings newSettings = GeneratorSettings.load(loaded.config(), getLogger(), newMessages);
            RetrofitSchedule newSchedule = RetrofitSchedule.load(loaded.config());
            RegenerationExclusions newExclusions = RegenerationExclusions.load(loaded.config());
            configuration = loaded.config();
            legacyConfig = loaded.legacy();
            messages = newMessages;
            settings = newSettings;
            retrofitSchedule = newSchedule;
            retrofitter.schedule(newSchedule);
            exclusions = newExclusions;
            retrofitter.exclusions(newExclusions);
            for (World world : Bukkit.getWorlds()) {
                attach(world);
            }
            retrofitter.enqueueLoadedChunks();
            WebIntegration current = web;
            if (current != null) {
                current.reloaded();
            }
            return AdminActions.Outcome.ok(new AdminActions.Line("reload.done", "count", newSettings.veins().size()));
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            return AdminActions.Outcome.failed(new AdminActions.Line("reload.failed", "error", exception.getMessage()));
        }
    }

    Messages messages() {
        return messages;
    }

    RetrofitSchedule maintenanceSchedule() { return retrofitSchedule; }

    GeneratorSettings settings() {
        return settings;
    }

    boolean legacyConfig() {
        return legacyConfig;
    }

    String languageSetting() {
        return getConfig().getString("language", "auto");
    }

    ReadOnlyAnalysis.Validation validateCandidate() throws IOException, InvalidConfigurationException {
        ConfigFiles.Loaded candidate = readConfiguration();
        return ReadOnlyAnalysis.validate(candidate.config(), Bukkit.getWorlds().stream().map(World::getName)
                .collect(java.util.stream.Collectors.toSet()), key -> {
                    try {
                        org.bukkit.NamespacedKey parsed = org.bukkit.NamespacedKey.fromString(key);
                        return parsed != null && Bukkit.getRegistry(org.bukkit.block.Biome.class).get(parsed) != null;
                    } catch (RuntimeException ignored) { return false; }
                });
    }

    ReadOnlyAnalysis.Comparison comparePreset(String name) throws IOException, InvalidConfigurationException {
        String resource = switch (name.toLowerCase(Locale.ROOT)) {
            case "iris-tall-world", "iris" -> ConfigFiles.LEGACY_DEFAULTS_RESOURCE;
            case "default" -> ConfigFiles.DEFAULT_RESOURCE;
            default -> throw new IllegalArgumentException("unknown preset: " + name);
        };
        var preset = ConfigFiles.resource(this::getResource, resource);
        GeneratorSettings reference = GeneratorSettings.load(preset, null, messages);
        return ReadOnlyAnalysis.compare(name, settings, reference,
                Bukkit.getWorlds().stream().map(WebModel.WorldView::of).toList());
    }

    String loadedPreview() {
        final int maxChunks = 4096;
        int inspected = 0;
        int matching = 0;
        long sections = 0;
        long passChunkRuns = 0;
        boolean truncated = false;
        for (World world : Bukkit.getWorlds()) {
            if (!settings.enables(world.getName(), world.getEnvironment())) continue;
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                if (inspected == maxChunks) { truncated = true; break; }
                inspected++;
                matching++;
                sections += Math.max(1, (world.getMaxHeight() - world.getMinHeight() + 15L) / 16L);
                passChunkRuns += settings.veins().stream().filter(vein -> vein.environment() == world.getEnvironment()).count();
            }
            if (truncated) break;
        }
        return "loaded-only; inspected=" + inspected + "; candidate-chunks=" + matching
                + "; pass-chunk-runs=" + passChunkRuns
                + "; approximate-sections=" + sections + "; max-chunks=" + maxChunks
                + (truncated ? "; truncated=true" : "; truncated=false") + "; world-state=read-only";
    }

    GenerationSimulation.Report simulatePreset(String preset, World.Environment environment,
            int seeds, int chunks) throws IOException, InvalidConfigurationException {
        GeneratorSettings snapshot;
        if (preset.equalsIgnoreCase("current")) {
            snapshot = settings;
        } else {
            String resource = switch (preset.toLowerCase(Locale.ROOT)) {
                case "default" -> ConfigFiles.DEFAULT_RESOURCE;
                case "iris-tall-world", "iris" -> ConfigFiles.LEGACY_DEFAULTS_RESOURCE;
                default -> throw new IllegalArgumentException("unknown preset: " + preset);
            };
            FileConfiguration presetConfig = ConfigFiles.resource(this::getResource, resource);
            snapshot = GeneratorSettings.load(presetConfig, null, messages);
        }
        if (snapshot == null) throw new IllegalStateException("settings are not ready");
        List<Long> seedsToRun = java.util.stream.LongStream.range(0, seeds).boxed().toList();
        HeightReference reference = snapshot.heightReferences().get(environment);
        if (reference == null) throw new IllegalArgumentException("no configured height for " + environment);
        return GenerationSimulation.simulate(snapshot, environment, reference.minY(), reference.maxY(),
                seedsToRun, chunks);
    }

    boolean acquireSimulationCooldown(CommandSender sender) {
        return !(sender instanceof Player player) || acquireSimulationCooldown(player.getUniqueId());
    }

    boolean acquireSimulationCooldown(UUID userId) {
        long now = System.currentTimeMillis();
        simulationCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
        Long previous = simulationCooldowns.get(userId);
        if (previous != null && now - previous < SIMULATION_COOLDOWN_MILLIS) return false;
        simulationCooldowns.put(userId, now);
        return true;
    }

    private void attach(World world) {
        GeneratorSettings current = settings;
        if (populator == null || current == null || !current.enables(world.getName(), world.getEnvironment())) {
            return;
        }
        List<BlockPopulator> populators = world.getPopulators();
        // PlugMan reloads use a new class loader. Remove an instance left by an
        // older OreVeins version before attaching the current populator.
        populators.removeIf(existing -> existing != populator
                && existing.getClass().getName().equals(VeinPopulator.class.getName()));
        if (!populators.contains(populator)) {
            populators.add(populator);
            getLogger().info(messages.console("startup.attached", "world", world.getName(),
                    "min", world.getMinHeight(), "max", world.getMaxHeight() - 1));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        Messages text = messages;
        if (!sender.hasPermission("oreveins.admin")) { text.send(sender, "web.error.no-permission"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("history")) {
            text.send(sender, "history.header", "available", retrofitter.historyAvailable());
            var rows = retrofitter.history();
            for (int i = rows.size() - 1; i >= Math.max(0, rows.size() - 15); i--) {
                var row = rows.get(i);
                text.send(sender, "history.row", "id", row.id(), "actor", row.actor(), "scope", row.scope(),
                        "started", java.time.Instant.ofEpochMilli(row.startedMillis()),
                        "ended", row.endedMillis() == 0 ? "-" : java.time.Instant.ofEpochMilli(row.endedMillis()),
                        "status", row.status(), "queued", row.queued(), "completed", row.completed(),
                        "failed", row.failed(), "skipped", row.skipped(), "cancelled", row.cancelled());
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            AdminActions.Outcome outcome = actions.reload();
            send(sender, messages, outcome);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            send(sender, text, actions.status(text.get(sender, "status.all-worlds")));
            text.send(sender, "exclusions.status", "areas", exclusions.areas().size(), "skipped", retrofitter.protectedChunksSkipped());
            text.send(sender, "maintenance.status", "enabled", retrofitSchedule.enabled(),
                    "start", retrofitSchedule.startUtc(), "end", retrofitSchedule.endUtc(),
                    "allowed", retrofitSchedule.allowed(java.time.Instant.now()), "budget", retrofitSchedule.maxWorkMillis(), "rate", retrofitSchedule.maxChunksPerMinute());
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stats")
                || args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            if (!sender.hasPermission("oreveins.admin")) {
                text.send(sender, "web.error.no-permission");
                return true;
            }
            sendGenerationStats(sender, text, args.length == 2 ? args[1] : null);
            return true;
        }
        if (args.length >= 1 && args.length <= 5 && args[0].equalsIgnoreCase("simulate")) {
            if (!sender.hasPermission("oreveins.admin")) {
                text.send(sender, "web.error.no-permission");
                return true;
            }
            handleSimulation(sender, args, text);
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("failed") || args[0].equalsIgnoreCase("retry"))) {
            if (!sender.hasPermission("oreveins.admin")) { text.send(sender, "web.error.no-permission"); return true; }
            if (args[0].equalsIgnoreCase("failed")) {
                var rows = retrofitter.failedChunks();
                text.send(sender, "failures.summary", "count", rows.size(), "blocked", retrofitter.failureStorageBlocked());
                rows.stream().limit(30).forEach(row -> text.send(sender, "failures.row", "world", row.key().world(),
                        "x", row.key().x(), "z", row.key().z(), "reason", row.reason(), "attempts", row.attempts()));
            } else if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
                text.send(sender, "failures.queued", "count", retrofitter.attributed(actor(sender), retrofitter::retryFailedChunks));
            } else text.send(sender, "failures.confirm");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("validate")) {
            try {
                ReadOnlyAnalysis.Validation report = validateCandidate();
                text.send(sender, report.valid() ? "analysis.validation-valid" : "analysis.validation-invalid",
                        "passes", report.validPasses(), "errors", report.errors().size(), "warnings", report.warnings().size());
                for (ReadOnlyAnalysis.Problem problem : report.errors()) text.send(sender, "analysis.problem", "kind", problem.code(), "subject", problem.subject());
                for (ReadOnlyAnalysis.Problem problem : report.warnings()) text.send(sender, "analysis.warning", "kind", problem.code(), "subject", problem.subject());
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                text.send(sender, "analysis.read-failed", "error", failure.getMessage());
            }
            return true;
        }
        if ((args.length == 1 || args.length == 2) && args[0].equalsIgnoreCase("compare")) {
            try {
                String preset = args.length == 2 ? args[1] : "iris-tall-world";
                ReadOnlyAnalysis.Comparison report = comparePreset(preset);
                text.send(sender, "analysis.comparison", "preset", report.preset(), "changed", report.changedPasses(), "references", report.referenceChanges().size());
                for (String change : report.referenceChanges()) text.send(sender, "analysis.reference-change", "change", change);
                for (ReadOnlyAnalysis.PassDelta delta : report.passes()) text.send(sender, "analysis.pass-change",
                        "id", delta.id(), "state", text.get(sender, "analysis.pass-state." + delta.state()),
                        "fields", delta.fields(), "effective-y", delta.effectiveY());
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                text.send(sender, "analysis.read-failed", "error", failure.getMessage());
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("web")) {
            openWeb(sender, text);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("regenerate")) {
            return handleRegenerate(sender, args, text);
        }
        text.send(sender, "usage", "label", label);
        return true;
    }

    private void sendGenerationStats(CommandSender sender, Messages text, String worldFilter) {
        Map<GenerationStatistics.Key, Long> snapshot = GenerationStatistics.snapshot();
        List<Map.Entry<GenerationStatistics.Key, Long>> entries = snapshot.entrySet().stream()
                .filter(entry -> worldFilter == null
                        || entry.getKey().worldName().equalsIgnoreCase(worldFilter))
                .sorted(Map.Entry.<GenerationStatistics.Key, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().worldName())
                        .thenComparing(entry -> entry.getKey().material().name()))
                .toList();
        long total = entries.stream().mapToLong(Map.Entry::getValue).sum();
        long worlds = entries.stream().map(entry -> entry.getKey().worldName()).distinct().count();
        text.send(sender, "stats.summary", "world", worldFilter == null ? "all" : worldFilter,
                "worlds", worlds, "ores", entries.size(), "blocks", total);
        int outputCount = Math.min(entries.size(), 20);
        for (int index = 0; index < outputCount; index++) {
            Map.Entry<GenerationStatistics.Key, Long> entry = entries.get(index);
            text.send(sender, "stats.row", "world", entry.getKey().worldName(),
                    "ore", entry.getKey().material().name(), "blocks", entry.getValue());
        }
        if (entries.size() > outputCount) text.send(sender, "stats.truncated", "shown", outputCount,
                "total", entries.size());
    }

    private void handleSimulation(CommandSender sender, String[] args, Messages text) {
        String preset = args.length >= 2 ? args[1] : "current";
        int seeds = 1;
        int chunks = 1;
        World.Environment environment = World.Environment.NORMAL;
        try {
            if (!SIMULATION_PRESETS.contains(preset.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("preset");
            }
            if (args.length >= 3) seeds = parseBounded(args[2], 1, GenerationSimulation.MAX_SEEDS);
            if (args.length >= 4) chunks = parseBounded(args[3], 1, GenerationSimulation.MAX_CHUNKS);
            if (args.length >= 5) {
                environment = switch (args[4].toLowerCase(Locale.ROOT)) {
                    case "normal", "overworld" -> World.Environment.NORMAL;
                    case "nether" -> World.Environment.NETHER;
                    default -> throw new IllegalArgumentException("environment");
                };
            }
        } catch (IllegalArgumentException invalidInput) {
            text.send(sender, "analysis.simulation-usage");
            return;
        }
        if (!acquireSimulationCooldown(sender)) {
            text.send(sender, "analysis.simulation-cooldown");
            return;
        }
        try {
            GenerationSimulation.Report report = simulatePreset(preset, environment, seeds, chunks);
            Map<org.bukkit.Material, Long> totals = new java.util.EnumMap<>(org.bukkit.Material.class);
            report.results().forEach(result -> result.placedByMaterial()
                    .forEach((material, count) -> totals.merge(material, count, Long::sum)));
            long totalBlocks = totals.values().stream().mapToLong(Long::longValue).sum();
            text.send(sender, "analysis.simulation-summary", "preset", preset,
                    "environment", environment.name(), "seeds", report.seedsSimulated(),
                    "chunks", report.chunksPerSeed(), "blocks", totalBlocks, "label", report.label());
            totals.entrySet().stream().sorted(Map.Entry.<org.bukkit.Material, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(20).forEach(entry -> text.send(sender, "analysis.simulation-row",
                            "ore", entry.getKey().name(), "blocks", entry.getValue()));
            if (totals.isEmpty()) text.send(sender, "analysis.simulation-empty");
            report.materialDistributions().stream().limit(20).forEach(row -> text.send(sender, "simulation-distribution.row",
                    "ore", row.material().name(), "samples", row.sampleSizeSeeds(),
                    "min", String.format(Locale.ROOT, "%.2f", row.minPerChunk()),
                    "max", String.format(Locale.ROOT, "%.2f", row.maxPerChunk()),
                    "mean", String.format(Locale.ROOT, "%.2f", row.meanPerChunk()),
                    "stddev", String.format(Locale.ROOT, "%.2f", row.populationStdDevPerChunk())));
            text.send(sender, "simulation-distribution.note");
            report.connectedVeinSizes().stream().limit(20).forEach(row -> text.send(sender, "simulation-connected.row",
                    "ore", row.material().name(), "count", row.components(), "min", row.min(), "max", row.max(), "median", row.median()));
            text.send(sender, "simulation-connected.note");
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal
                    && illegal.getMessage() != null && illegal.getMessage().startsWith("simulation work budget")) {
                text.send(sender, "analysis.simulation-too-large",
                        "budget", GenerationSimulation.MAX_MODELED_BLOCK_ATTEMPTS);
            } else {
                text.send(sender, "analysis.read-failed", "error", failure.getMessage());
            }
        }
    }

    private static int parseBounded(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("out of range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("not an integer", invalid);
        }
    }

    private static String actor(CommandSender sender) { return sender instanceof Player player ? player.getUniqueId().toString() : "console"; }

    private void stopRegionPreview() {
        if (regionPreviewTask != null) { regionPreviewTask.cancel(); regionPreviewTask = null; }
        if (regionPreview != null) { regionPreview.cancel(); regionPreview = null; }
    }

    /** Capture paths/height metadata on the server thread; region headers are read in bounded async steps. */
    private void startRegionPreview(CommandSender sender) {
        if (regionPreview != null || System.nanoTime() < nextRegionPreviewNanos) {
            messages.send(sender, "region-preview.busy"); return;
        }
        List<java.nio.file.Path> paths = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!settings.enables(world.getName(), world.getEnvironment())) continue;
            paths.addAll(ExistingChunkRetrofitter.regionDirectories(world));
            messages.send(sender, "region-preview.world", "world", world.getName(), "min", world.getMinHeight(),
                    "max", world.getMaxHeight() - 1, "sections", (world.getMaxHeight() - world.getMinHeight() + 15) / 16);
        }
        RegionFilesPreview preview;
        try { preview = new RegionFilesPreview(paths); }
        catch (IllegalArgumentException invalid) { messages.send(sender, "analysis.read-failed", "error", invalid.getMessage()); return; }
        regionPreview = preview;
        nextRegionPreviewNanos = System.nanoTime() + 30_000_000_000L;
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        messages.send(sender, "region-preview.started");
        var task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                var result = preview.step(16);
                if (!result.complete() && !result.cancelled()) return;
                cancel();
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(OreVeinsPlugin.this, () -> {
                    if (regionPreview != preview) return;
                    regionPreview = null; regionPreviewTask = null;
                    CommandSender target = viewer == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(viewer);
                    if (target == null || !target.hasPermission("oreveins.admin")) return;
                    messages.send(target, "region-preview.result", "chunks", result.storedChunks(),
                            "files", result.filesScanned(), "errors", result.errors());
                    for (String detail : result.details()) messages.send(target, "region-preview.error", "detail", detail);
                });
            }
        };
        regionPreviewTask = task.runTaskTimerAsynchronously(this, 1L, 1L);
    }

    private boolean handleRegenerate(CommandSender sender, String[] args, Messages text) {
        if (args.length == 3 && args[1].equalsIgnoreCase("preview") && args[2].equalsIgnoreCase("all")) {
            startRegionPreview(sender);
        } else if (args.length == 3 && args[1].equalsIgnoreCase("preview") && args[2].equalsIgnoreCase("cancel")) {
            stopRegionPreview(); text.send(sender, "region-preview.cancelled");
        } else if (args.length == 2 && args[1].equalsIgnoreCase("preview")) {
            text.send(sender, "analysis.preview", "report", loadedPreview());
        } else if (args.length == 2 && args[1].equalsIgnoreCase("loaded")) {
            send(sender, text, actions.attributed(actor(sender), actions::regenerateLoaded));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            send(sender, text, actions.attributed(actor(sender), () -> actions.regenerateAll(finished -> send(sender, text, finished))));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            send(sender, text, actions.cancel());
        } else if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                text.send(sender, "regenerate.radius.player-only");
                return true;
            }
            OptionalInt radius = AdminActions.parseRadius(args[1]);
            if (radius.isEmpty()) {
                text.send(sender, "regenerate.radius.not-a-number");
                return true;
            }
            send(sender, text, actions.attributed(actor(sender), () -> actions.regenerateRadius(player, radius.getAsInt())));
        } else if (args.length == 5 && args[1].equalsIgnoreCase("chunk")) {
            send(sender, text, actions.attributed(actor(sender), () -> actions.regenerateChunk(args[2], args[3], args[4])));
        } else {
            text.send(sender, "usage", "label", "oreveins");
        }
        return true;
    }

    private static void send(CommandSender sender, Messages text, AdminActions.Outcome outcome) {
        for (AdminActions.Line line : outcome.lines()) {
            text.send(sender, line.key(), line.placeholders());
        }
    }

    private void openWeb(CommandSender sender, Messages text) {
        WebIntegration current = web;
        if (current == null) {
            text.send(sender, "web.unavailable");
        } else if (!(sender instanceof Player player)) {
            text.send(sender, "web.player-only");
        } else {
            current.open(player);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        try {
            return complete(sender, args, web != null,
                    () -> Bukkit.getWorlds().stream().map(World::getName).toList());
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    /** Suggestions on every level for senders with oreveins.admin, prefix-filtered case-insensitively. */
    static List<String> complete(CommandSender sender, String[] args, boolean webAvailable,
            java.util.function.Supplier<List<String>> worlds) {
        if (args.length == 0 || !sender.hasPermission("oreveins.admin")) return List.of();
        List<String> options;
        if (args.length == 1) {
            options = webAvailable ? SUBCOMMANDS_WITH_WEB : SUBCOMMANDS;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("simulate")) {
            options = SIMULATION_PRESETS;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("simulate")) {
            options = List.of("1", "4", "16");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("simulate")) {
            options = List.of("1", "2", "4");
        } else if (args.length == 5 && args[0].equalsIgnoreCase("simulate")) {
            options = List.of("overworld", "nether");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("compare")) {
            options = List.of("default", "iris-tall-world");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            options = worlds.get();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("retry")) {
            options = List.of("confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("regenerate")) {
            options = REGENERATE_TARGETS;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("regenerate") && args[1].equalsIgnoreCase("preview")) {
            options = List.of("all", "cancel");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("regenerate") && args[1].equalsIgnoreCase("chunk")) {
            options = worlds.get();
        } else if ((args.length == 4 || args.length == 5) && args[0].equalsIgnoreCase("regenerate") && args[1].equalsIgnoreCase("chunk")) {
            options = List.of("0");
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
