/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * JSON views for the optional web page: the public ore guide and the admin
 * status. Plain data only (no platform classes), so it is tested without a
 * server and without the web platform.
 */
final class WebModel {
    private WebModel() {
    }

    /** A loaded world as the guide shows it; {@code maxHeight} is exclusive. */
    record WorldView(String name, World.Environment environment, int minHeight, int maxHeight) {
        static WorldView of(World world) {
            return new WorldView(world.getName(), world.getEnvironment(), world.getMinHeight(), world.getMaxHeight());
        }
    }

    /** Everything the admin panel shows, captured on the server thread. */
    record StatusView(
            GeneratorSettings settings,
            boolean legacyConfig,
            String language,
            long processed,
            long manual,
            int queued,
            boolean scanning,
            boolean campaignActive,
            int campaignDone,
            int campaignRemaining,
            double chunksPerMinute,
            AdminActions.ScanReport lastScan
    ) {
    }

    /**
     * The ore guide: per enabled world its height and every generation pass
     * of that world's environment with the Y range scaled to the world, the
     * veins per chunk and the most blocks a chunk can get from the pass.
     */
    static JsonObject guide(GeneratorSettings settings, List<WorldView> worlds) {
        JsonObject root = new JsonObject();
        root.addProperty("replaceExisting", settings.replaceExistingOres());
        JsonArray worldArray = new JsonArray();
        for (WorldView world : worlds) {
            if (!settings.enables(world.name(), world.environment())) {
                continue;
            }
            HeightReference reference = settings.heightReferences().get(world.environment());
            JsonObject entry = new JsonObject();
            entry.addProperty("name", world.name());
            entry.addProperty("environment", world.environment().name());
            entry.addProperty("minY", world.minHeight());
            entry.addProperty("maxY", world.maxHeight() - 1);
            JsonArray passes = new JsonArray();
            if (reference != null) {
                for (VeinDefinition vein : settings.veins()) {
                    if (vein.environment() == world.environment()) {
                        passes.add(pass(vein, reference, world));
                    }
                }
            }
            entry.add("passes", passes);
            worldArray.add(entry);
        }
        root.add("worlds", worldArray);
        return root;
    }

    private static JsonObject pass(VeinDefinition vein, HeightReference reference, WorldView world) {
        int minY = VeinPopulator.scaledMinY(reference, vein, world.minHeight(), world.maxHeight());
        int maxY = VeinPopulator.scaledMaxY(reference, vein, world.minHeight(), world.maxHeight());
        double veinsPerChunk = vein.attemptsPerChunk() * vein.spawnChance();
        JsonObject pass = new JsonObject();
        pass.addProperty("id", vein.id());
        pass.addProperty("ore", family(vein.material()));
        pass.addProperty("material", key(vein.material()));
        if (vein.deepslateMaterial() != null) {
            pass.addProperty("deepslateMaterial", key(vein.deepslateMaterial()));
        }
        pass.addProperty("host", vein.hostType().name());
        pass.addProperty("attempts", vein.attemptsPerChunk());
        pass.addProperty("spawnChance", vein.spawnChance());
        pass.addProperty("veinsPerChunk", round(veinsPerChunk));
        pass.addProperty("size", vein.size());
        pass.addProperty("blocksPerChunk", round(veinsPerChunk * vein.size()));
        pass.addProperty("minY", minY);
        pass.addProperty("maxY", maxY);
        pass.addProperty("active", maxY >= minY);
        pass.addProperty("distribution", vein.distribution().name());
        pass.addProperty("discardOnAirExposure", vein.discardOnAirExposure());
        pass.addProperty("regenerable", VeinPopulator.isRegenerableDefinition(vein));
        JsonArray biomes = new JsonArray();
        vein.biomes().stream().sorted().forEach(biomes::add);
        pass.add("biomes", biomes);
        return pass;
    }

    /** The ore a material belongs to: {@code deepslate_iron_ore} → {@code iron}. */
    static String family(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.startsWith("deepslate_")) {
            name = name.substring("deepslate_".length());
        }
        if (name.endsWith("_ore")) {
            name = name.substring(0, name.length() - "_ore".length());
        }
        return name;
    }

    private static String key(Material material) {
        return material.getKey().toString();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** The admin status: settings, retrofit counters and a running {@code regenerate all}. */
    static JsonObject status(StatusView view) {
        GeneratorSettings settings = view.settings();
        JsonObject root = new JsonObject();
        root.addProperty("passes", settings.veins().size());
        root.addProperty("replaceExisting", settings.replaceExistingOres());
        root.addProperty("retrofit", settings.retrofitExistingChunks());
        root.addProperty("sectionsPerTick", settings.retrofitSectionsPerTick());
        root.addProperty("retrofitVersion", settings.retrofitVersion());
        JsonArray worlds = new JsonArray();
        settings.enabledWorlds().stream().sorted().forEach(worlds::add);
        root.add("worlds", worlds);
        root.addProperty("legacyConfig", view.legacyConfig());
        root.addProperty("language", view.language());
        root.addProperty("processed", view.processed());
        root.addProperty("manual", view.manual());
        root.addProperty("queued", view.queued());
        root.addProperty("scanning", view.scanning());
        root.addProperty("maxRadius", AdminActions.MAX_RADIUS);
        if (view.chunksPerMinute() > 0.0) {
            root.addProperty("chunksPerMinute", Math.round(view.chunksPerMinute() * 10.0) / 10.0);
        } else {
            root.add("chunksPerMinute", JsonNull.INSTANCE);
        }
        JsonObject campaign = new JsonObject();
        campaign.addProperty("active", view.campaignActive());
        campaign.addProperty("done", view.campaignDone());
        campaign.addProperty("remaining", view.campaignRemaining());
        root.add("campaign", campaign);
        AdminActions.ScanReport scan = view.lastScan();
        if (scan == null) {
            root.add("lastScan", JsonNull.INSTANCE);
        } else {
            JsonObject last = new JsonObject();
            last.addProperty("worlds", scan.worlds());
            last.addProperty("found", scan.found());
            last.addProperty("queued", scan.queued());
            last.addProperty("failed", scan.failedRegionFiles());
            last.addProperty("at", scan.finishedAt());
            root.add("lastScan", last);
        }
        return root;
    }

    /** Feedback lines of an action as plain texts in {@code language}, for the web page. */
    static JsonObject outcome(AdminActions.Outcome outcome, Messages messages, String language) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", outcome.success());
        JsonArray lines = new JsonArray();
        for (AdminActions.Line line : outcome.lines()) {
            lines.add(messages.plain(language, line.key(), line.placeholders()));
        }
        root.add("messages", lines);
        return root;
    }
}
