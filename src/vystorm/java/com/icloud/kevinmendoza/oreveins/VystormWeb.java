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
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
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
                .route(action("/reload", "reload", 10, request -> actions.reload()))
                .route(action("/regenerate/all", "regenerate all", 6,
                        request -> actions.regenerateAll(this::scanFinished)))
                .route(action("/regenerate/loaded", "regenerate loaded", 6, request -> actions.regenerateLoaded()))
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
                AdminActions.Outcome outcome = action.run(request);
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
            return answer(request, actions.regenerateRadius(player, checked));
        });
        return WebResponse.json(answer);
    }

    private JsonObject answer(WebRequest request, AdminActions.Outcome outcome) {
        Messages messages = plugin.messages();
        JsonObject answer = WebModel.outcome(outcome, messages, messages.languageForCode(request.language()));
        answer.add(STATUS_EVENT, refreshStatus());
        return answer;
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
        return WebModel.status(new WebModel.StatusView(
                plugin.settings(), plugin.legacyConfig(), plugin.languageSetting(),
                retrofitter.processedChunks(), retrofitter.manuallyRegeneratedChunks(),
                retrofitter.queuedChunks(), retrofitter.allScanRunning(),
                retrofitter.campaignActive(), retrofitter.campaignDone(), retrofitter.campaignRemaining(),
                rate.perMinute(), actions.lastScan()));
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
