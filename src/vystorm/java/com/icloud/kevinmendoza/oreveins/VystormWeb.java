/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRequest;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import Vystorm.vystorm_core.web.WebServices;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * The optional web page on Vystorm Core (0.24.0+):
 *
 * <ul>
 *   <li>module {@code oreveins} (every web user): the ore guide, published as
 *       data {@code guide} from the loaded config and worlds;</li>
 *   <li>module {@code oreveins-admin} (unlisted, permission {@code oreveins.admin}):
 *       status, live progress (event {@code status}) and the admin actions of
 *       {@code /oreveins}, through the same {@link AdminActions} methods.</li>
 * </ul>
 *
 * Handlers run off the server thread; everything touching Bukkit or OreVeins
 * state goes through {@code request.sync(...)}.
 */
final class VystormWeb implements WebIntegration {
    static final String MODULE = "oreveins";
    static final String ADMIN_MODULE = "oreveins-admin";
    static final String ADMIN_PERMISSION = "oreveins.admin";
    static final String GUIDE_DATA = "guide";
    static final String STATUS_EVENT = "status";
    private static final List<String> LANG_STUBS = List.of("en", "de");
    private static final String LANG_STUB = """
            # OreVeins texts ({code}). Add only the keys you want to change, for example
            #   prefix: "[Ores] "
            # and run /oreveins reload. Every other key keeps the bundled text from
            # lang/{code}.yml inside the jar (chat messages and the web page texts under web.).
            """;

    private final OreVeinsPlugin plugin;
    private final AdminActions actions;
    private final RateMeter rate = new RateMeter(60_000L);
    private Lang lang;
    private BukkitTask ticker;
    private volatile JsonObject status;
    private String lastPushed;
    private boolean guideQueued;

    VystormWeb(OreVeinsPlugin plugin, AdminActions actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    void start() {
        createLanguageStubs();
        lang = CoreApi.api().orElseThrow().lang().register(plugin);
        lang.setLanguage(plugin.languageSetting());
        status = captureStatus();
        WebServices.register(plugin, WebModule.builder(MODULE, lang.ref("web.module.title"))
                .description(lang.ref("web.module.description"))
                .build());
        WebServices.register(plugin, WebModule.builder(ADMIN_MODULE, lang.ref("web.module.admin-title"))
                .description(lang.ref("web.module.admin-description"))
                .permission(ADMIN_PERMISSION)
                .listed(false)
                .snapshot(context -> {
                    JsonObject current = status;
                    return current == null ? Map.of() : Map.of(STATUS_EVENT, current);
                })
                .route(WebRoute.get("/status", request -> WebResponse.json(request.sync(this::refreshStatus)))
                        .permission(ADMIN_PERMISSION).rateLimit(60).build())
                .route(WebRoute.get("/generation/stats", request -> WebResponse.json(request.sync(this::generationStats)))
                        .permission(ADMIN_PERMISSION).rateLimit(12).build())
                .route(WebRoute.get("/regeneration/history", request -> WebResponse.json(request.sync(this::regenerationHistory)))
                        .permission(ADMIN_PERMISSION).rateLimit(12).build())
                .route(WebRoute.get("/regeneration/exclusions", request -> WebResponse.json(request.sync(() -> regenerationExclusions(request))))
                        .permission(ADMIN_PERMISSION).rateLimit(12).build())
                .route(WebRoute.post("/generation/simulate", this::simulate)
                        .permission(ADMIN_PERMISSION).requiresOnline().rateLimit(6).maxBody(256).build())
                .route(action("/reload", "reload", 10, request -> actions.reload()))
                .route(action("/regenerate/all", "regenerate all", 6,
                        request -> actions.regenerateAll(this::scanFinished)))
                .route(action("/regenerate/loaded", "regenerate loaded", 6, request -> actions.regenerateLoaded()))
                .route(WebRoute.post("/regenerate/preview", request -> WebResponse.json(request.sync(() -> {
                    JsonObject result = new JsonObject(); result.addProperty("mode", "loaded-only");
                    result.addProperty("report", plugin.loadedPreview()); return result;
                }))).permission(ADMIN_PERMISSION).rateLimit(12).maxBody(64).build())
                .route(WebRoute.post("/validate", request -> WebResponse.json(request.sync(() -> validation(request))))
                        .permission(ADMIN_PERMISSION).rateLimit(10).maxBody(64).build())
                .route(WebRoute.post("/preset/compare", request -> WebResponse.json(request.sync(() -> comparison(request))))
                        .permission(ADMIN_PERMISSION).rateLimit(10).maxBody(128).build())
                .route(action("/regenerate/cancel", "regenerate cancel", 10, request -> actions.cancel()))
                .route(WebRoute.post("/regenerate/radius", this::regenerateRadius)
                        .permission(ADMIN_PERMISSION).requiresOnline().rateLimit(12).maxBody(256).build())
                .build());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        queueGuide();
        plugin.getLogger().info(plugin.messages().console("web.registered",
                "active", WebServices.available()));
    }

    @Override
    public void reloaded() {
        if (lang != null) {
            lang.reload();
            lang.setLanguage(plugin.languageSetting());
        }
        queueGuide();
    }

    @Override
    public void worldsChanged() {
        queueGuide();
    }

    @Override
    public void open(Player player) {
        WebServices.openOrLink(player, MODULE, "admin", null);
    }

    @Override
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        try {
            WebServices.unregister(plugin);
        } catch (RuntimeException | LinkageError ignored) {
            // Core cleans up modules of disabled plugins as well.
        }
    }

