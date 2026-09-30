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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegenerationProgressTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void finishedChunksSurviveARestart(@TempDir Path folder) throws Exception {
        RegenerationProgress first = new RegenerationProgress(folder);
        first.begin();
        first.markDone(WORLD, 3, -7);
        first.markDone(WORLD, -100000, 42);
        first.close();

        RegenerationProgress second = new RegenerationProgress(folder);
        assertTrue(second.active());
        second.resume();
        assertEquals(2, second.doneCount());
        assertTrue(second.isDone(WORLD, 3, -7));
        assertTrue(second.isDone(WORLD, -100000, 42));
        assertFalse(second.isDone(WORLD, -7, 3));
        second.close();
    }

    @Test
    void tornRecordIsDroppedAndLaterRecordsStayReadable(@TempDir Path folder) throws Exception {
        RegenerationProgress first = new RegenerationProgress(folder);
        first.begin();
        first.markDone(WORLD, 1, 1);
        first.close();
        Files.write(folder.resolve("regenerate-all.done"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);

        RegenerationProgress second = new RegenerationProgress(folder);
        second.resume();
        second.markDone(WORLD, 2, 2);
        second.close();

        RegenerationProgress third = new RegenerationProgress(folder);
        third.resume();
        assertEquals(2, third.doneCount());
        assertTrue(third.isDone(WORLD, 1, 1));
        assertTrue(third.isDone(WORLD, 2, 2));
        third.close();
    }

    @Test
    void finishedPassIsNotResumed(@TempDir Path folder) throws Exception {
        RegenerationProgress progress = new RegenerationProgress(folder);
        progress.begin();
        progress.markDone(WORLD, 5, 5);
        progress.finish();
        assertFalse(progress.active());
        assertFalse(Files.exists(folder.resolve("regenerate-all.done")));
    }
}
