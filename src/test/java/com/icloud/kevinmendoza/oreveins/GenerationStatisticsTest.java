/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GenerationStatisticsTest {
    @AfterEach
    void clearCounters() {
        GenerationStatistics.clear();
    }

    @Test
    void countsPerWorldAndMaterialAndReturnsImmutableSnapshot() {
        GenerationStatistics.record("world", Material.DIAMOND_ORE);
        GenerationStatistics.record("world", Material.DIAMOND_ORE);
        GenerationStatistics.record("world_nether", Material.DIAMOND_ORE);

        Map<GenerationStatistics.Key, Long> snapshot = GenerationStatistics.snapshot();
        assertEquals(2L, snapshot.get(new GenerationStatistics.Key("world", Material.DIAMOND_ORE)));
        assertEquals(1L, snapshot.get(new GenerationStatistics.Key("world_nether", Material.DIAMOND_ORE)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(new GenerationStatistics.Key("other", Material.STONE), 1L));
    }

    @Test
    void capsTrackedWorldNames() {
        for (int world = 0; world < 129; world++) {
            GenerationStatistics.record("world" + world, Material.COAL_ORE);
        }
        assertEquals(128, GenerationStatistics.snapshot().size());
    }
}