    // ------------------------------------------------------------------ routes

    private interface Action {
        AdminActions.Outcome run(WebRequest request);
    }

    /**
     * A POST route for an admin action: permission checked by Core for the route
     * (and the module), the action itself runs on the server thread through the
     * same method as the command. The answer carries the command's feedback
     * lines and the fresh status.
     */
    private WebRoute action(String path, String label, int perMinute, Action action) {
        return WebRoute.post(path, request -> {
            JsonObject answer = request.sync(() -> {
                plugin.getLogger().info(plugin.messages().console("web.action-log",
                        "player", request.playerName(), "action", label));
                AdminActions.Outcome outcome = actions.attributed(request.playerId().toString(), () -> action.run(request));
                return answer(request, outcome);
            });
            return WebResponse.json(answer);
        }).permission(ADMIN_PERMISSION).rateLimit(perMinute).maxBody(256).build();
    }

    /** {@code /oreveins regenerate <radius>} for the player's own position; needs the player online. */
    private WebResponse regenerateRadius(WebRequest request) {
        JsonElement body = request.body();
        JsonElement value = body != null && body.isJsonObject() ? body.getAsJsonObject().get("radius") : null;
        Integer radius = null;
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
            double number = primitive.getAsDouble();
            if (number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE) {
                radius = (int) number;
            }
        }
        Integer checked = radius;
        JsonObject answer = request.sync(() -> {
            Player player = Bukkit.getPlayer(request.playerId());
            if (player == null) {
                throw WebException.conflict(lang.plain(request.language(), "web.error.offline"));
            }
            if (!player.hasPermission(ADMIN_PERMISSION)) {
                throw WebException.forbidden(lang.plain(request.language(), "web.error.no-permission"));
            }
            if (checked == null) {
                return answer(request, AdminActions.Outcome.failed(
                        new AdminActions.Line("regenerate.radius.not-a-number")));
            }
            plugin.getLogger().info(plugin.messages().console("web.action-log",
                    "player", request.playerName(), "action", "regenerate " + checked));
            return answer(request, actions.attributed(request.playerId().toString(), () -> actions.regenerateRadius(player, checked)));
        });
        return WebResponse.json(answer);
    }

    private JsonObject answer(WebRequest request, AdminActions.Outcome outcome) {
        Messages messages = plugin.messages();
        JsonObject answer = WebModel.outcome(outcome, messages, messages.languageForCode(request.language()));
        answer.add(STATUS_EVENT, refreshStatus());
        return answer;
    }

    private JsonObject validation(WebRequest request) {
        JsonObject result = new JsonObject();
        try {
            ReadOnlyAnalysis.Validation report = plugin.validateCandidate();
            result.addProperty("valid", report.valid()); result.addProperty("validPasses", report.validPasses());
            JsonArray errors = new JsonArray(); report.errors().forEach(problem -> errors.add(problem.code() + ": " + problem.subject()));
            JsonArray warnings = new JsonArray(); report.warnings().forEach(problem -> warnings.add(problem.code() + ": " + problem.subject()));
            result.add("errors", errors); result.add("warnings", warnings);
        } catch (Exception failure) { result.addProperty("error", String.valueOf(failure.getMessage())); }
        return result;
    }

    private JsonObject comparison(WebRequest request) {
        JsonObject result = new JsonObject();
        try {
            JsonElement body = request.body();
            JsonElement presetValue = body != null && body.isJsonObject() ? body.getAsJsonObject().get("preset") : null;
            String preset = presetValue == null ? "iris-tall-world"
                    : presetValue.isJsonPrimitive() && presetValue.getAsJsonPrimitive().isString()
                            ? presetValue.getAsString() : null;
            if (preset == null || !List.of("default", "iris-tall-world").contains(preset.toLowerCase(Locale.ROOT))) {
                result.addProperty("error", plugin.messages().plain(request.language(), "web.error.compare-preset"));
                return result;
            }
            ReadOnlyAnalysis.Comparison report = plugin.comparePreset(preset.toLowerCase(Locale.ROOT));
            result.addProperty("preset", report.preset());
            JsonArray references = new JsonArray(); report.referenceChanges().forEach(references::add);
            result.add("referenceChanges", references);
            JsonArray passes = new JsonArray();
            for (ReadOnlyAnalysis.PassDelta delta : report.passes()) {
                JsonObject item = new JsonObject(); item.addProperty("id", delta.id()); item.addProperty("state", delta.state());
                JsonArray fields = new JsonArray(); delta.fields().forEach(fields::add); item.add("fields", fields);
                JsonArray y = new JsonArray(); delta.effectiveY().forEach(y::add); item.add("effectiveY", y); passes.add(item);
            }
            result.add("passes", passes);
        } catch (Exception failure) { result.addProperty("error", String.valueOf(failure.getMessage())); }
        return result;
    }

    private JsonObject generationStats() {
        JsonObject result = new JsonObject();
        Map<GenerationStatistics.Key, Long> snapshot = GenerationStatistics.snapshot();
        long total = snapshot.values().stream().mapToLong(Long::longValue).sum();
        long worlds = snapshot.keySet().stream().map(GenerationStatistics.Key::worldName).distinct().count();
        result.addProperty("sinceStartup", true);
        result.addProperty("totalOreWrites", total);
        result.addProperty("trackedWorlds", worlds);
        JsonArray rows = new JsonArray();
        snapshot.entrySet().stream()
                .sorted(Map.Entry.<GenerationStatistics.Key, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().worldName())
                        .thenComparing(entry -> entry.getKey().material().name()))
                .limit(20)
                .forEach(entry -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("world", entry.getKey().worldName());
                    row.addProperty("ore", entry.getKey().material().name());
                    row.addProperty("writes", entry.getValue());
                    rows.add(row);
                });
        result.add("worldOreCounts", rows);
        result.addProperty("truncated", snapshot.size() > 20);
        return result;
    }

    /** Bounded read-only snapshot matching the most recent entries shown by /oreveins history. */
    private JsonObject regenerationHistory() {
        ExistingChunkRetrofitter retrofitter = actions.retrofitter();
        List<RegenerationHistory.Entry> entries = retrofitter.history();
        JsonObject result = new JsonObject();
        result.addProperty("available", retrofitter.historyAvailable());
        JsonArray rows = new JsonArray();
        int start = Math.max(0, entries.size() - 15);
        for (int i = start; i < entries.size(); i++) {
            RegenerationHistory.Entry entry = entries.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.id().toString());
            row.addProperty("actor", entry.actor());
            row.addProperty("scope", entry.scope());
            row.addProperty("startedAt", entry.startedMillis());
            row.addProperty("endedAt", entry.endedMillis());
            row.addProperty("queued", entry.queued());
            row.addProperty("completed", entry.completed());
            row.addProperty("failed", entry.failed());
            row.addProperty("skipped", entry.skipped());
            row.addProperty("cancelled", entry.cancelled());
            row.addProperty("status", entry.status().name());
            rows.add(row);
        }
        result.add("entries", rows);
        result.addProperty("truncated", entries.size() > rows.size());
        return result;
    }

    /** Read-only view of the active bounded exclusion config and current skipped-chunk count. */
    private JsonObject regenerationExclusions(WebRequest request) {
        JsonObject result = new JsonObject();
        try {
            RegenerationExclusions active = RegenerationExclusions.load(plugin.getConfig());
            JsonArray areas = new JsonArray();
            for (RegenerationExclusions.Area area : active.areas()) {
                JsonObject row = new JsonObject();
                row.addProperty("world", area.world());
                row.addProperty("minX", area.minX()); row.addProperty("maxX", area.maxX());
                row.addProperty("minZ", area.minZ()); row.addProperty("maxZ", area.maxZ());
                areas.add(row);
            }
            result.add("areas", areas);
            result.addProperty("protectedChunksSkipped", actions.retrofitter().protectedChunksSkipped());
        } catch (RuntimeException invalid) {
            result.addProperty("error", plugin.messages().plain(request.language(), "web.error.exclusion-read"));
        }
        return result;
    }

    private WebResponse simulate(WebRequest request) {
        SimulationInput input = parseSimulationInput(request.body());
        if (input == null) {
            JsonObject invalid = new JsonObject();
            invalid.addProperty("error", plugin.messages().plain(request.language(), "web.error.simulation-input"));
            return WebResponse.json(invalid);
        }
        JsonObject answer = request.sync(() -> {
            Player player = Bukkit.getPlayer(request.playerId());
            if (player == null) {
                throw WebException.conflict(lang.plain(request.language(), "web.error.offline"));
            }
            if (!player.hasPermission(ADMIN_PERMISSION)) {
                throw WebException.forbidden(lang.plain(request.language(), "web.error.no-permission"));
            }
            if (!plugin.acquireSimulationCooldown(player.getUniqueId())) {
                JsonObject limited = new JsonObject();
                limited.addProperty("error", plugin.messages().plain(request.language(), "web.error.simulation-cooldown"));
                return limited;
            }
            try {
                GenerationSimulation.Report report = plugin.simulatePreset(input.preset(), input.environment(),
                        input.seeds(), input.chunks());
                JsonObject result = new JsonObject();
                result.addProperty("mode", "synthetic-simulation");
                result.addProperty("label", report.label());
                result.addProperty("limitations", report.limitations());
                JsonArray distributions = new JsonArray();
                for (var distribution : report.materialDistributions()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("material", distribution.material().name());
                    row.addProperty("sampleSizeSeeds", distribution.sampleSizeSeeds());
                    row.addProperty("chunksPerSeed", distribution.chunksPerSeed());
                    row.addProperty("minPerChunk", distribution.minPerChunk());
                    row.addProperty("maxPerChunk", distribution.maxPerChunk());
                    row.addProperty("meanPerChunk", distribution.meanPerChunk());
                    row.addProperty("populationStdDevPerChunk", distribution.populationStdDevPerChunk());
                    distributions.add(row);
                }
                result.add("distributions", distributions);
                JsonArray connected = new JsonArray();
                for (var sizes : report.connectedVeinSizes()) {
                    JsonObject row = new JsonObject(); row.addProperty("material", sizes.material().name());
                    row.addProperty("components", sizes.components()); row.addProperty("min", sizes.min()); row.addProperty("max", sizes.max());
                    row.addProperty("mean", sizes.mean()); row.addProperty("median", sizes.median()); connected.add(row);
                }
                result.add("connectedVeinSizes", connected);
                result.addProperty("preset", input.preset());
                result.addProperty("environment", input.environment().name());
                result.addProperty("seeds", report.seedsSimulated());
                result.addProperty("chunksPerSeed", report.chunksPerSeed());
                Map<org.bukkit.Material, Long> totals = new java.util.EnumMap<>(org.bukkit.Material.class);
                report.results().forEach(seed -> seed.placedByMaterial()
                        .forEach((material, count) -> totals.merge(material, count, Long::sum)));
                long totalBlocks = totals.values().stream().mapToLong(Long::longValue).sum();
                result.addProperty("placedBlocks", totalBlocks);
                JsonArray materials = new JsonArray();
                totals.entrySet().stream().sorted(Map.Entry.<org.bukkit.Material, Long>
                        comparingByValue(Comparator.reverseOrder())).limit(20).forEach(entry -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("material", entry.getKey().name());
                    row.addProperty("blocks", entry.getValue());
                    materials.add(row);
                });
                result.add("materialCounts", materials);
                return result;
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                JsonObject failed = new JsonObject();
                String key = failure instanceof IllegalArgumentException illegal
                        && illegal.getMessage() != null && illegal.getMessage().startsWith("simulation work budget")
                        ? "web.error.simulation-budget" : "web.error.simulation-input";
                failed.addProperty("error", plugin.messages().plain(request.language(), key));
                return failed;
            }
        });
        return WebResponse.json(answer);
    }

    private static SimulationInput parseSimulationInput(JsonElement body) {
        if (body == null || !body.isJsonObject()) return null;
        JsonObject object = body.getAsJsonObject();
        String preset = stringField(object, "preset", "current");
        String environment = stringField(object, "environment", "overworld").toLowerCase(Locale.ROOT);
        Integer seeds = intField(object, "seeds", 1);
        Integer chunks = intField(object, "chunks", 1);
        if (preset == null || !List.of("current", "default", "iris-tall-world").contains(preset.toLowerCase(Locale.ROOT))
                || seeds == null || seeds < 1 || seeds > GenerationSimulation.MAX_SEEDS
                || chunks == null || chunks < 1 || chunks > GenerationSimulation.MAX_CHUNKS) return null;
        org.bukkit.World.Environment parsedEnvironment = switch (environment) {
            case "normal", "overworld" -> org.bukkit.World.Environment.NORMAL;
            case "nether" -> org.bukkit.World.Environment.NETHER;
            default -> null;
        };
        return parsedEnvironment == null ? null
                : new SimulationInput(preset, parsedEnvironment, seeds, chunks);
    }

    private static String stringField(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer intField(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return new java.math.BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private record SimulationInput(String preset, org.bukkit.World.Environment environment, int seeds, int chunks) {
    }

    /** The end of a web-started {@code regenerate all} scan goes to the console (the web user sees the status). */
    private void scanFinished(AdminActions.Outcome finished) {
        Messages messages = plugin.messages();
        for (AdminActions.Line line : finished.lines()) {
            plugin.getLogger().info(messages.plain(messages.consoleLanguage(), line.key(), line.placeholders()));
        }
        refreshStatus();
    }

    // ------------------------------------------------------------------ live state

    private void tick() {
        rate.sample(System.currentTimeMillis(), actions.retrofitter().processedChunks());
        refreshStatus();
        if (guideQueued) {
            publishGuide();
        }
    }

    /** Captures the status (server thread) and pushes it to open admin pages when it changed. */
    private JsonObject refreshStatus() {
        JsonObject current = captureStatus();
        status = current;
        String json = current.toString();
        if (!json.equals(lastPushed)) {
            lastPushed = json;
            guarded("push", () -> WebServices.broadcast(ADMIN_MODULE, STATUS_EVENT, current));
        }
        return current;
    }

    private JsonObject captureStatus() {
        ExistingChunkRetrofitter retrofitter = actions.retrofitter();
        JsonObject result = WebModel.status(new WebModel.StatusView(
                plugin.settings(), plugin.legacyConfig(), plugin.languageSetting(),
                retrofitter.processedChunks(), retrofitter.manuallyRegeneratedChunks(),
                retrofitter.queuedChunks(), retrofitter.allScanRunning(),
                retrofitter.campaignActive(), retrofitter.campaignDone(), retrofitter.campaignRemaining(),
                rate.perMinute(), actions.lastScan()));
        RetrofitSchedule schedule = plugin.maintenanceSchedule();
        JsonObject maintenance = new JsonObject();
        maintenance.addProperty("enabled", schedule.enabled());
        maintenance.addProperty("startUtc", schedule.startUtc().toString());
        maintenance.addProperty("endUtc", schedule.endUtc().toString());
        maintenance.addProperty("allowedNow", schedule.allowed(java.time.Instant.now()));
        maintenance.addProperty("maxChunksPerMinute", schedule.maxChunksPerMinute());
        maintenance.addProperty("maxWorkMillisPerTick", schedule.maxWorkMillis());
        result.add("maintenance", maintenance);
        result.addProperty("protectedChunksSkipped", retrofitter.protectedChunksSkipped());
        return result;
    }

    private void queueGuide() {
        guideQueued = true;
    }

    /** Publishes the guide for the worlds loaded right now (server thread, at most once per second). */
    private void publishGuide() {
        guideQueued = false;
        List<WebModel.WorldView> worlds = Bukkit.getWorlds().stream().map(WebModel.WorldView::of).toList();
        JsonObject guide = WebModel.guide(plugin.settings(), worlds);
        guarded("publish", () -> WebServices.publishData(plugin, MODULE, GUIDE_DATA, guide));
    }

    private void guarded(String what, Supplier<?> call) {
        try {
            call.get();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, plugin.messages().console("web.call-failed",
                    "call", what, "error", String.valueOf(failure)), failure);
        }
    }

    /**
     * Core copies a plugin's bundled lang files to plugins/OreVeins/lang/ when
     * they are missing. OreVeins reads that folder as single-key overrides, so a
     * full copy would freeze every text at this version. A comment-only file
     * keeps both systems on the bundled texts until an admin adds keys.
     */
    private void createLanguageStubs() {
        Path folder = plugin.getDataFolder().toPath().resolve("lang");
        for (String code : LANG_STUBS) {
            Path file = folder.resolve(code + ".yml");
            if (Files.exists(file)) {
                continue;
            }
            try {
                Files.createDirectories(folder);
                Files.writeString(file, LANG_STUB.replace("{code}", code), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                plugin.getLogger().log(Level.WARNING, "Could not create " + file, failure);
            }
        }
    }
}
