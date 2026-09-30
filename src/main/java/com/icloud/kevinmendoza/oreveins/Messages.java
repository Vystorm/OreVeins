/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Player- and admin-facing texts. English is the fallback for every missing
 * key; the setting "auto" picks German for players whose client locale starts
 * with "de" and English for everyone else, including the console.
 */
final class Messages {
    static final String DEFAULT_LANGUAGE = "en";
    static final List<String> BUNDLED_LANGUAGES = List.of("en", "de");

    private final Map<String, YamlConfiguration> bundles;
    private final String fixedLanguage;

    private Messages(Map<String, YamlConfiguration> bundles, String fixedLanguage) {
        this.bundles = bundles;
        this.fixedLanguage = fixedLanguage;
    }

    /**
     * Loads the bundled language files, letting files in {@code dataFolder/lang}
     * override single keys. Unknown settings fall back to "auto".
     */
    static Messages load(String setting, File dataFolder, Function<String, InputStream> resources,
            Logger logger) {
        Map<String, YamlConfiguration> bundles = new LinkedHashMap<>();
        for (String language : BUNDLED_LANGUAGES) {
            YamlConfiguration bundled = readResource(resources, "lang/" + language + ".yml");
            YamlConfiguration merged = bundled;
            File override = dataFolder == null ? null : new File(new File(dataFolder, "lang"), language + ".yml");
            if (override != null && override.isFile()) {
                merged = YamlConfiguration.loadConfiguration(override);
                merged.setDefaults(bundled);
            }
            bundles.put(language, merged);
        }
        String normalized = setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT);
        String fixed = null;
        if (!normalized.equals("auto")) {
            if (bundles.containsKey(normalized)) {
                fixed = normalized;
            } else if (logger != null) {
                logger.warning("Unknown language '" + setting + "'; using auto (available: auto, "
                        + String.join(", ", bundles.keySet()) + ").");
            }
        }
        return new Messages(bundles, fixed);
    }

    /** Bundled texts only, for tests and for messages before the config is read. */
    static Messages bundled(String setting) {
        return load(setting, null, name -> Messages.class.getClassLoader().getResourceAsStream(name), null);
    }

    String languageFor(CommandSender sender) {
        if (fixedLanguage != null) {
            return fixedLanguage;
        }
        if (sender instanceof Player player) {
            return languageForLocale(player.locale());
        }
        return DEFAULT_LANGUAGE;
    }

    String languageForLocale(Locale locale) {
        if (fixedLanguage != null) {
            return fixedLanguage;
        }
        String language = locale == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        return language.startsWith("de") ? "de" : DEFAULT_LANGUAGE;
    }

    /** Language used for console log lines: the fixed language, else English. */
    String consoleLanguage() {
        return fixedLanguage != null ? fixedLanguage : DEFAULT_LANGUAGE;
    }

    String get(CommandSender sender, String key, Object... placeholders) {
        return format(languageFor(sender), key, placeholders);
    }

    String console(String key, Object... placeholders) {
        return format(consoleLanguage(), key, placeholders);
    }

    void send(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(get(sender, key, placeholders));
    }

    /**
     * Resolves {@code key} in {@code language} (falling back to English) and
     * replaces {@code {name}} placeholders given as name/value pairs.
     */
    String format(String language, String key, Object... placeholders) {
        String text = raw(language, key);
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            text = text.replace("{" + placeholders[index] + "}", String.valueOf(placeholders[index + 1]));
        }
        return text.replace("{prefix}", raw(language, "prefix"));
    }

    String raw(String language, String key) {
        YamlConfiguration bundle = bundles.get(language);
        String text = bundle == null ? null : bundle.getString(key);
        if (text == null) {
            YamlConfiguration fallback = bundles.get(DEFAULT_LANGUAGE);
            text = fallback == null ? null : fallback.getString(key);
        }
        return text == null ? key : text;
    }

    YamlConfiguration bundle(String language) {
        return bundles.get(language);
    }

    private static YamlConfiguration readResource(Function<String, InputStream> resources, String name) {
        try (InputStream stream = resources.apply(name)) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            return new YamlConfiguration();
        }
    }
}
