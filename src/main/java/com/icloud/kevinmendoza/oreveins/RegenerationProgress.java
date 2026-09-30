/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists a running "regenerate all" pass so it survives server restarts.
 * The marker file says a pass is active; the log file holds one 24-byte record
 * (world UUID, chunk x, chunk z) per finished chunk. A torn trailing record from
 * a crash is ignored, so that chunk is simply processed again.
 */
final class RegenerationProgress {
    private static final String ACTIVE_FILE = "regenerate-all.active";
    private static final String DONE_FILE = "regenerate-all.done";
    private static final int RECORD_BYTES = 24;

    private final Path activeFile;
    private final Path doneFile;
    private final Map<UUID, Set<Long>> done = new ConcurrentHashMap<>();
    private DataOutputStream output;
    private volatile int doneCount;

    RegenerationProgress(Path dataFolder) {
        this.activeFile = dataFolder.resolve(ACTIVE_FILE);
        this.doneFile = dataFolder.resolve(DONE_FILE);
    }

    boolean active() {
        return Files.exists(activeFile);
    }

    /** Starts a fresh pass and forgets the chunks of any earlier one. */
    void begin() throws IOException {
        close();
        done.clear();
        doneCount = 0;
        Files.createDirectories(activeFile.getParent());
        Files.deleteIfExists(doneFile);
        Files.writeString(activeFile, Long.toString(System.currentTimeMillis()));
        open();
    }

    /** Continues an interrupted pass and loads the chunks it already finished. */
    void resume() throws IOException {
        close();
        done.clear();
        doneCount = 0;
        if (Files.exists(doneFile)) {
            // Drop a torn trailing record so new records stay aligned.
            long whole = Files.size(doneFile) / RECORD_BYTES * RECORD_BYTES;
            try (FileChannel channel = FileChannel.open(doneFile, StandardOpenOption.WRITE)) {
                channel.truncate(whole);
            }
            try (InputStream in = Files.newInputStream(doneFile)) {
                read(in);
            }
        }
        open();
    }

    boolean isDone(UUID world, int x, int z) {
        Set<Long> chunks = done.get(world);
        return chunks != null && chunks.contains(key(x, z));
    }

    void markDone(UUID world, int x, int z) throws IOException {
        if (output == null || !done.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).add(key(x, z))) {
            return;
        }
        output.writeLong(world.getMostSignificantBits());
        output.writeLong(world.getLeastSignificantBits());
        output.writeInt(x);
        output.writeInt(z);
        output.flush();
        doneCount++;
    }

    int doneCount() {
        return doneCount;
    }

    /** Ends the pass: the next start will not resume it. */
    void finish() throws IOException {
        close();
        done.clear();
        doneCount = 0;
        Files.deleteIfExists(activeFile);
        Files.deleteIfExists(doneFile);
    }

    void close() {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // Every record is flushed when written; nothing is lost here.
        }
        output = null;
    }

    void read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        while (true) {
            UUID world;
            int x;
            int z;
            try {
                world = new UUID(in.readLong(), in.readLong());
                x = in.readInt();
                z = in.readInt();
            } catch (EOFException endOfLog) {
                return;
            }
            if (done.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).add(key(x, z))) {
                doneCount++;
            }
        }
    }

    private void open() throws IOException {
        OutputStream stream = Files.newOutputStream(doneFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        output = new DataOutputStream(stream);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
