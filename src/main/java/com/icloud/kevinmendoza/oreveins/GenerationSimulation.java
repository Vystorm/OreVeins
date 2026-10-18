/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;

/** Isolated preview of the real vein algorithm against explicitly synthetic terrain. */
final class GenerationSimulation {
    static final int MAX_SEEDS = 16;
    static final int MAX_CHUNKS = 4;
    static final long MAX_MODELED_BLOCK_ATTEMPTS = 120_000L;
    private static final String LABEL = "SIMULATION — synthetic host blocks and biome fixture; not a world scan";
    private static final String LIMITATIONS = "Counts are aggregate placed blocks per material in this synthetic fixture. "
            + "Connected sizes use six-face adjacency per material within each synthetic chunk: boundaries truncate clusters and neighboring veins may merge. "
            + "Results are not predictions or confidence estimates for real worlds.";

    private GenerationSimulation() {
    }

    static Report simulate(GeneratorSettings settings, World.Environment environment,
            int worldMinY, int worldMaxY, List<Long> seeds, int requestedChunks) {
        if (settings == null || environment == null || seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("settings, environment, and at least one seed are required");
        }
        if (worldMaxY <= worldMinY) {
            throw new IllegalArgumentException("world max Y must be greater than min Y");
        }
        int chunkCount = Math.max(1, Math.min(MAX_CHUNKS, requestedChunks));
        List<Long> boundedSeeds = List.copyOf(seeds.subList(0, Math.min(MAX_SEEDS, seeds.size())));
        long modeledAttempts = modeledBlockAttempts(settings, environment, boundedSeeds.size(), chunkCount);
        if (modeledAttempts > MAX_MODELED_BLOCK_ATTEMPTS) {
            throw new IllegalArgumentException("simulation work budget exceeded: " + modeledAttempts);
        }
        HeightReference reference = settings.heightReferences().get(environment);
        if (reference == null) {
            throw new IllegalArgumentException("no height reference configured for " + environment);
        }

        Map<Material, List<Integer>> connected = new HashMap<>();
        List<SeedResult> seedResults = new ArrayList<>(boundedSeeds.size());
        for (long seed : boundedSeeds) {
            Map<Material, Long> totals = new HashMap<>();
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                SyntheticRegion region = new SyntheticRegion(environment, worldMinY, worldMaxY, chunk);
                Random random = new Random(mixSeed(seed, chunk));
                VeinPopulator.generateDefinitions(settings, environment, reference, region, random,
                        chunk, 0, worldMinY, worldMaxY, false);
                region.writes.forEach((material, count) -> totals.merge(material, count, Long::sum));
                connectedSizes(region.placed).forEach((material, sizes) -> connected.computeIfAbsent(material, ignored -> new ArrayList<>()).addAll(sizes));
            }
            seedResults.add(new SeedResult(seed, Map.copyOf(totals)));
        }
        Set<Material> configuredMaterials = new HashSet<>();
        for (VeinDefinition vein : settings.veins()) {
            if (vein.environment() != environment) continue;
            configuredMaterials.add(vein.material());
            if (vein.deepslateMaterial() != null) configuredMaterials.add(vein.deepslateMaterial());
        }
        return new Report(LABEL, LIMITATIONS, boundedSeeds.size(), chunkCount, List.copyOf(seedResults),
                summarizeMaterials(seedResults, configuredMaterials, chunkCount), summarizeSizes(connected));
    }

    /** Six-face components in the final synthetic block map, never calls Bukkit or loads a world. */
    static Map<Material, List<Integer>> connectedSizes(Map<Position, Material> placed) {
        Set<Position> remaining = new HashSet<>(placed.keySet());
        Map<Material, List<Integer>> result = new HashMap<>();
        java.util.ArrayDeque<Position> queue = new java.util.ArrayDeque<>();
        int[][] steps = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (Position start : placed.keySet()) {
            if (!remaining.remove(start)) continue;
            queue.add(start);
            Material material = placed.get(start); int size = 0;
            while (!queue.isEmpty()) {
                Position current = queue.remove(); size++;
                for (int[] step : steps) {
                    Position neighbor = new Position(current.x + step[0], current.y + step[1], current.z + step[2]);
                    if (placed.get(neighbor) == material && remaining.remove(neighbor)) queue.add(neighbor);
                }
            }
            result.computeIfAbsent(material, ignored -> new ArrayList<>()).add(size);
        }
        result.replaceAll((material, sizes) -> List.copyOf(sizes)); return Map.copyOf(result);
    }

    static List<VeinSizeDistribution> summarizeSizes(Map<Material, List<Integer>> connected) {
        List<VeinSizeDistribution> result = new ArrayList<>();
        connected.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Material::name))).forEach(entry -> {
            List<Integer> sizes = entry.getValue().stream().sorted().toList(); if (sizes.isEmpty()) return;
            int n = sizes.size(); double median = n % 2 == 1 ? sizes.get(n / 2) : (sizes.get(n / 2 - 1) + (double) sizes.get(n / 2)) / 2;
            result.add(new VeinSizeDistribution(entry.getKey(), n, sizes.getFirst(), sizes.getLast(), sizes.stream().mapToInt(Integer::intValue).average().orElse(0), median));
        });
        return List.copyOf(result);
    }

    /** Summarizes aggregate generated material totals as per-seed per-chunk averages. */
    static List<MaterialDistribution> summarizeMaterials(List<SeedResult> samples,
            Set<Material> configuredMaterials, int chunksPerSeed) {
        if (samples.isEmpty()) throw new IllegalArgumentException("at least one seed sample is required");
        if (chunksPerSeed < 1) throw new IllegalArgumentException("chunksPerSeed must be positive");
        List<Material> materials = configuredMaterials.stream().sorted(Comparator.comparing(Material::name)).toList();
        List<MaterialDistribution> summaries = new ArrayList<>(materials.size());
        for (Material material : materials) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            double[] values = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                double perChunk = samples.get(i).placedByMaterial().getOrDefault(material, 0L) / (double) chunksPerSeed;
                values[i] = perChunk;
                min = Math.min(min, perChunk);
                max = Math.max(max, perChunk);
                sum += perChunk;
            }
            double mean = sum / values.length;
            double squaredDifferenceSum = 0.0;
            for (double value : values) {
                double difference = value - mean;
                squaredDifferenceSum += difference * difference;
            }
            double populationStdDev = Math.sqrt(squaredDifferenceSum / values.length);
            summaries.add(new MaterialDistribution(material, values.length, chunksPerSeed,
                    min, max, mean, populationStdDev));
        }
        return List.copyOf(summaries);
    }

    /** Conservative candidate-placement upper bound before any simulation is run. */
    static long modeledBlockAttempts(GeneratorSettings settings, World.Environment environment, int seeds, int chunks) {
        long perChunk = 0;
        for (VeinDefinition vein : settings.veins()) {
            if (vein.environment() != environment) continue;
            long attempts = (long) Math.floor(vein.attemptsPerChunk())
                    + (vein.attemptsPerChunk() > Math.floor(vein.attemptsPerChunk()) ? 1L : 0L);
            // Each successful extension can lower the failure counter by two,
            // adding at most three loop iterations per placed block to the limit.
            long candidatesPerVein = Math.max(64L, (long) vein.size() * 30L)
                    + (long) vein.size() * 3L + 33L;
            perChunk = saturatingAdd(perChunk, saturatingMultiply(attempts, candidatesPerVein));
        }
        return saturatingMultiply(perChunk, saturatingMultiply(seeds, chunks));
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long mixSeed(long seed, int chunk) {
        return seed + 0x9E3779B97F4A7C15L * chunk;
    }

    record Report(String label, String limitations, int seedsSimulated, int chunksPerSeed,
                  List<SeedResult> results, List<MaterialDistribution> materialDistributions, List<VeinSizeDistribution> connectedVeinSizes) {
        Report {
            results = List.copyOf(results);
            materialDistributions = List.copyOf(materialDistributions);
            connectedVeinSizes = List.copyOf(connectedVeinSizes);
        }
    }

    /** Population statistics over seeds; each seed contributes its aggregate count divided by chunksPerSeed. */
    record MaterialDistribution(Material material, int sampleSizeSeeds, int chunksPerSeed,
                                double minPerChunk, double maxPerChunk, double meanPerChunk,
                                double populationStdDevPerChunk) {}

    record VeinSizeDistribution(Material material, int components, int min, int max, double mean, double median) {}

    record SeedResult(long seed, Map<Material, Long> placedByMaterial) {
        SeedResult {
            placedByMaterial = Map.copyOf(placedByMaterial);
        }
    }

    private static final class SyntheticRegion implements VeinPopulator.GenerationRegion {
        private final World.Environment environment;
        private final int minY;
        private final int maxY;
        private final int chunkX;
        private final Map<Position, Material> placed = new HashMap<>();
        private final Map<Material, Long> writes = new HashMap<>();

        private SyntheticRegion(World.Environment environment, int minY, int maxY, int chunkX) {
            this.environment = environment;
            this.minY = minY;
            this.maxY = maxY;
            this.chunkX = chunkX;
        }

        @Override
        public Material getType(int x, int y, int z) {
            return placed.getOrDefault(new Position(x, y, z), syntheticHost(y));
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            Position position = new Position(x, y, z);
            Material previous = getType(x, y, z);
            if (previous != material) {
                placed.put(position, material);
                writes.merge(material, 1L, Long::sum);
            }
        }

        @Override
        public boolean isInRegion(int x, int y, int z) {
            return (x >> 4) == chunkX && (z >> 4) == 0 && y >= minY && y < maxY;
        }

        @Override
        public String getBiomeKey(int x, int y, int z) {
            return "minecraft:plains";
        }

        private Material syntheticHost(int y) {
            if (environment == World.Environment.NETHER) {
                return Material.NETHERRACK;
            }
            return y < 0 ? Material.DEEPSLATE : Material.STONE;
        }
    }

    record Position(int x, int y, int z) {
    }
}
