/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable UTC window and per-tick work budget for chunk retrofitting. */
record RetrofitSchedule(boolean enabled, LocalTime startUtc, LocalTime endUtc, int maxWorkMillisPerTick, int maxChunksPerMinute) {
    private static final int DEFAULT_BUDGET_MILLIS = 4;
    private static final Pattern TIME_TEXT = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    RetrofitSchedule {
        if (maxChunksPerMinute < 0 || maxChunksPerMinute > 10_000) throw new IllegalArgumentException("max-chunks-per-minute must be 0..10000");
        Objects.requireNonNull(startUtc, "startUtc");
        Objects.requireNonNull(endUtc, "endUtc");
        if (maxWorkMillisPerTick < 1 || maxWorkMillisPerTick > 20) {
            throw new IllegalArgumentException("max-work-millis-per-tick must be between 1 and 20");
        }
    }

    /** Loads settings.maintenance.*; absent configuration retains legacy behavior. */
    static RetrofitSchedule load(ConfigurationSection config) {
        if (config != null && config.contains("settings.maintenance")
                && config.getConfigurationSection("settings.maintenance") == null) {
            throw new IllegalArgumentException("settings.maintenance must be a configuration section");
        }
        ConfigurationSection settings = config == null ? null : config.getConfigurationSection("settings.maintenance");
        if (settings == null) {
            return defaults();
        }
        Object rawEnabled = settings.get("enabled");
        boolean enabled = rawEnabled == null ? false : strictBoolean(rawEnabled, "settings.maintenance.enabled");
        LocalTime start = time(settings.get("start-utc"), "settings.maintenance.start-utc", "00:00");
        LocalTime end = time(settings.get("end-utc"), "settings.maintenance.end-utc", "00:00");
        int budget = integer(settings.get("max-work-millis-per-tick"),
                "settings.maintenance.max-work-millis-per-tick", DEFAULT_BUDGET_MILLIS);
        Object rawRate = settings.get("max-chunks-per-minute", 0);
        if (!(rawRate instanceof Number rate) || !Double.isFinite(rate.doubleValue())
                || rate.doubleValue() != Math.rint(rate.doubleValue()) || rate.doubleValue() < 0 || rate.doubleValue() > 10_000)
            throw new IllegalArgumentException("max-chunks-per-minute must be an integer 0..10000");
        return new RetrofitSchedule(enabled, start, end, budget, rate.intValue());
    }

    static RetrofitSchedule defaults() {
        return new RetrofitSchedule(false, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, DEFAULT_BUDGET_MILLIS, 0);
    }

    boolean allowed(Clock clock) {
        return allowed(Objects.requireNonNull(clock, "clock").instant());
    }

    boolean allowed(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (!enabled || startUtc.equals(endUtc)) {
            return true;
        }
        LocalTime utc = instant.atOffset(ZoneOffset.UTC).toLocalTime();
        if (startUtc.isBefore(endUtc)) {
            return !utc.isBefore(startUtc) && utc.isBefore(endUtc);
        }
        return !utc.isBefore(startUtc) || utc.isBefore(endUtc);
    }

    int maxWorkMillis() {
        return maxWorkMillisPerTick;
    }

    long budgetNanos() {
        return maxWorkMillisPerTick * 1_000_000L;
    }

    private static boolean strictBoolean(Object raw, String key) {
        if (raw instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    private static LocalTime time(Object raw, String key, String fallback) {
        if (raw == null) {
            raw = fallback;
        }
        if (!(raw instanceof String value) || !TIME_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " must use strict HH:mm UTC format");
        }
        return LocalTime.parse(value, TIME_FORMAT);
    }

    private static int integer(Object raw, String key, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer from 1 to 20");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 1 || value > 20) {
            throw new IllegalArgumentException(key + " must be an integer from 1 to 20");
        }
        return (int) value;
    }
}
