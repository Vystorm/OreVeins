package com.icloud.kevinmendoza.oreveins;

import java.util.ArrayDeque;

/** Server-thread sliding window; limits newly started chunks, never interrupts an active chunk. */
final class ChunkStartLimiter {
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    boolean tryStart(long monotonicNanos, int limit) {
        while (!starts.isEmpty() && monotonicNanos - starts.peekFirst() >= 60_000_000_000L) starts.removeFirst();
        if (limit > 0 && starts.size() >= limit) return false;
        // Retain the maximum configurable window even while the limit is disabled.
        if (starts.size() == 10_000) starts.removeFirst();
        starts.addLast(monotonicNanos);
        return true;
    }
}
