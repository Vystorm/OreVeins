/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RetrofitScheduleTest {
    @Test
    void absentOrDisabledPolicyKeepsLegacyAlwaysAllowedBehavior() {
        RetrofitSchedule absent = RetrofitSchedule.load(null);
        RetrofitSchedule disabled = load("enabled: false\nstart-utc: '08:00'\nend-utc: '09:00'");

        assertTrue(absent.allowed(Instant.parse("2026-10-13T23:59:00Z")));
        assertTrue(disabled.allowed(Instant.parse("2026-10-13T12:00:00Z")));
        assertEquals(0, absent.maxChunksPerMinute());
        assertEquals(12, load("max-chunks-per-minute: 12").maxChunksPerMinute());
        assertThrows(IllegalArgumentException.class, () -> load("max-chunks-per-minute: 1.5"));
        assertThrows(IllegalArgumentException.class, () -> load("max-chunks-per-minute: 10001"));
        assertEquals(4, absent.maxWorkMillis());
        assertEquals(4_000_000L, absent.budgetNanos());
    }

    @Test
    void enabledWindowIncludesStartAndExcludesEndUsingUtcClock() {
        RetrofitSchedule schedule = load("enabled: true\nstart-utc: '08:30'\nend-utc: '09:45'\n"
                + "max-work-millis-per-tick: 7");

        assertTrue(schedule.allowed(Clock.fixed(Instant.parse("2026-10-13T08:30:00Z"), ZoneOffset.UTC)));
        assertTrue(schedule.allowed(Instant.parse("2026-10-13T09:44:59Z")));
        assertFalse(schedule.allowed(Instant.parse("2026-10-13T09:45:00Z")));
        assertFalse(schedule.allowed(Instant.parse("2026-10-13T08:29:59Z")));
        assertEquals(7, schedule.maxWorkMillis());
    }

    @Test
    void overnightAndEqualBoundariesHaveExplicitSemantics() {
        RetrofitSchedule overnight = load("enabled: true\nstart-utc: '22:00'\nend-utc: '02:00'");
        RetrofitSchedule fullDay = load("enabled: true\nstart-utc: '02:00'\nend-utc: '02:00'");

        assertTrue(overnight.allowed(Instant.parse("2026-10-13T22:00:00Z")));
        assertTrue(overnight.allowed(Instant.parse("2026-10-14T01:59:59Z")));
        assertFalse(overnight.allowed(Instant.parse("2026-10-14T02:00:00Z")));
        assertFalse(overnight.allowed(Instant.parse("2026-10-14T12:00:00Z")));
        assertTrue(fullDay.allowed(Instant.parse("2026-10-14T12:00:00Z")));
    }

    @Test
    void validatesMalformedWindowsTypesAndBudgetsInsteadOfClamping() {
        assertThrows(IllegalArgumentException.class, () -> load("enabled: true\nstart-utc: '8:00'\nend-utc: '09:00'"));
        assertThrows(IllegalArgumentException.class, () -> load("enabled: true\nstart-utc: '24:00'\nend-utc: '09:00'"));
        assertThrows(IllegalArgumentException.class, () -> load("enabled: 'yes'"));
        assertThrows(IllegalArgumentException.class, () -> load("max-work-millis-per-tick: 0"));
        assertThrows(IllegalArgumentException.class, () -> load("max-work-millis-per-tick: 21"));
        assertThrows(IllegalArgumentException.class, () -> load("max-work-millis-per-tick: 2.5"));
        assertThrows(IllegalArgumentException.class, () -> load("max-work-millis-per-tick: .nan"));
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.maintenance", "broken");
        assertThrows(IllegalArgumentException.class, () -> RetrofitSchedule.load(config));
        assertEquals(LocalTime.MIDNIGHT, RetrofitSchedule.defaults().startUtc());
    }

    private static RetrofitSchedule load(String maintenanceYaml) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.maintenance", YamlConfiguration.loadConfiguration(
                new java.io.StringReader(maintenanceYaml)));
        return RetrofitSchedule.load(config);
    }
}
