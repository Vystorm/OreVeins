package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ReadOnlyAnalysisTest {
    @Test
    void bundledPresetValidatesAndReportsOverlappingPassesAsWarnings() {
        var config = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        config.set("settings.enabled-worlds", java.util.List.of("missing-world"));
        var passes = new java.util.ArrayList<>(config.getMapList("veins"));
        var duplicate = new java.util.LinkedHashMap<Object, Object>(passes.get(0));
        duplicate.put("id", "overlap-probe");
        passes.add(duplicate);
        config.set("veins", passes);
        var result = validate(config, Set.of(), biome -> true);
        assertTrue(result.valid(), result.errors().toString());
        assertTrue(result.validPasses() > 0);
        assertTrue(result.warnings().stream().anyMatch(problem -> problem.code().equals("unknown-world")));
        assertTrue(result.warnings().stream().anyMatch(problem -> problem.code().equals("overlap")));
    }

    @Test
    void invalidMaterialAndOutOfRangeValuesAreReportedWithoutCoercion() {
        var config = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        var passes = new java.util.ArrayList<>(config.getMapList("veins"));
        var first = new java.util.LinkedHashMap<Object, Object>(passes.get(0));
        first.put("material", "NOT_A_REAL_BLOCK");
        first.put("max-y", 999999);
        passes.set(0, first);
        config.set("veins", passes);
        var result = validate(config, Set.of(), biome -> false);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-material")));
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("y-outside-reference")));
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        var config = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        var passes = new java.util.ArrayList<>(config.getMapList("veins"));
        var first = new java.util.LinkedHashMap<Object, Object>(passes.get(0));
        first.put("attempts-per-chunk", Double.NaN);
        first.put("spawn-chance", Double.POSITIVE_INFINITY);
        passes.set(0, first);
        config.set("veins", passes);

        var result = validate(config, Set.of(), biome -> true);
        assertFalse(result.valid());
        assertEquals(2, result.errors().stream().filter(problem -> problem.code().equals("invalid-number")
                && problem.subject().startsWith(String.valueOf(first.get("id")) + ":")).count());
    }

    @Test
    void presetComparisonReportsChangedFieldsAndScaledYWithoutApplyingAnything() {
        var activeConfig = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        var active = GeneratorSettings.load(activeConfig, java.util.logging.Logger.getAnonymousLogger());
        var preset = GeneratorSettings.load(GeneratorSettingsTest.resource(ConfigFiles.LEGACY_DEFAULTS_RESOURCE),
                java.util.logging.Logger.getAnonymousLogger());
        var comparison = ReadOnlyAnalysis.compare("iris-tall-world", active, preset,
                java.util.List.of(new WebModel.WorldView("world", org.bukkit.World.Environment.NORMAL, -64, 320)));
        assertTrue(comparison.changedPasses() > 0);
        assertTrue(comparison.passes().stream().anyMatch(delta -> !delta.fields().isEmpty()));
        assertTrue(comparison.referenceChanges().stream().anyMatch(change -> change.startsWith("NORMAL:")));
        assertTrue(comparison.passes().stream().allMatch(delta -> !delta.state().equals("unchanged")));
    }

    @Test
    void strictValidationRejectsWrongContainerTypesAndNonBlockMaterials() {
        var config = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        var passes = new java.util.ArrayList<>(config.getMapList("veins"));
        var first = new java.util.LinkedHashMap<Object, Object>(passes.getFirst());
        first.put("material", "DIAMOND");
        passes.set(0, first);
        passes.add(null);
        config.set("veins", passes);
        config.set("settings.enabled-worlds", "world");

        var result = validate(config, Set.of(), biome -> true);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-material")));
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-vein-entry")));
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-enabled-worlds")));
    }

    @Test
    void maintenanceScheduleAndReferenceValuesAreValidatedStrictly() {
        var config = GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE);
        config.set("settings.maintenance.enabled", "true");
        config.set("reference-heights.NORMAL.min-y", "-64");

        var result = validate(config, Set.of(), biome -> true);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-maintenance")));
        assertTrue(result.errors().stream().anyMatch(problem -> problem.code().equals("invalid-reference")));
    }

    private static ReadOnlyAnalysis.Validation validate(org.bukkit.configuration.file.FileConfiguration config,
            Set<String> worlds, java.util.function.Predicate<String> biomeExists) {
        Set<String> knownBlocks = new HashSet<>();
        for (var vein : GeneratorSettingsTest.resource(ConfigFiles.DEFAULT_RESOURCE).getMapList("veins")) {
            Object material = vein.get("material");
            Object deepslate = vein.get("deepslate-material");
            if (material instanceof String name) knownBlocks.add(name.toUpperCase(java.util.Locale.ROOT));
            if (deepslate instanceof String name) knownBlocks.add(name.toUpperCase(java.util.Locale.ROOT));
        }
        // DIAMOND is a valid Material enum but a non-block; config ores only allow block materials.
        knownBlocks.remove("DIAMOND");
        return ReadOnlyAnalysis.validate(config, worlds, biomeExists, name -> {
            String canonical = name.toUpperCase(java.util.Locale.ROOT);
            return knownBlocks.contains(canonical) ? canonical : null;
        });
    }
}
