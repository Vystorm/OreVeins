/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Incremental filesystem-only preview of existing Anvil region files.
 * Caller supplies an immutable path snapshot prepared on the server thread. This class never touches Bukkit,
 * worlds, chunks, or blocks. A step reads no more than its file budget and holds no world-wide chunk list.
 */
final class RegionFilesPreview implements AutoCloseable {
    private static final int MAX_DIRECTORIES = 128;
    private static final int MAX_ERROR_DETAILS = 12;
    private static final int MAX_ERROR_TEXT = 200;

    record Snapshot(long storedChunks, long filesScanned, long errors, boolean complete,
                    boolean cancelled, List<String> details, boolean detailsCapped) {
        Snapshot { details = List.copyOf(details); }
    }

    private final List<Path> directories;
    private final List<String> details = new ArrayList<>();
    private int nextDirectory;
    private Path currentDirectory;
    private DirectoryStream<Path> openDirectory;
    private Iterator<Path> currentFiles;
    private long storedChunks;
    private long filesScanned;
    private long errors;
    private boolean complete;
    private boolean cancelled;
    private boolean detailsCapped;

    RegionFilesPreview(Collection<Path> regionDirectories) {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (Path directory : regionDirectories) unique.add(Objects.requireNonNull(directory).toAbsolutePath().normalize());
        if (unique.size() > MAX_DIRECTORIES) throw new IllegalArgumentException("At most 128 region directories may be previewed");
        directories = List.copyOf(unique);
        if (directories.isEmpty()) complete = true;
    }

    /** Processes at most maxFiles matching region files; may be called incrementally or asynchronously. */
    synchronized Snapshot step(int maxFiles) {
        if (maxFiles < 1 || maxFiles > 128) throw new IllegalArgumentException("maxFiles must be 1..128");
        if (complete || cancelled) return snapshot();
        int processed = 0;
        while (processed < maxFiles && !complete && !cancelled) {
            if (currentFiles == null) {
                if (!openNextDirectory()) continue;
                if (complete || cancelled) break;
            }
            try {
                if (currentFiles.hasNext()) {
                    Path file = currentFiles.next();
                    processed++;
                    filesScanned++;
                    try {
                        storedChunks += RegionChunkScanner.scanRegionFile(file).size();
                    } catch (IOException | IllegalArgumentException failure) {
                        addError(file.getFileName().toString(), failure);
                    }
                    if (!currentFiles.hasNext()) closeOpenDirectory();
                } else {
                    closeOpenDirectory();
                }
            } catch (RuntimeException iterationFailure) {
                processed++;
                addError(String.valueOf(currentDirectory), iterationFailure);
                closeOpenDirectory();
            }
        }
        if (nextDirectory >= directories.size() && currentFiles == null) complete = true;
        return snapshot();
    }

    private boolean openNextDirectory() {
        while (nextDirectory < directories.size()) {
            Path directory = directories.get(nextDirectory++);
            if (!Files.isDirectory(directory)) {
                errors++;
                addDetail(directory.toString() + ": not a region directory");
                continue;
            }
            try {
                openDirectory = Files.newDirectoryStream(directory, "r.*.*.mca");
                currentFiles = openDirectory.iterator();
                currentDirectory = directory;
                return true;
            } catch (IOException | SecurityException failure) {
                addError(directory.toString(), failure);
            }
        }
        complete = true;
        return false;
    }

    private void addError(String path, Exception failure) {
        errors++;
        String reason = failure.getMessage();
        addDetail(path + ": " + (reason == null ? failure.getClass().getSimpleName() : reason));
    }

    private void addDetail(String text) {
        if (details.size() >= MAX_ERROR_DETAILS) {
            detailsCapped = true;
            return;
        }
        details.add(text.length() <= MAX_ERROR_TEXT ? text : text.substring(0, MAX_ERROR_TEXT));
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(storedChunks, filesScanned, errors, complete, cancelled, details, detailsCapped);
    }

    synchronized void cancel() {
        if (complete || cancelled) return;
        cancelled = true;
        closeOpenDirectory();
    }

    private void closeOpenDirectory() {
        if (openDirectory != null) {
            try { openDirectory.close(); } catch (IOException ignored) { }
            openDirectory = null;
            currentFiles = null;
            currentDirectory = null;
        }
    }

    @Override public void close() { cancel(); }
}
