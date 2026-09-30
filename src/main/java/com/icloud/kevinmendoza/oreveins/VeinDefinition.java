/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;

record VeinDefinition(
        String id,
        World.Environment environment,
        Material material,
        Material deepslateMaterial,
        HostType hostType,
        double attemptsPerChunk,
        double spawnChance,
        int size,
        int minY,
        int maxY,
        Distribution distribution,
        double discardOnAirExposure,
        Set<String> biomes
) {
    enum Distribution {
        UNIFORM,
        TRIANGLE
    }

    enum HostType {
        OVERWORLD_BASE,
        NETHER_BASE,
        NETHERRACK
    }

    VeinDefinition {
        biomes = Set.copyOf(biomes);
    }
}
