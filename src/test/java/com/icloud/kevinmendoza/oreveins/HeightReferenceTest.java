/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HeightReferenceTest {
    @Test
    void preservesCoordinatesForMatchingWorldHeight() {
        HeightReference reference = new HeightReference(-256, 512);
        assertEquals(-256, reference.scale(-256, -256, 512));
        assertEquals(-64, reference.scale(-64, -256, 512));
        assertEquals(512, reference.scale(512, -256, 512));
    }

    @Test
    void scalesToDifferentWorldHeight() {
        HeightReference reference = new HeightReference(-256, 512);
        assertEquals(-64, reference.scale(-256, -64, 320));
        assertEquals(320, reference.scale(512, -64, 320));
        assertEquals(32, reference.scale(-64, -64, 320));
    }
}
