/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionChunkScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOnlyPresentChunksFromAnvilLocationTable() throws Exception {
        ByteBuffer header = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
        for (int index : new int[] {0, 31, 32, 1023}) {
            header.putInt(index * Integer.BYTES, 0x00000201 + index * 0x00000100);
        }
        Path region = temporaryDirectory.resolve("r.-2.3.mca");
        Files.write(region, header.array());

        assertEquals(Set.of(
                        new RegionChunkScanner.ChunkCoordinate(-64, 96),
                        new RegionChunkScanner.ChunkCoordinate(-33, 96),
                        new RegionChunkScanner.ChunkCoordinate(-64, 97),
                        new RegionChunkScanner.ChunkCoordinate(-33, 127)),
                RegionChunkScanner.scanRegionFile(region));
    }
}
