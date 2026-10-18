package com.icloud.kevinmendoza.oreveins;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChunkStartLimiterTest {
 @Test void slidingWindowDoesNotBurstAcrossMinuteBoundary() {
  ChunkStartLimiter gate = new ChunkStartLimiter();
  assertTrue(gate.tryStart(59_000_000_000L, 2));
  assertTrue(gate.tryStart(60_000_000_000L, 2));
  assertFalse(gate.tryStart(61_000_000_000L, 2));
  assertTrue(gate.tryStart(119_000_000_000L, 2));
  assertFalse(gate.tryStart(119_500_000_000L, 2));
  assertTrue(gate.tryStart(120_000_000_000L, 2));
 }
 @Test void tighteningLimitRetainsRecentStarts() {
  ChunkStartLimiter gate = new ChunkStartLimiter();
  assertTrue(gate.tryStart(0, 0)); assertTrue(gate.tryStart(1, 0));
  assertFalse(gate.tryStart(2, 1));
  assertTrue(gate.tryStart(60_000_000_001L, 1));
 }
}
