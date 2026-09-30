/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Chunks per minute over a sliding window, from samples of an ever-growing
 * counter (the processed-chunk count). Not thread-safe; used on the server thread.
 */
final class RateMeter {
    private record Sample(long timeMillis, long count) {
    }

    private final long windowMillis;
    private final Deque<Sample> samples = new ArrayDeque<>();

    RateMeter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    void sample(long timeMillis, long count) {
        Sample last = samples.peekLast();
        if (last != null && count < last.count()) {
            // The counter restarted (plugin reload); old samples say nothing now.
            samples.clear();
        }
        samples.addLast(new Sample(timeMillis, count));
        while (samples.size() > 2 && samples.peekFirst().timeMillis() < timeMillis - windowMillis) {
            samples.removeFirst();
        }
    }

    /** Per-minute rate over the window, 0 until two samples at least a second apart exist. */
    double perMinute() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null || last.timeMillis() - first.timeMillis() < 1000L) {
            return 0.0;
        }
        return (last.count() - first.count()) * 60_000.0 / (last.timeMillis() - first.timeMillis());
    }
}
