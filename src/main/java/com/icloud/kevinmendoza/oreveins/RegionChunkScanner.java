/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RegionChunkScanner {
    private static final int LOCATION_TABLE_BYTES = 4096;
    private static final int CHUNKS_PER_REGION_SIDE = 32;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionChunkScanner() {
    }

    static ScanResult scan(Set<Path> regionDirectories, WarningSink warnings) {
        Set<ChunkCoordinate> chunks = new HashSet<>();
        int failedFiles = 0;
        for (Path directory : regionDirectories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "r.*.*.mca")) {
                for (Path regionFile : stream) {
                    try {
                        chunks.addAll(scanRegionFile(regionFile));
                    } catch (IOException | IllegalArgumentException exception) {
                        failedFiles++;
                        warnings.warn("scan.region-file-failed", String.valueOf(regionFile.getFileName()),
                                exception.getMessage());
                    }
                }
            } catch (IOException exception) {
                failedFiles++;
                warnings.warn("scan.region-directory-failed", String.valueOf(directory),
                        exception.getMessage());
            }
        }
        return new ScanResult(Set.copyOf(chunks), failedFiles);
    }

    static Set<ChunkCoordinate> scanRegionFile(Path regionFile) throws IOException {
        Matcher matcher = REGION_FILE.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported region filename");
        }
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        ByteBuffer header = ByteBuffer.allocate(LOCATION_TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
        try (SeekableByteChannel channel = Files.newByteChannel(regionFile, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    throw new EOFException("location table is shorter than 4096 bytes");
                }
            }
        }
        header.flip();
        Set<ChunkCoordinate> chunks = new HashSet<>();
        for (int index = 0; index < 1024; index++) {
            int location = header.getInt();
            int sectorOffset = location >>> 8;
            int sectorCount = location & 0xff;
            if (sectorOffset >= 2 && sectorCount > 0) {
                int localX = index & (CHUNKS_PER_REGION_SIDE - 1);
                int localZ = index >> 5;
                chunks.add(new ChunkCoordinate(
                        regionX * CHUNKS_PER_REGION_SIDE + localX,
                        regionZ * CHUNKS_PER_REGION_SIDE + localZ));
            }
        }
        return chunks;
    }

    /** Receives a message key, the affected path and the error text. */
    interface WarningSink {
        void warn(String key, String path, String error);
    }

    record ScanResult(Set<ChunkCoordinate> chunks, int failedFiles) {
    }

    record ChunkCoordinate(int x, int z) {
    }
}
