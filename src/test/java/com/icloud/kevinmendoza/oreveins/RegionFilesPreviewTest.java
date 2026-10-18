/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFilesPreviewTest {
    @TempDir Path temp;

    @Test
    void scansIncrementallyCountsStoredEntriesAndCapturesFileErrors() throws Exception {
        Path regionDir = Files.createDirectory(temp.resolve("region"));
        Path brokenDir = Files.createDirectory(temp.resolve("broken"));
        ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0, 0x00000201);
        header.putInt(4 * 33, 0x00000301);
        Files.write(regionDir.resolve("r.0.0.mca"), header.array());
        Files.write(brokenDir.resolve("r.1.0.mca"), new byte[] {1, 2, 3});

        try (RegionFilesPreview preview = new RegionFilesPreview(java.util.List.of(regionDir, brokenDir))) {
            var first = preview.step(1);
            assertEquals(1, first.filesScanned());
            assertEquals(2, first.storedChunks());
            assertFalse(first.complete());

            var finished = preview.step(1);
            assertTrue(finished.complete());
            assertEquals(2, finished.filesScanned());
            assertEquals(1, finished.errors());
            assertEquals(2, finished.storedChunks());
            assertEquals(1, finished.details().size());
        }
    }

    @Test
    void cancellationStopsFurtherWorkAndDirectoryInputIsBounded() throws Exception {
        Path regionDir = Files.createDirectory(temp.resolve("empty"));
        RegionFilesPreview preview = new RegionFilesPreview(java.util.List.of(regionDir));
        preview.cancel();
        assertTrue(preview.snapshot().cancelled());
        assertEquals(0, preview.step(1).filesScanned());

        var tooManyDirectories = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> temp.resolve("region-" + index)).toList();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RegionFilesPreview(tooManyDirectories));
    }
}
