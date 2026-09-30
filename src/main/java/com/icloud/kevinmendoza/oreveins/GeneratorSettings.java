/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record GeneratorSettings(
        boolean replaceExistingOres,
        boolean retrofitExistingChunks,
        int retrofitSectionsPerTick,
        int retrofitVersion,
        Set<String> enabledWorlds,
        Map<World.Environment, HeightReference> heightReferences,
        List<VeinDefinition> veins
) {
    GeneratorSettings {
        enabledWorlds = Set.copyOf(enabledWorlds);
        heightReferences = Map.copyOf(heightReferences);
        veins = List.copyOf(veins);
    }

    static GeneratorSettings load(FileConfiguration config, Logger logger) {
        return load(config, logger, Messages.bundled("en"));
    }

    static GeneratorSettings load(FileConfiguration config, Logger logger, Messages messages) {
        boolean replace = config.getBoolean("settings.replace-existing-ores", true);
        boolean retrofit = config.getBoolean("settings.retrofit-existing-chunks", true);
        int retrofitPerTick = Math.max(1, Math.min(8,
                config.getInt("settings.retrofit-sections-per-tick", 1)));
        int retrofitVersion = Math.max(1, config.getInt("settings.retrofit-version", 1));
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("settings.enabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }

        Map<World.Environment, HeightReference> references = new EnumMap<>(World.Environment.class);
        ConfigurationSection referenceSection = config.getConfigurationSection("reference-heights");
        if (referenceSection != null) {
            for (String key : referenceSection.getKeys(false)) {
                try {
                    World.Environment environment = World.Environment.valueOf(key.toUpperCase(Locale.ROOT));
                    ConfigurationSection section = referenceSection.getConfigurationSection(key);
                    if (section != null) {
                        references.put(environment, new HeightReference(
                                section.getInt("min-y"), section.getInt("max-y")));
                    }
                } catch (IllegalArgumentException exception) {
                    logger.warning(messages.console("config.unknown-environment", "environment", key));
                }
            }
        }

        List<VeinDefinition> definitions = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("veins")) {
            try {
                VeinDefinition definition = parseDefinition(entry);
                if (!references.containsKey(definition.environment())) {
                    throw new IllegalArgumentException(messages.console("config.missing-reference",
                            "environment", definition.environment()));
                }
                definitions.add(definition);
            } catch (RuntimeException exception) {
                logger.severe(messages.console("config.invalid-vein",
                        "id", entry.get("id"), "error", exception.getMessage()));
            }
        }

        if (definitions.isEmpty()) {
            throw new IllegalArgumentException(messages.console("config.no-veins"));
        }
        return new GeneratorSettings(replace, retrofit, retrofitPerTick, retrofitVersion,
                worlds, references, definitions);
    }

    boolean enables(String worldName, World.Environment environment) {
        if (environment != World.Environment.NORMAL && environment != World.Environment.NETHER) {
            return false;
        }
        return enabledWorlds.isEmpty() || enabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static VeinDefinition parseDefinition(Map<?, ?> map) {
        String id = requiredString(map, "id");
        World.Environment environment = World.Environment.valueOf(
                requiredString(map, "environment").toUpperCase(Locale.ROOT));
        Material material = requiredMaterial(map, "material");
        Material deepslateMaterial = optionalMaterial(map, "deepslate-material");
        VeinDefinition.HostType host = VeinDefinition.HostType.valueOf(
                requiredString(map, "replace").toUpperCase(Locale.ROOT));
        double attempts = number(map, "attempts-per-chunk").doubleValue();
        double spawnChance = optionalNumber(map, "spawn-chance", 1.0).doubleValue();
        int size = number(map, "size").intValue();
        int minY = number(map, "min-y").intValue();
        int maxY = number(map, "max-y").intValue();
        VeinDefinition.Distribution distribution = VeinDefinition.Distribution.valueOf(
                optionalString(map, "distribution", "UNIFORM").toUpperCase(Locale.ROOT));
        double discard = optionalNumber(map, "discard-on-air-exposure", 0.0).doubleValue();

        if (attempts < 0.0 || spawnChance < 0.0 || spawnChance > 1.0 || size < 1 || maxY < minY
                || discard < 0.0 || discard > 1.0) {
            throw new IllegalArgumentException("numeric value is outside its allowed range");
        }

        Set<String> biomes = new HashSet<>();
        Object rawBiomes = map.get("biomes");
        if (rawBiomes instanceof List<?> list) {
            for (Object biome : list) {
                biomes.add(String.valueOf(biome).toLowerCase(Locale.ROOT));
            }
        }

        return new VeinDefinition(id, environment, material, deepslateMaterial, host, attempts,
                spawnChance, size, minY, maxY, distribution, discard, biomes);
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return String.valueOf(value);
    }

    private static String optionalString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Number number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing or non-numeric " + key);
        }
        return number;
    }

    private static Number optionalNumber(Map<?, ?> map, String key, Number fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number : fallback;
    }

    private static Material requiredMaterial(Map<?, ?> map, String key) {
        Material material = optionalMaterial(map, key);
        if (material == null) {
            throw new IllegalArgumentException("unknown block material in " + key + ": " + map.get(key));
        }
        return material;
    }

    private static Material optionalMaterial(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : Material.matchMaterial(String.valueOf(value));
    }
}
