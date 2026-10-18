/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.lang.Lang;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Optional: makes config.yml editable in Vystorm Core's admin config editor (Core 0.27.0+, capability
 * {@code config-editor}). Started from {@link VystormWebFactory}; on an older Core nothing happens. A legacy config
 * (no {@code config-version}, missing keys come from the tall Iris preset) is not registered, because Core would add
 * the vanilla defaults to it.
 */
final class VystormConfigEditor {
    /** A labelled key with its lang keys ({@code config-editor.<name>}); {@code min} is NaN without a slider range. */
    record Entry(String path, String labelKey, String descriptionKey, double min, double max, double step) {
        boolean ranged() { return !Double.isNaN(min); }
    }

    static final List<Entry> ENTRIES = List.of(
            label("language", "language"),
            label("settings.enabled-worlds", "enabled-worlds"),
            label("settings.replace-existing-ores", "replace-existing-ores"),
            label("settings.retrofit-existing-chunks", "retrofit-existing-chunks"),
            range("settings.retrofit-sections-per-tick", "retrofit-sections-per-tick", 1, 8, 1),
            label("settings.retrofit-version", "retrofit-version"),
            label("settings.maintenance.enabled", "maintenance-enabled"),
            label("settings.maintenance.start-utc", "maintenance-start"),
            label("settings.maintenance.end-utc", "maintenance-end"),
            range("settings.maintenance.max-chunks-per-minute", "maintenance-max-chunks", 0, 10_000, 1),
            range("settings.maintenance.max-work-millis-per-tick", "maintenance-max-millis", 1, 20, 1));

    private VystormConfigEditor() {
    }

    private static Entry range(String path, String key, double min, double max, double step) {
        return new Entry(path, "config-editor." + key, "config-editor." + key + "-desc", min, max, step);
    }

    private static Entry label(String path, String key) {
        return range(path, key, Double.NaN, Double.NaN, Double.NaN);
    }

    /** Never throws; returns whether config.yml was registered. Server thread. */
    static boolean register(OreVeinsPlugin plugin, AdminActions actions) {
        try {
            if (!CoreApi.has(CoreApi.Capability.CONFIG_EDITOR)) return false;
            if (plugin.legacyConfig()) {
                plugin.getLogger().info("config.yml has no config-version (legacy 2.5.0 file); not shown in Vystorm Core's config editor");
                return false;
            }
            Lang lang = CoreApi.api().orElseThrow().lang().register(plugin);
            ConfigEditor.register(plugin, "config.yml", () -> {
                AdminActions.Outcome outcome = actions.reload();
                if (!outcome.success()) {
                    StringBuilder text = new StringBuilder();
                    for (AdminActions.Line line : outcome.lines()) {
                        text.append(plugin.messages().console(line.key(), line.placeholders())).append(' ');
                    }
                    throw new IllegalStateException(text.toString().trim());
                }
            });
            for (Entry entry : ENTRIES) {
                ConfigEditor.label(plugin, entry.path(), lang.ref(entry.labelKey()), lang.ref(entry.descriptionKey()));
                if (entry.ranged()) ConfigEditor.range(plugin, entry.path(), entry.min(), entry.max(), entry.step());
            }
            LinkedHashMap<String, String> languages = new LinkedHashMap<>();
            languages.put("auto", lang.ref("config-editor.language-auto"));
            languages.put("de", "Deutsch");
            languages.put("en", "English");
            ConfigEditor.choices(plugin, "language", languages);
            ConfigEditor.hidden(plugin, "config-version");
            return true;
        } catch (LinkageError | RuntimeException failure) {
            plugin.getLogger().warning("Vystorm Core config editor not available: " + failure);
            return false;
        }
    }
}
