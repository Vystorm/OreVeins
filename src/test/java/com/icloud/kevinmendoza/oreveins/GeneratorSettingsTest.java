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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GeneratorSettingsTest {
    static YamlConfiguration resource(String name) {
        YamlConfiguration yaml;
        try (var stream = GeneratorSettingsTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError(name + " is missing");
            }
            yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return yaml;
    }

    @Test
    void tallIrisPresetKeepsThe250Tuning() {
        GeneratorSettings settings = GeneratorSettings.load(
                resource(ConfigFiles.LEGACY_DEFAULTS_RESOURCE), Logger.getAnonymousLogger());
        assertEquals(28, settings.veins().size());
        assertTrue(settings.replaceExistingOres());
        assertTrue(settings.retrofitExistingChunks());
        assertEquals(1, settings.retrofitSectionsPerTick());
        assertEquals(2, settings.retrofitVersion());
        assertTrue(settings.enables("world", World.Environment.NORMAL));
        assertTrue(settings.enables("world_nether", World.Environment.NETHER));
        assertEquals(-256, settings.heightReferences().get(World.Environment.NETHER).minY());
        assertEquals(512, settings.heightReferences().get(World.Environment.NETHER).maxY());

        VeinDefinition lava = settings.veins().stream()
                .filter(vein -> vein.id().equals("deep-lava"))
                .findFirst().orElseThrow();
        assertEquals(576, lava.size());
        assertEquals(0.08, lava.attemptsPerChunk());
        assertEquals(1.0, lava.discardOnAirExposure());

        VeinDefinition magma = settings.veins().stream()
                .filter(vein -> vein.id().equals("deep-magma"))
                .findFirst().orElseThrow();
        assertEquals(576, magma.size());
        assertEquals(0.16, magma.attemptsPerChunk());

        VeinDefinition obsidian = settings.veins().stream()
                .filter(vein -> vein.id().equals("deep-obsidian"))
                .findFirst().orElseThrow();
        assertEquals(576, obsidian.size());
        assertEquals(0.24, obsidian.attemptsPerChunk());

        Map<String, Double> expectedOreAttempts = Map.ofEntries(
                Map.entry("coal-upper", 4.5),
                Map.entry("coal-lower", 3.0),
                Map.entry("iron-upper", 13.5),
                Map.entry("iron-middle", 1.5),
                Map.entry("iron-small", 1.5),
                Map.entry("copper", 2.4),
                Map.entry("copper-large-dripstone", 2.4),
                Map.entry("gold", 0.6),
                Map.entry("gold-lower", 0.075),
                Map.entry("gold-extra-badlands", 7.5),
                Map.entry("redstone", 0.6),
                Map.entry("redstone-lower", 1.2),
                Map.entry("diamond", 1.05),
                Map.entry("diamond-medium", 0.3),
                Map.entry("diamond-large", 0.15),
                Map.entry("diamond-buried", 0.6),
                Map.entry("lapis", 0.3),
                Map.entry("lapis-buried", 0.6),
                Map.entry("emerald-mountains", 15.0),
                Map.entry("ancient-debris-large", 0.3),
                Map.entry("ancient-debris-small", 0.3),
                Map.entry("nether-quartz", 4.8),
                Map.entry("nether-quartz-deltas", 9.6),
                Map.entry("nether-gold", 3.0),
                Map.entry("nether-gold-deltas", 6.0));
        Map<String, Double> actualOreAttempts = settings.veins().stream()
                .filter(VeinPopulator::isRegenerableDefinition)
                .filter(vein -> !vein.id().equals("deep-lava"))
                .collect(Collectors.toMap(VeinDefinition::id, VeinDefinition::attemptsPerChunk));
        assertEquals(expectedOreAttempts, actualOreAttempts);

        for (String id : new String[]{"ancient-debris-large", "ancient-debris-small"}) {
            VeinDefinition debris = settings.veins().stream()
                    .filter(vein -> vein.id().equals(id))
                    .findFirst().orElseThrow();
            assertEquals(-256, debris.minY());
            assertEquals(-128, debris.maxY());
        }

        VeinDefinition quartz = settings.veins().stream()
                .filter(vein -> vein.id().equals("nether-quartz"))
                .findFirst().orElseThrow();
        assertEquals(-246, quartz.minY());
        assertEquals(501, quartz.maxY());
    }

    @Test
    void tallIrisPresetEqualsThe250BundledConfig() {
        Logger logger = Logger.getAnonymousLogger();
        assertEquals(GeneratorSettings.load(resource("legacy-2.5.0-config.yml"), logger),
                GeneratorSettings.load(resource(ConfigFiles.LEGACY_DEFAULTS_RESOURCE), logger));
    }

    @Test
    void vanillaDefaultsFitVanillaWorlds() {
        GeneratorSettings settings = GeneratorSettings.load(
                resource(ConfigFiles.DEFAULT_RESOURCE), Logger.getAnonymousLogger());
        assertEquals(28, settings.veins().size());
        assertTrue(settings.replaceExistingOres());
        assertFalse(settings.retrofitExistingChunks());
        assertEquals(1, settings.retrofitVersion());
        assertEquals(new HeightReference(-64, 320), settings.heightReferences().get(World.Environment.NORMAL));
        assertEquals(new HeightReference(0, 256), settings.heightReferences().get(World.Environment.NETHER));
        for (VeinDefinition vein : settings.veins()) {
            HeightReference world = settings.heightReferences().get(vein.environment());
            assertTrue(vein.minY() >= world.minY() && vein.maxY() < world.maxY(),
                    vein.id() + " lies outside the vanilla build limits");
            for (String biome : vein.biomes()) {
                assertTrue(biome.startsWith("minecraft:"), vein.id() + " uses non-vanilla biome " + biome);
            }
        }
    }
}
