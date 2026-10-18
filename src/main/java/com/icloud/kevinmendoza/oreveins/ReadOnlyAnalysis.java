/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Pure, non-mutating analysis of candidate settings and the currently loaded ore model. */
final class ReadOnlyAnalysis {
    private ReadOnlyAnalysis() {}

    record Problem(String code, String subject) {}
    record Validation(List<Problem> errors, List<Problem> warnings, int validPasses) {
        Validation { errors = List.copyOf(errors); warnings = List.copyOf(warnings); }
        boolean valid() { return errors.isEmpty(); }
    }
    record PassDelta(String id, String state, List<String> fields, List<String> effectiveY) {
        PassDelta { fields = List.copyOf(fields); effectiveY = List.copyOf(effectiveY); }
        boolean changed() { return !"unchanged".equals(state) || !fields.isEmpty() || !effectiveY.isEmpty(); }
    }
    record Comparison(String preset, List<String> referenceChanges, List<PassDelta> passes) {
        Comparison { referenceChanges = List.copyOf(referenceChanges); passes = List.copyOf(passes); }
        long changedPasses() { return passes.stream().filter(PassDelta::changed).count(); }
    }

    static Validation validate(FileConfiguration config, Set<String> loadedWorldNames,
            Predicate<String> biomeExists) {
        return validate(config, loadedWorldNames, biomeExists, ReadOnlyAnalysis::canonicalBlockMaterial);
    }

    /** Injectable material lookup keeps structural validation tests independent of a running Paper registry. */
    static Validation validate(FileConfiguration config, Set<String> loadedWorldNames,
            Predicate<String> biomeExists, Function<String, String> blockMaterialLookup) {
        List<Problem> errors = new ArrayList<>();
        List<Problem> warnings = new ArrayList<>();
        try { RegenerationExclusions.load(config); }
        catch (IllegalArgumentException invalid) { errors.add(new Problem("invalid-excluded-areas", invalid.getMessage())); }
        Set<String> worlds = loadedWorldNames.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        Object rawWorlds = config.get("settings.enabled-worlds");
        if (rawWorlds != null) {
            if (!(rawWorlds instanceof List<?> enabledWorlds)) errors.add(new Problem("invalid-enabled-worlds", "settings.enabled-worlds"));
            else for (Object rawWorld : enabledWorlds) {
                if (!(rawWorld instanceof String configured) || configured.isBlank()) {
                    errors.add(new Problem("invalid-enabled-world", "settings.enabled-worlds"));
                } else if (!worlds.contains(configured.toLowerCase(Locale.ROOT))) {
                    warnings.add(new Problem("unknown-world", configured));
                }
            }
        }

        Map<World.Environment, HeightReference> references = new EnumMap<>(World.Environment.class);
        var refs = config.getConfigurationSection("reference-heights");
        if (refs == null) errors.add(new Problem(config.contains("reference-heights")
                ? "invalid-reference-section" : "missing-reference-section", "reference-heights"));
        else for (String key : refs.getKeys(false)) {
            try {
                World.Environment environment = World.Environment.valueOf(key.toUpperCase(Locale.ROOT));
                var section = refs.getConfigurationSection(key);
                if (section == null) throw new IllegalArgumentException();
                Integer min = integer(section.get("min-y"));
                Integer max = integer(section.get("max-y"));
                if (min == null || max == null || max <= min)
                    throw new IllegalArgumentException();
                references.put(environment, new HeightReference(min, max));
            } catch (RuntimeException invalid) {
                errors.add(new Problem("invalid-reference", key));
            }
        }

        Object rawEntries = config.get("veins");
        List<?> entries;
        if (rawEntries == null) entries = List.of();
        else if (rawEntries instanceof List<?> list) entries = list;
        else {
            errors.add(new Problem("invalid-veins", "veins"));
            entries = List.of();
        }
        if (entries.isEmpty()) errors.add(new Problem("no-veins", "veins"));
        Set<String> ids = new HashSet<>();
        List<PassCandidate> parsed = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Object rawEntry = entries.get(i);
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                errors.add(new Problem("invalid-vein-entry", "#" + i));
                continue;
            }
            String id = String.valueOf(entry.containsKey("id") ? entry.get("id") : "#" + i);
            int errorsBefore = errors.size();
            if (!(entry.get("id") instanceof String stringId) || stringId.isBlank()) {
                errors.add(new Problem("missing-id", id));
            } else if (!ids.add(id)) errors.add(new Problem("duplicate-id", id));

