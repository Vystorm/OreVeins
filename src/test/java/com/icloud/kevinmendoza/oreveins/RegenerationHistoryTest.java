package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegenerationHistoryTest {
    private static Clock clock(long millis) { return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC); }

    @Test
    void roundTripsCountersAndReturnsDetachedImmutableSnapshots(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("history.properties");
        RegenerationHistory history = new RegenerationHistory(file, clock(1_000));
        UUID id = history.begin("admin", "world:overworld");
        assertNotNull(id);
        assertTrue(history.queued(id, 12));
        assertTrue(history.completed(id));
        assertTrue(history.failed(id));
        assertTrue(history.skipped(id));
        assertTrue(history.cancelled(id, 2));
        assertTrue(history.finish(id, RegenerationHistory.Status.COMPLETED));
        List<RegenerationHistory.Entry> snapshot = history.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        history.flush();
        assertFalse(history.dirty());

        RegenerationHistory reloaded = new RegenerationHistory(file, clock(2_000));
        assertTrue(reloaded.readable());
        assertEquals(List.of(new RegenerationHistory.Entry(id, "admin", "world:overworld", 1_000, 1_000,
                12, 1, 1, 1, 2, RegenerationHistory.Status.COMPLETED)), reloaded.snapshot());
    }

    @Test
    void restartMarksActiveRunInterruptedWithoutRetryingIt(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("history.properties");
        RegenerationHistory first = new RegenerationHistory(file, clock(100));
        UUID id = first.begin("console", "region:A");
        assertTrue(first.queued(id, 8));
        first.flush();

        RegenerationHistory restarted = new RegenerationHistory(file, clock(250));
        assertTrue(restarted.readable());
        assertTrue(restarted.dirty());
        assertEquals(RegenerationHistory.Status.INTERRUPTED, restarted.snapshot().getFirst().status());
        assertEquals(250, restarted.snapshot().getFirst().endedMillis());
        restarted.flush();
        RegenerationHistory afterFlush = new RegenerationHistory(file, clock(300));
        assertEquals(RegenerationHistory.Status.INTERRUPTED, afterFlush.snapshot().getFirst().status());
        assertFalse(afterFlush.dirty());
        assertNotNull(afterFlush.begin("other", "region:B"));
    }

    @Test
    void corruptHistoryRemainsUntouchedAndCannotBeFlushed(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("history.properties");
        byte[] original = "schema=9\ncount=0\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(file, original);
        RegenerationHistory history = new RegenerationHistory(file, clock(10));
        assertFalse(history.readable());
        assertTrue(history.snapshot().isEmpty());
        assertThrows(IOException.class, history::flush);
        assertNull(history.begin("admin", "world"));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(file)));
    }

    @Test
    void enforcesEntryAndActiveBoundsAndEvictsOldestInactive(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("history.properties");
        RegenerationHistory history = new RegenerationHistory(file, clock(1));
        UUID oldest = null;
        for (int i = 0; i < 100; i++) {
            UUID id = history.begin("actor", "scope-" + i);
            assertNotNull(id);
            if (i == 0) oldest = id;
            assertTrue(history.finish(id, RegenerationHistory.Status.COMPLETED));
        }
        UUID oldestId = oldest;
        assertEquals(100, history.snapshot().size());
        UUID newest = history.begin("actor", "newest");
        assertNotNull(newest);
        assertEquals(100, history.snapshot().size());
        assertFalse(history.snapshot().stream().anyMatch(entry -> entry.id().equals(oldestId)));
        assertTrue(history.finish(newest, RegenerationHistory.Status.COMPLETED));

        for (int i = 0; i < RegenerationHistory.MAX_ACTIVE; i++) assertNotNull(history.begin("actor", "active-" + i));
        assertNull(history.begin("actor", "over-limit"));
    }

    @Test
    void rejectsInvalidTextNegativeCountsAndCounterOverflow(@TempDir Path folder) {
        RegenerationHistory history = new RegenerationHistory(folder.resolve("history.properties"), clock(1));
        assertNull(history.begin(" ", "scope"));
        assertNull(history.begin("x".repeat(129), "scope"));
        UUID id = history.begin("actor", "scope");
        assertFalse(history.queued(id, -1));
        assertTrue(history.queued(id, Long.MAX_VALUE));
        assertFalse(history.queued(id, 1));
        assertEquals(Long.MAX_VALUE, history.snapshot().getFirst().queued());
        assertFalse(history.finish(id, RegenerationHistory.Status.ACTIVE));
    }
}
