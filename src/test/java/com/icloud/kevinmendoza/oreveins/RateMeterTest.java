/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RateMeterTest {
    @Test
    void measuresPerMinuteOverTheWindow() {
        RateMeter meter = new RateMeter(60_000L);
        assertEquals(0.0, meter.perMinute());
        meter.sample(0L, 100L);
        assertEquals(0.0, meter.perMinute(), "one sample says nothing");
        for (int second = 1; second <= 120; second++) {
            meter.sample(second * 1000L, 100L + second);
        }
        assertEquals(60.0, meter.perMinute(), 1e-9);
    }

    @Test
    void forgetsOldSamplesAndCounterRestarts() {
        RateMeter meter = new RateMeter(10_000L);
        meter.sample(0L, 0L);
        meter.sample(5_000L, 500L);
        for (int second = 6; second <= 30; second++) {
            meter.sample(second * 1000L, 500L);
        }
        assertEquals(0.0, meter.perMinute(), "idle for longer than the window");
        meter.sample(31_000L, 10L);
        assertEquals(0.0, meter.perMinute(), "a smaller count starts over");
        meter.sample(33_000L, 20L);
        assertEquals(300.0, meter.perMinute(), 1e-9);
    }
}
