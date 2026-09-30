/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads plugins/OreVeins/config.yml and attaches the matching bundled defaults.
 *
 * <p>Up to 2.5.0 the bundled config.yml was the tall Iris preset and Bukkit used
 * it as the default for every key missing from the file on disk. Such files
 * have no {@code config-version} key. They keep getting exactly that preset as
 * their defaults, so an existing installation behaves as before; only files
 * written by 2.6.0 or later fall back to the vanilla defaults.
 */
final class ConfigFiles {
    static final String DEFAULT_RESOURCE = "config.yml";
    static final String LEGACY_DEFAULTS_RESOURCE = "presets/iris-tall-world.yml";
    static final String VERSION_KEY = "config-version";

    private ConfigFiles() {
    }

    record Loaded(YamlConfiguration config, boolean legacy) {
    }

    static Loaded load(File file, Function<String, InputStream> resources)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return withDefaults(config, resources);
    }

    static Loaded withDefaults(YamlConfiguration config, Function<String, InputStream> resources)
            throws IOException, InvalidConfigurationException {
        boolean legacy = !config.contains(VERSION_KEY);
        config.setDefaults(resource(resources, legacy ? LEGACY_DEFAULTS_RESOURCE : DEFAULT_RESOURCE));
        return new Loaded(config, legacy);
    }

    static YamlConfiguration resource(Function<String, InputStream> resources, String name)
            throws IOException, InvalidConfigurationException {
        try (InputStream stream = resources.apply(name)) {
            if (stream == null) {
                throw new IOException("bundled " + name + " is missing");
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
