/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

record HeightReference(int minY, int maxY) {
    HeightReference {
        if (maxY <= minY) {
            throw new IllegalArgumentException("reference max-y must be greater than min-y");
        }
    }

    int scale(int referenceY, int worldMinY, int worldMaxY) {
        double fraction = (referenceY - minY) / (double) (maxY - minY);
        return worldMinY + (int) Math.round(fraction * (worldMaxY - worldMinY));
    }
}
