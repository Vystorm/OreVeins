/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class VeinPopulatorTest {
    @Test
    void fractionalAttemptsHaveExpectedLongRunAverage() {
        Random random = new Random(12345L);
        int total = 0;
        for (int i = 0; i < 100_000; i++) {
            total += VeinPopulator.rollAttempts(random, 0.12);
        }
        assertTrue(total > 11_700 && total < 12_300, "actual total: " + total);
    }

    @Test
    void sampledYAlwaysStaysInsideInclusiveRange() {
        Random random = new Random(42L);
        for (VeinDefinition.Distribution distribution : VeinDefinition.Distribution.values()) {
            for (int i = 0; i < 10_000; i++) {
                int y = VeinPopulator.sampleY(random, -240, -64, distribution);
                assertTrue(y >= -240 && y <= -64);
            }
        }
        assertEquals(10, VeinPopulator.sampleY(random, 10, 10, VeinDefinition.Distribution.UNIFORM));
    }

    @Test
    void regenerationIncludesOnlyOresAndLava() {
        assertTrue(VeinPopulator.isRegenerableDefinition(definition(Material.DIAMOND_ORE)));
        assertTrue(VeinPopulator.isRegenerableDefinition(definition(Material.NETHER_QUARTZ_ORE)));
        assertTrue(VeinPopulator.isRegenerableDefinition(definition(Material.LAVA)));
        assertTrue(!VeinPopulator.isRegenerableDefinition(definition(Material.OBSIDIAN)));
        assertTrue(!VeinPopulator.isRegenerableDefinition(definition(Material.MAGMA_BLOCK)));
    }

    @Test
    void removedOreTakesTheDominantSurroundingBlock() {
        FakeRegion region = new FakeRegion(Material.TUFF);
        region.put(0, 1, 0, Material.STONE);
        region.put(1, 0, 0, Material.IRON_ORE);
        region.put(-1, 0, 0, Material.IRON_ORE);
        assertEquals(Material.TUFF,
                VeinPopulator.dominantNeighbour(region, 0, 0, 0, material -> !material.name().endsWith("_ORE")));
    }

    @Test
    void faceNeighboursOutweighCorners() {
        FakeRegion region = new FakeRegion(Material.GRANITE);
        for (int[] face : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            region.put(face[0], face[1], face[2], Material.SANDSTONE);
        }
        // 6 faces x3 = 18 votes vs. 12 edges x2 + 8 corners x1 = 32 votes.
        assertEquals(Material.GRANITE, VeinPopulator.dominantNeighbour(region, 0, 0, 0, material -> true));
        region.fallback = Material.AIR;
        assertEquals(Material.SANDSTONE,
                VeinPopulator.dominantNeighbour(region, 0, 0, 0, material -> material != Material.AIR));
    }

    @Test
    void noCandidateNeighbourYieldsNull() {
        FakeRegion region = new FakeRegion(Material.AIR);
        assertNull(VeinPopulator.dominantNeighbour(region, 0, 0, 0, material -> material != Material.AIR));
    }

    private static final class FakeRegion implements VeinPopulator.GenerationRegion {
        private final Map<String, Material> blocks = new HashMap<>();
        private Material fallback;

        FakeRegion(Material fallback) {
            this.fallback = fallback;
        }

        void put(int x, int y, int z, Material material) {
            blocks.put(x + "," + y + "," + z, material);
        }

        @Override
        public Material getType(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, fallback);
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            put(x, y, z, material);
        }

        @Override
        public boolean isInRegion(int x, int y, int z) {
            return true;
        }

        @Override
        public String getBiomeKey(int x, int y, int z) {
            return "minecraft:plains";
        }
    }

    private static VeinDefinition definition(Material material) {
        World.Environment environment = material == Material.NETHER_QUARTZ_ORE
                ? World.Environment.NETHER : World.Environment.NORMAL;
        VeinDefinition.HostType host = environment == World.Environment.NETHER
                ? VeinDefinition.HostType.NETHER_BASE : VeinDefinition.HostType.OVERWORLD_BASE;
        return new VeinDefinition("test", environment, material, null, host,
                1.0, 1.0, 1, 0, 0, VeinDefinition.Distribution.UNIFORM, 0.0, Set.of());
    }
}
