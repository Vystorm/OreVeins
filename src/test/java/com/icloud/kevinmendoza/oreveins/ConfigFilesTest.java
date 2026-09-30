/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configs written by 2.5.0 or older must behave exactly as with the 2.5.0 jar,
 * which used its bundled config.yml (now the test fixture) as Bukkit defaults.
 */
class ConfigFilesTest {
    private static final Function<String, InputStream> RESOURCES =
            name -> ConfigFilesTest.class.getClassLoader().getResourceAsStream(name);
    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @TempDir
    Path directory;

    private static final String IRIS_HEIGHTS = "reference-heights:\n  NORMAL:\n    min-y: -256\n"
            + "    max-y: 512\n  NETHER:\n    min-y: -256\n    max-y: 512\n";

    private static final String[] LEGACY_FILES = {
            // Unchanged 2.5.0 file.
            null,
            // Empty or partial files rely on the old bundled defaults.
            "",
            "settings:\n  retrofit-version: 3\n",
            "settings:\n  enabled-worlds: [world]\n  replace-existing-ores: false\n",
            "reference-heights:\n  NORMAL:\n    min-y: -64\n    max-y: 320\n",
            "reference-heights:\n  NORMAL:\n    max-y: 320\n  NETHER:\n    min-y: 0\n",
            "veins:\n  - id: only-coal\n    environment: NORMAL\n    material: COAL_ORE\n"
                    + "    replace: OVERWORLD_BASE\n    attempts-per-chunk: 1\n    size: 5\n"
                    + "    min-y: 0\n    max-y: 64\n",
            // Same partial files, but with the reference heights present.
            IRIS_HEIGHTS + "settings:\n  retrofit-version: 3\n",
            IRIS_HEIGHTS + "settings:\n  enabled-worlds: [world]\n  retrofit-existing-chunks: false\n",
            IRIS_HEIGHTS + "veins:\n  - id: only-coal\n    environment: NORMAL\n    material: COAL_ORE\n"
                    + "    replace: OVERWORLD_BASE\n    attempts-per-chunk: 1\n    size: 5\n"
                    + "    min-y: 0\n    max-y: 64\n",
    };

    @Test
    void legacyConfigsBehaveLikeThe250Jar() throws Exception {
        YamlConfiguration oldBundled = GeneratorSettingsTest.resource("legacy-2.5.0-config.yml");
        int started = 0;
        for (String content : LEGACY_FILES) {
            Path file = directory.resolve("config.yml");
            Files.writeString(file, content == null ? oldBundled.saveToString() : content);

            // What JavaPlugin.getConfig() did in 2.5.0.
            YamlConfiguration old = YamlConfiguration.loadConfiguration(file.toFile());
            old.setDefaults(oldBundled);
            Object expected = outcome(old);

            ConfigFiles.Loaded loaded = ConfigFiles.load(file.toFile(), RESOURCES);
            assertTrue(loaded.legacy(), "file without config-version must be legacy: " + content);
            assertEquals(expected, outcome(loaded.config()), String.valueOf(content));
            if (expected instanceof GeneratorSettings) {
                started++;
            }
        }
        assertEquals(6, started);
    }

    /** The loaded settings, or the error that stopped the plugin from starting. */
    private static Object outcome(YamlConfiguration config) {
        try {
            return GeneratorSettings.load(config, LOGGER);
        } catch (RuntimeException exception) {
            return exception.getClass().getName() + ": " + exception.getMessage();
        }
    }

    @Test
    void newConfigsUseVanillaDefaults() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "config-version: 1\nsettings:\n  retrofit-version: 4\n"
                + "reference-heights:\n  NORMAL:\n    min-y: -64\n    max-y: 320\n"
                + "  NETHER:\n    min-y: 0\n    max-y: 256\n");
        ConfigFiles.Loaded loaded = ConfigFiles.load(file.toFile(), RESOURCES);
        assertFalse(loaded.legacy());
        GeneratorSettings settings = GeneratorSettings.load(loaded.config(), LOGGER);
        assertEquals(4, settings.retrofitVersion());
        assertEquals(GeneratorSettings.load(GeneratorSettingsTest.resource("config.yml"), LOGGER).veins(),
                settings.veins());
        assertEquals("auto", loaded.config().getString("language"));
    }

    @Test
    void bundledDefaultConfigIsNotLegacy() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, GeneratorSettingsTest.resource("config.yml").saveToString());
        assertFalse(ConfigFiles.load(file.toFile(), RESOURCES).legacy());
    }
}