            World.Environment environment = null;
            try {
                if (!(entry.get("environment") instanceof String)) throw new IllegalArgumentException();
                environment = World.Environment.valueOf(String.valueOf(entry.get("environment")).toUpperCase(Locale.ROOT));
                if (environment != World.Environment.NORMAL && environment != World.Environment.NETHER)
                    throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { errors.add(new Problem("invalid-environment", id)); }

            String material = material(entry.get("material"), blockMaterialLookup);
            if (material == null) errors.add(new Problem("invalid-material", id + ":material=" + entry.get("material")));
            String deepslate = entry.get("deepslate-material") == null ? null : material(entry.get("deepslate-material"), blockMaterialLookup);
            if (entry.get("deepslate-material") != null && deepslate == null)
                errors.add(new Problem("invalid-material", id + ":deepslate-material=" + entry.get("deepslate-material")));

            String host = entry.get("replace") instanceof String value ? value : "";
            if (!Set.of("OVERWORLD_BASE", "NETHER_BASE", "NETHERRACK").contains(host.toUpperCase(Locale.ROOT)))
                errors.add(new Problem("invalid-host", id));
            Object rawDistribution = entry.containsKey("distribution") ? entry.get("distribution") : "UNIFORM";
            String distribution = rawDistribution instanceof String value ? value : "";
            if (!Set.of("UNIFORM", "TRIANGLE").contains(distribution.toUpperCase(Locale.ROOT)))
                errors.add(new Problem("invalid-distribution", id));

            Double attempts = number(entry.get("attempts-per-chunk"));
            Double chance = entry.containsKey("spawn-chance") ? number(entry.get("spawn-chance")) : Double.valueOf(1.0);
            Double discard = entry.containsKey("discard-on-air-exposure")
                    ? number(entry.get("discard-on-air-exposure")) : Double.valueOf(0.0);
            Integer size = integer(entry.get("size"));
            Integer min = integer(entry.get("min-y"));
            Integer max = integer(entry.get("max-y"));
            if (attempts == null || attempts < 0) errors.add(new Problem("invalid-number", id + ":attempts-per-chunk"));
            if (chance == null || chance < 0 || chance > 1) errors.add(new Problem("invalid-number", id + ":spawn-chance"));
            if (discard == null || discard < 0 || discard > 1) errors.add(new Problem("invalid-number", id + ":discard-on-air-exposure"));
            if (size == null || size < 1) errors.add(new Problem("invalid-number", id + ":size"));
            if (min == null || max == null || max < min) errors.add(new Problem("invalid-y-range", id));
            if (environment != null && min != null && max != null) {
                HeightReference reference = references.get(environment);
                if (reference == null) errors.add(new Problem("missing-reference", environment.name()));
                else if (min < reference.minY() || max >= reference.maxY())
                    errors.add(new Problem("y-outside-reference", id + ":" + min + ".." + max));
            }

            Set<String> biomes = new LinkedHashSet<>();
            Object rawBiomes = entry.get("biomes");
            if (rawBiomes != null && !(rawBiomes instanceof List<?>)) errors.add(new Problem("invalid-biomes", id));
            else if (rawBiomes instanceof List<?> list) for (Object raw : list) {
                if (!(raw instanceof String rawBiome) || rawBiome.isBlank()) {
                    errors.add(new Problem("invalid-biome", id));
                    continue;
                }
                String biome = rawBiome.toLowerCase(Locale.ROOT);
                if (!biome.contains(":")) biome = "minecraft:" + biome;
                if (!biomeExists.test(biome)) warnings.add(new Problem("unknown-biome", id + ":" + biome));
                biomes.add(biome);
            }

            if (errors.size() == errorsBefore && environment != null && material != null && min != null && max != null) {
                Set<String> materials = new HashSet<>();
                materials.add(material);
                if (deepslate != null) materials.add(deepslate);
                parsed.add(new PassCandidate(id, environment, materials, min, max, biomes));
            }
        }
        try {
            RetrofitSchedule.load(config);
        } catch (RuntimeException invalidSchedule) {
            errors.add(new Problem("invalid-maintenance", safeMessage(invalidSchedule)));
        }
        for (int i = 0; i < parsed.size(); i++) for (int j = i + 1; j < parsed.size(); j++) {
            PassCandidate a = parsed.get(i), b = parsed.get(j);
            if (a.environment != b.environment || a.maxY < b.minY || b.maxY < a.minY
                    || java.util.Collections.disjoint(a.materials, b.materials)
                    || !biomeScopesOverlap(a.biomes, b.biomes)) continue;
            warnings.add(new Problem("overlap", a.id + " / " + b.id));
        }
        return new Validation(errors, warnings, parsed.size());
    }

    static Comparison compare(String preset, GeneratorSettings active, GeneratorSettings reference,
            List<WebModel.WorldView> worlds) {
        List<String> referenceChanges = new ArrayList<>();
        for (World.Environment environment : World.Environment.values()) {
            HeightReference a = active.heightReferences().get(environment);
            HeightReference b = reference.heightReferences().get(environment);
            if (!java.util.Objects.equals(a, b)) referenceChanges.add(environment + ": " + display(a) + " → " + display(b));
        }
        Map<String, VeinDefinition> activeById = index(active.veins());
        Map<String, VeinDefinition> presetById = index(reference.veins());
        Set<String> ids = new java.util.TreeSet<>(); ids.addAll(activeById.keySet()); ids.addAll(presetById.keySet());
        List<PassDelta> deltas = new ArrayList<>();
        for (String id : ids) {
            VeinDefinition a = activeById.get(id), b = presetById.get(id);
            if (a == null) { deltas.add(new PassDelta(id, "preset-only", List.of(), List.of())); continue; }
            if (b == null) { deltas.add(new PassDelta(id, "active-only", List.of(), List.of())); continue; }
            List<String> fields = changedFields(a, b);
            List<String> y = new ArrayList<>();
            for (WebModel.WorldView world : worlds) {
                if (world.environment() != a.environment() || a.environment() != b.environment()) continue;
                HeightReference ar = active.heightReferences().get(a.environment());
                HeightReference br = reference.heightReferences().get(b.environment());
                if (ar == null || br == null) continue;
                String activeRange = VeinPopulator.scaledMinY(ar, a, world.minHeight(), world.maxHeight()) + ".."
                        + VeinPopulator.scaledMaxY(ar, a, world.minHeight(), world.maxHeight());
                String presetRange = VeinPopulator.scaledMinY(br, b, world.minHeight(), world.maxHeight()) + ".."
                        + VeinPopulator.scaledMaxY(br, b, world.minHeight(), world.maxHeight());
                if (!activeRange.equals(presetRange)) y.add(world.name() + " " + activeRange + " → " + presetRange);
            }
            PassDelta delta = new PassDelta(id, fields.isEmpty() && y.isEmpty() ? "unchanged" : "changed", fields, y);
            if (delta.changed()) deltas.add(delta);
        }
        return new Comparison(preset, referenceChanges, deltas);
    }

    private static List<String> changedFields(VeinDefinition a, VeinDefinition b) {
        List<String> fields = new ArrayList<>();
        if (a.environment() != b.environment()) fields.add("environment");
        if (a.material() != b.material()) fields.add("material");
        if (a.deepslateMaterial() != b.deepslateMaterial()) fields.add("deepslate-material");
        if (a.hostType() != b.hostType()) fields.add("replace");
        if (Double.compare(a.attemptsPerChunk(), b.attemptsPerChunk()) != 0) fields.add("attempts-per-chunk");
        if (Double.compare(a.spawnChance(), b.spawnChance()) != 0) fields.add("spawn-chance");
        if (a.size() != b.size()) fields.add("size");
        if (a.minY() != b.minY()) fields.add("min-y");
        if (a.maxY() != b.maxY()) fields.add("max-y");
        if (a.distribution() != b.distribution()) fields.add("distribution");
        if (Double.compare(a.discardOnAirExposure(), b.discardOnAirExposure()) != 0) fields.add("discard-on-air-exposure");
        if (!a.biomes().equals(b.biomes())) fields.add("biomes");
        return fields;
    }

    private static Map<String, VeinDefinition> index(List<VeinDefinition> veins) {
        Map<String, VeinDefinition> out = new HashMap<>();
        for (VeinDefinition vein : veins) out.putIfAbsent(vein.id(), vein);
        return out;
    }

    private static String display(HeightReference ref) { return ref == null ? "missing" : ref.minY() + ".." + (ref.maxY() - 1); }
    private static boolean biomeScopesOverlap(Set<String> a, Set<String> b) {
        return a.isEmpty() || b.isEmpty() || !java.util.Collections.disjoint(a, b);
    }
    private static String material(Object value, Function<String, String> lookup) {
        if (!(value instanceof String name)) return null;
        return lookup.apply(name);
    }
    private static String canonicalBlockMaterial(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isBlock() ? material.name() : null;
    }
    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "settings.maintenance" : message;
    }
    private static Double number(Object value) {
        if (!(value instanceof Number number)) return null;
        double parsed = number.doubleValue();
        return Double.isFinite(parsed) ? parsed : null;
    }
    private static Integer integer(Object value) {
        if (!(value instanceof Number n)) return null;
        double valueDouble = n.doubleValue();
        return valueDouble == Math.rint(valueDouble) && valueDouble >= Integer.MIN_VALUE && valueDouble <= Integer.MAX_VALUE
                ? (int) valueDouble : null;
    }

    private record PassCandidate(String id, World.Environment environment, Set<String> materials,
            int minY, int maxY, Set<String> biomes) {}
}
