/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Material;

/** Thread-safe counters for block writes that actually changed a generated block. */
final class GenerationStatistics {
    private static final int MAX_WORLDS = 128;
    private static final ConcurrentHashMap<Key, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> WORLDS = ConcurrentHashMap.newKeySet();

    private GenerationStatistics() {
    }

    static void record(String worldName, Material material) {
        Key key = new Key(worldName, material);
        LongAdder existing = COUNTS.get(key);
        if (existing != null) {
            existing.increment();
            return;
        }
        synchronized (WORLDS) {
            if (!WORLDS.contains(worldName) && WORLDS.size() >= MAX_WORLDS) {
                return;
            }
            WORLDS.add(worldName);
            COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
    }

    /** Returns a detached snapshot and performs no Bukkit lookups. */
    static Map<Key, Long> snapshot() {
        return COUNTS.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    /** Clears counters, primarily for plugin lifecycle reset and tests. */
    static void clear() {
        COUNTS.clear();
        WORLDS.clear();
    }

    record Key(String worldName, Material material) {
        Key {
            if (worldName == null || worldName.isBlank() || material == null) {
                throw new IllegalArgumentException("world name and material are required");
            }
        }
    }
}
