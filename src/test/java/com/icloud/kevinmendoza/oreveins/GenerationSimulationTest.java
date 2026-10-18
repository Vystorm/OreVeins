/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class GenerationSimulationTest {
    @Test
    void isDeterministicAndClearlyIdentifiesItsSyntheticFixture() {
        GeneratorSettings settings = settings();
        GenerationSimulation.Report first = GenerationSimulation.simulate(settings, World.Environment.NORMAL,
                -64, 320, List.of(77L), 1);
        GenerationSimulation.Report second = GenerationSimulation.simulate(settings, World.Environment.NORMAL,
                -64, 320, List.of(77L), 1);

        assertTrue(first.label().toLowerCase().contains("synthetic"));
        assertEquals(first, second);
        assertTrue(first.limitations().contains("boundaries truncate clusters"));
        assertFalse(first.connectedVeinSizes().isEmpty());
        assertTrue(first.materialDistributions().stream().allMatch(stat -> stat.sampleSizeSeeds() == 1));
        assertTrue(first.materialDistributions().stream().allMatch(stat -> stat.chunksPerSeed() == 1));
        assertTrue(first.results().getFirst().placedByMaterial().getOrDefault(Material.DIAMOND_ORE, 0L) > 0);
        assertTrue(GenerationStatistics.snapshot().isEmpty(), "simulation must not affect live counters");
    }

    @Test
    void distributionsTreatMissingMaterialAsZeroAndUsePopulationDeviationPerChunk() {
        var samples = List.of(
                new GenerationSimulation.SeedResult(1L, Map.of(Material.DIAMOND_ORE, 4L)),
                new GenerationSimulation.SeedResult(2L, Map.of()));
        var stats = GenerationSimulation.summarizeMaterials(samples,
                Set.of(Material.DIAMOND_ORE, Material.EMERALD_ORE), 2);

        var diamond = stats.stream().filter(stat -> stat.material() == Material.DIAMOND_ORE).findFirst().orElseThrow();
        assertEquals(2, diamond.sampleSizeSeeds());
        assertEquals(2, diamond.chunksPerSeed());
        assertEquals(0.0, diamond.minPerChunk(), 1.0e-9);
        assertEquals(2.0, diamond.maxPerChunk(), 1.0e-9);
        assertEquals(1.0, diamond.meanPerChunk(), 1.0e-9);
        assertEquals(1.0, diamond.populationStdDevPerChunk(), 1.0e-9);

        var absent = stats.stream().filter(stat -> stat.material() == Material.EMERALD_ORE).findFirst().orElseThrow();
        assertEquals(0.0, absent.minPerChunk(), 1.0e-9);
        assertEquals(0.0, absent.maxPerChunk(), 1.0e-9);
        assertEquals(0.0, absent.meanPerChunk(), 1.0e-9);
        assertEquals(0.0, absent.populationStdDevPerChunk(), 1.0e-9);
    }

    @Test
    void capsSeedsAndChunksAndKeepsResultsLabeled() {
        List<Long> seeds = java.util.stream.LongStream.range(0, 20).boxed().toList();
        GenerationSimulation.Report report = GenerationSimulation.simulate(settings(0.0, 1), World.Environment.NORMAL,
                -64, 320, seeds, 10);
        assertEquals(GenerationSimulation.MAX_SEEDS, report.seedsSimulated());
        assertEquals(GenerationSimulation.MAX_CHUNKS, report.chunksPerSeed());
        assertEquals(GenerationSimulation.MAX_SEEDS, report.results().size());
        assertFalse(report.label().isBlank());
    }

    @Test
    void rejectsSettingsWhoseConservativeWorkEstimateExceedsBudget() {
        GeneratorSettings expensive = settings(1_000.0, 576);
        assertThrows(IllegalArgumentException.class, () -> GenerationSimulation.simulate(expensive,
                World.Environment.NORMAL, -64, 320, List.of(1L), 1));
    }

    @Test
    void bundledDefaultSingleChunkPreviewFitsTheBudget() throws Exception {
        GeneratorSettings defaults = GeneratorSettings.load(
                ConfigFiles.resource(name -> GenerationSimulationTest.class.getClassLoader().getResourceAsStream(name),
                        ConfigFiles.DEFAULT_RESOURCE), java.util.logging.Logger.getAnonymousLogger());
        long modeled = GenerationSimulation.modeledBlockAttempts(defaults, World.Environment.NORMAL, 1, 1);
        assertTrue(modeled <= GenerationSimulation.MAX_MODELED_BLOCK_ATTEMPTS, "modeled attempts: " + modeled);
    }

    @Test
    void nearBudgetSyntheticRunCompletesWithinTheBoundedSample() {
        GeneratorSettings nearBudget = settings(1.0, 3_000);
        assertTrue(GenerationSimulation.modeledBlockAttempts(nearBudget, World.Environment.NORMAL, 1, 1)
                <= GenerationSimulation.MAX_MODELED_BLOCK_ATTEMPTS);
        GenerationSimulation.Report report = GenerationSimulation.simulate(nearBudget, World.Environment.NORMAL,
                -64, 320, List.of(0L), 1);
        assertEquals(1, report.seedsSimulated());
    }

    private static GeneratorSettings settings() {
        return settings(8.0, 12);
    }

    private static GeneratorSettings settings(double attempts, int size) {
        VeinDefinition diamond = new VeinDefinition("diamond", World.Environment.NORMAL,
                Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
                VeinDefinition.HostType.OVERWORLD_BASE, attempts, 1.0, size,
                -60, 40, VeinDefinition.Distribution.TRIANGLE, 0.0, Set.of());
        Map<World.Environment, HeightReference> references = new EnumMap<>(World.Environment.class);
        references.put(World.Environment.NORMAL, new HeightReference(-64, 320));
        return new GeneratorSettings(false, false, 1, 1, Set.of(), references, List.of(diamond));
    }
}
