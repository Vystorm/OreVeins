/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.data.Levelled;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

final class VeinPopulator extends BlockPopulator {
    private static final Set<Material> OVERWORLD_HOSTS = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE);
    private static final Set<Material> NETHER_HOSTS = EnumSet.of(
            Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE);
    private static final Set<Material> OVERWORLD_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
    private static final Set<Material> NETHER_ORES = EnumSet.of(
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS);
    // Blocks that must never be copied into a removed ore: valuables an X-ray
    // client would still highlight, and blocks players cannot mine.
    private static final Set<Material> NEVER_FILL = EnumSet.of(
            Material.RAW_IRON_BLOCK, Material.RAW_COPPER_BLOCK, Material.RAW_GOLD_BLOCK,
            Material.BUDDING_AMETHYST, Material.SPAWNER, Material.BEDROCK, Material.BARRIER,
            Material.REINFORCED_DEEPSLATE, Material.END_PORTAL_FRAME);
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1}
    };

    private final Supplier<GeneratorSettings> settingsSupplier;

    VeinPopulator(Supplier<GeneratorSettings> settingsSupplier) {
        this.settingsSupplier = settingsSupplier;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
            @NotNull LimitedRegion region) {
        GeneratorSettings settings = settingsSupplier.get();
        World.Environment environment = worldInfo.getEnvironment();
        if (settings == null || !settings.enables(worldInfo.getName(), environment)) {
            return;
        }

        GenerationRegion generationRegion = new LimitedGenerationRegion(region);
        if (settings.replaceExistingOres()) {
            removeExistingOres(generationRegion, environment, chunkX, chunkZ,
                    worldInfo.getMinHeight(), worldInfo.getMaxHeight());
        }

        HeightReference reference = settings.heightReferences().get(environment);
        if (reference == null) {
            return;
        }
        generateDefinitions(settings, environment, reference, generationRegion, random,
                chunkX, chunkZ, worldInfo.getMinHeight(), worldInfo.getMaxHeight(), false);
    }

    static void removeExistingOres(World world, Chunk chunk, int minY, int maxY) {
        World.Environment environment = world.getEnvironment();
        GenerationRegion generationRegion = new ChunkGenerationRegion(world, chunk);
        removeExistingOres(generationRegion, environment, chunk.getX(), chunk.getZ(), minY, maxY);
    }

    static void regenerateExisting(World world, Chunk chunk, Random random, GeneratorSettings settings) {
        World.Environment environment = world.getEnvironment();
        GenerationRegion generationRegion = new ChunkGenerationRegion(world, chunk);
        HeightReference reference = settings.heightReferences().get(environment);
        if (reference != null) {
            generateDefinitions(settings, environment, reference, generationRegion, random,
                    chunk.getX(), chunk.getZ(), world.getMinHeight(), world.getMaxHeight(), true);
        }
    }

    static void removeRegenerableBlocks(World world, Chunk chunk, int minY, int maxY,
            GeneratorSettings settings) {
        World.Environment environment = world.getEnvironment();
        Set<Material> ores = environment == World.Environment.NETHER ? NETHER_ORES : OVERWORLD_ORES;
        GenerationRegion region = new ChunkGenerationRegion(world, chunk);
        int boundedMin = Math.max(world.getMinHeight(), minY);
        int boundedMax = Math.min(world.getMaxHeight(), maxY);
        int startX = chunk.getX() << 4;
        int startZ = chunk.getZ() << 4;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int y = boundedMin; y < boundedMax; y++) {
                    Block block = chunk.getBlock(localX, y, localZ);
                    Material current = block.getType();
                    if (ores.contains(current)
                            || (current == Material.LAVA && isConfiguredSourceLava(block, settings))) {
                        block.setType(replacementForRemovedBlock(region, startX + localX, y, startZ + localZ,
                                current, environment), false);
                    }
                }
            }
        }
    }

    private static void generateDefinitions(GeneratorSettings settings, World.Environment environment,
            HeightReference reference, GenerationRegion region, Random random,
            int chunkX, int chunkZ, int worldMinY, int worldMaxY, boolean regenerationOnly) {
        for (VeinDefinition vein : settings.veins()) {
            if (vein.environment() != environment
                    || (regenerationOnly && !isRegenerableDefinition(vein))) {
                continue;
            }
            int attempts = rollAttempts(random, vein.attemptsPerChunk());
            for (int attempt = 0; attempt < attempts; attempt++) {
                if (random.nextDouble() <= vein.spawnChance()) {
                    generateVein(region, random, chunkX, chunkZ, reference, vein, worldMinY, worldMaxY);
                }
            }
        }
    }

    static boolean isRegenerableDefinition(VeinDefinition vein) {
        return vein.material() == Material.LAVA
                || OVERWORLD_ORES.contains(vein.material())
                || NETHER_ORES.contains(vein.material());
    }

    private static boolean isConfiguredSourceLava(Block block, GeneratorSettings settings) {
        if (!(block.getBlockData() instanceof Levelled levelled) || levelled.getLevel() != 0) {
            return false;
        }
        World world = block.getWorld();
        HeightReference reference = settings.heightReferences().get(world.getEnvironment());
        if (reference == null) {
            return false;
        }
        int y = block.getY();
        for (VeinDefinition vein : settings.veins()) {
            if (vein.environment() != world.getEnvironment() || vein.material() != Material.LAVA) {
                continue;
            }
            int minimum = scaledMinY(reference, vein, world.getMinHeight(), world.getMaxHeight());
            int maximum = scaledMaxY(reference, vein, world.getMinHeight(), world.getMaxHeight());
            if (y >= minimum && y <= maximum) {
                return true;
            }
        }
        return false;
    }

    static int rollAttempts(Random random, double attempts) {
        int whole = (int) Math.floor(attempts);
        return whole + (random.nextDouble() < attempts - whole ? 1 : 0);
    }

    private static void removeExistingOres(GenerationRegion region, World.Environment environment,
            int chunkX, int chunkZ, int minY, int maxY) {
        Set<Material> ores = environment == World.Environment.NETHER ? NETHER_ORES : OVERWORLD_ORES;
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Material current = region.getType(x, y, z);
                    if (ores.contains(current)) {
                        region.setType(x, y, z, replacementForRemovedBlock(region, x, y, z, current, environment));
                    }
                }
            }
        }
    }

    /**
     * Fills a removed ore (or lava source) with the block that dominates its
     * 3x3x3 neighbourhood, so the hole blends into tuff, granite, sandstone or
     * whatever Iris placed there. A fixed stone/deepslate fill would leave
     * ore-shaped blobs that X-ray texture packs reveal.
     */
    private static Material replacementForRemovedBlock(GenerationRegion region, int x, int y, int z,
            Material removed, World.Environment environment) {
        Material fill = dominantNeighbour(region, x, y, z, VeinPopulator::isFillCandidate);
        return fill != null ? fill : fallbackFill(removed, environment, y);
    }

    static Material dominantNeighbour(GenerationRegion region, int x, int y, int z,
            Predicate<Material> candidate) {
        Map<Material, Integer> votes = new EnumMap<>(Material.class);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (distance == 0 || !region.isInRegion(x + dx, y + dy, z + dz)) {
                        continue;
                    }
                    Material neighbour = region.getType(x + dx, y + dy, z + dz);
                    if (candidate.test(neighbour)) {
                        // Face neighbours count most, corners least.
                        votes.merge(neighbour, 4 - distance, Integer::sum);
                    }
                }
            }
        }
        Material best = null;
        int bestVotes = 0;
        for (Map.Entry<Material, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > bestVotes) {
                best = entry.getKey();
                bestVotes = entry.getValue();
            }
        }
        return best;
    }

    static boolean isFillCandidate(Material material) {
        return !OVERWORLD_ORES.contains(material) && !NETHER_ORES.contains(material)
                && !NEVER_FILL.contains(material)
                && material.isBlock() && material.isSolid() && material.isOccluding()
                && !material.hasGravity() && !material.isInteractable();
    }

    private static Material fallbackFill(Material removed, World.Environment environment, int y) {
        if (environment == World.Environment.NETHER) {
            return Material.NETHERRACK;
        }
        return removed.name().startsWith("DEEPSLATE_") || y < 0 ? Material.DEEPSLATE : Material.STONE;
    }

    private static void generateVein(GenerationRegion region, Random random,
            int chunkX, int chunkZ, HeightReference reference, VeinDefinition vein,
            int worldMinY, int worldMaxY) {
        int minY = scaledMinY(reference, vein, worldMinY, worldMaxY);
        int maxY = scaledMaxY(reference, vein, worldMinY, worldMaxY);
        if (maxY < minY) {
            return;
        }

        BlockPos start = findStart(region, random, chunkX, chunkZ, minY, maxY, vein);
        if (start == null) {
            return;
        }

        List<BlockPos> placed = new ArrayList<>(vein.size());
        Set<BlockPos> visited = new HashSet<>(vein.size() * 3);
        if (tryPlace(region, random, start, vein)) {
            placed.add(start);
            visited.add(start);
        } else {
            return;
        }

        int failures = 0;
        int failureLimit = Math.max(64, vein.size() * 30);
        while (placed.size() < vein.size() && failures < failureLimit) {
            int recentWindow = Math.min(placed.size(), 24);
            BlockPos base = placed.get(placed.size() - 1 - random.nextInt(recentWindow));
            int[] direction = DIRECTIONS[random.nextInt(DIRECTIONS.length)];
            BlockPos candidate = new BlockPos(
                    base.x() + direction[0], base.y() + direction[1], base.z() + direction[2]);
            if (!visited.add(candidate)
                    || (candidate.x() >> 4) != chunkX
                    || (candidate.z() >> 4) != chunkZ
                    || candidate.y() < worldMinY
                    || candidate.y() >= worldMaxY) {
                failures++;
                continue;
            }
            if (tryPlace(region, random, candidate, vein)) {
                placed.add(candidate);
                failures = Math.max(0, failures - 2);
            } else {
                failures++;
            }
        }
    }

    /** Lowest start Y of {@code vein} in a world with these build limits (maxY exclusive). */
    static int scaledMinY(HeightReference reference, VeinDefinition vein, int worldMinY, int worldMaxY) {
        return Math.max(worldMinY, reference.scale(vein.minY(), worldMinY, worldMaxY));
    }

    /** Highest start Y of {@code vein}; below {@link #scaledMinY} the pass places nothing in that world. */
    static int scaledMaxY(HeightReference reference, VeinDefinition vein, int worldMinY, int worldMaxY) {
        return Math.min(worldMaxY - 1, reference.scale(vein.maxY(), worldMinY, worldMaxY));
    }

    private static BlockPos findStart(GenerationRegion region, Random random, int chunkX, int chunkZ,
            int minY, int maxY, VeinDefinition vein) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        for (int attempt = 0; attempt < 32; attempt++) {
            int x = startX + random.nextInt(16);
            int z = startZ + random.nextInt(16);
            int y = sampleY(random, minY, maxY, vein.distribution());
            if (matchesBiome(region, x, y, z, vein.biomes())
                    && isReplaceable(region.getType(x, y, z), vein.hostType())) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    static int sampleY(Random random, int minY, int maxY, VeinDefinition.Distribution distribution) {
        if (maxY == minY) {
            return minY;
        }
        double unit = distribution == VeinDefinition.Distribution.TRIANGLE
                ? (random.nextDouble() + random.nextDouble()) * 0.5
                : random.nextDouble();
        return minY + Math.min(maxY - minY, (int) Math.floor(unit * (maxY - minY + 1)));
    }

    private static boolean tryPlace(GenerationRegion region, Random random, BlockPos pos, VeinDefinition vein) {
        Material host = region.getType(pos.x(), pos.y(), pos.z());
        if (!isReplaceable(host, vein.hostType()) || !matchesBiome(region, pos.x(), pos.y(), pos.z(), vein.biomes())) {
            return false;
        }
        if (vein.discardOnAirExposure() > 0.0 && isAirExposed(region, pos)
                && random.nextDouble() < vein.discardOnAirExposure()) {
            return false;
        }
        Material target = vein.deepslateMaterial() != null
                && (host == Material.DEEPSLATE || host == Material.TUFF)
                ? vein.deepslateMaterial() : vein.material();
        region.setType(pos.x(), pos.y(), pos.z(), target);
        return true;
    }

    private static boolean isReplaceable(Material material, VeinDefinition.HostType hostType) {
        return switch (hostType) {
            case OVERWORLD_BASE -> OVERWORLD_HOSTS.contains(material);
            case NETHER_BASE -> NETHER_HOSTS.contains(material);
            case NETHERRACK -> material == Material.NETHERRACK;
        };
    }

    private static boolean isAirExposed(GenerationRegion region, BlockPos pos) {
        for (int[] direction : DIRECTIONS) {
            if (Math.abs(direction[0]) + Math.abs(direction[1]) + Math.abs(direction[2]) != 1) {
                continue;
            }
            int x = pos.x() + direction[0];
            int y = pos.y() + direction[1];
            int z = pos.z() + direction[2];
            if (region.isInRegion(x, y, z) && region.getType(x, y, z).isAir()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static boolean matchesBiome(GenerationRegion region, int x, int y, int z, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return true;
        }
        String key = region.getBiomeKey(x, y, z).toLowerCase(Locale.ROOT);
        if (allowed.contains(key)) {
            return true;
        }
        // Iris cave biomes can be exposed with an iris:* registry key while its
        // pack uses a slash-separated load key such as carving/drip.
        for (String alias : allowed) {
            if (!alias.contains(":") && (key.endsWith(":" + alias) || key.endsWith("/" + alias))) {
                return true;
            }
        }
        return false;
    }

    interface GenerationRegion {
        Material getType(int x, int y, int z);

        void setType(int x, int y, int z, Material material);

        boolean isInRegion(int x, int y, int z);

        String getBiomeKey(int x, int y, int z);
    }

    private record LimitedGenerationRegion(LimitedRegion delegate) implements GenerationRegion {
        @Override
        public Material getType(int x, int y, int z) {
            return delegate.getType(x, y, z);
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            delegate.setType(x, y, z, material);
        }

        @Override
        public boolean isInRegion(int x, int y, int z) {
            return delegate.isInRegion(x, y, z);
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getBiomeKey(int x, int y, int z) {
            Biome biome = delegate.getBiome(x, y, z);
            return biome.getKey().toString();
        }
    }

    private record ChunkGenerationRegion(World world, Chunk chunk) implements GenerationRegion {
        @Override
        public Material getType(int x, int y, int z) {
            return chunk.getBlock(x - (chunk.getX() << 4), y, z - (chunk.getZ() << 4)).getType();
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            chunk.getBlock(x - (chunk.getX() << 4), y, z - (chunk.getZ() << 4)).setType(material, false);
        }

        @Override
        public boolean isInRegion(int x, int y, int z) {
            return (x >> 4) == chunk.getX() && (z >> 4) == chunk.getZ()
                    && y >= world.getMinHeight() && y < world.getMaxHeight();
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getBiomeKey(int x, int y, int z) {
            return world.getBiome(x, y, z).getKey().toString();
        }
    }

    private record BlockPos(int x, int y, int z) {
    }
}
