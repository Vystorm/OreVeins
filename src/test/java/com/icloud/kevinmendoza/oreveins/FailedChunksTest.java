package com.icloud.kevinmendoza.oreveins;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class FailedChunksTest {
    @TempDir Path dir;
    @Test void givenFailedChunk_whenReloadedAndRetried_thenAttemptsPersistUntilSuccess() throws Exception {
        Path file = dir.resolve("failed.properties"); UUID world = UUID.randomUUID();
        FailedChunks chunks = new FailedChunks(file); chunks.add(world, -1, 3, "generation-failed");
        FailedChunks reload = new FailedChunks(file); assertTrue(reload.contains(world, -1, 3));
        reload.add(world, -1, 3, "generation-failed"); assertEquals(2, reload.snapshot().getFirst().attempts());
        reload.remove(world, -1, 3); assertFalse(new FailedChunks(file).contains(world, -1, 3));
    }
    @Test void givenCorruption_whenWriting_thenOriginalRemainsUntouched() throws Exception {
        Path file = dir.resolve("failed.properties"); Files.writeString(file, "invalid");
        FailedChunks chunks = new FailedChunks(file); assertFalse(chunks.readable());
        assertThrows(java.io.IOException.class, () -> chunks.add(UUID.randomUUID(), 0, 0, "failure"));
        assertEquals("invalid", Files.readString(file));
    }
}
