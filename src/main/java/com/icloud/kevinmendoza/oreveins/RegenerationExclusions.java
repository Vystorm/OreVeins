package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Explicit protected building areas. A intersecting chunk is skipped in its entirety before block access. */
record RegenerationExclusions(List<Area> areas) {
    static final RegenerationExclusions EMPTY = new RegenerationExclusions(List.of());
    RegenerationExclusions { areas = List.copyOf(areas); }
    record Area(String world, int minX, int maxX, int minZ, int maxZ) {
        boolean intersects(String name, UUID id, int chunkX, int chunkZ) {
            return (world.equalsIgnoreCase(name) || world.equals(id.toString()))
                    && (long)chunkX * 16 <= maxX && (long)chunkX * 16 + 15 >= minX
                    && (long)chunkZ * 16 <= maxZ && (long)chunkZ * 16 + 15 >= minZ;
        }
    }
    boolean blocks(String world, UUID id, int x, int z) {
        return areas.stream().anyMatch(area -> area.intersects(world, id, x, z));
    }
    static RegenerationExclusions load(ConfigurationSection config) {
        Object raw = config.get("settings.excluded-areas");
        if (raw == null) return EMPTY;
        if (!(raw instanceof List<?> rows) || rows.size() > 128) throw new IllegalArgumentException("excluded-areas must be a list of at most 128 areas");
        List<Area> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values) || !(values.get("world") instanceof String world)
                    || world.isBlank() || world.length() > 128) throw new IllegalArgumentException("excluded area requires world name or UUID");
            int minX = coordinate(values, "min-x"), maxX = coordinate(values, "max-x");
            int minZ = coordinate(values, "min-z"), maxZ = coordinate(values, "max-z");
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("excluded area has reversed bounds");
            result.add(new Area(world.trim().toLowerCase(Locale.ROOT), minX, maxX, minZ, maxZ));
        }
        return new RegenerationExclusions(result);
    }
    private static int coordinate(Map<?, ?> values, String key) {
        if (!(values.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || Math.abs(n.doubleValue()) > 30_000_000)
            throw new IllegalArgumentException("excluded area " + key + " must be a whole block coordinate within +/-30000000");
        return n.intValue();
    }
}
