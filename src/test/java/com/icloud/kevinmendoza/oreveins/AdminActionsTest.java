/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class AdminActionsTest {
    @Test
    void radiusArgumentsParseLikeTheCommandDid() {
        assertEquals(OptionalInt.of(8), AdminActions.parseRadius("8"));
        assertEquals(OptionalInt.of(-1), AdminActions.parseRadius("-1"), "range is checked by the action");
        assertTrue(AdminActions.parseRadius("eight").isEmpty());
        assertTrue(AdminActions.parseRadius("2.5").isEmpty());
        assertTrue(AdminActions.parseRadius(null).isEmpty());
    }

    @Test
    void queuedFeedbackMatchesTheCommand() {
        AdminActions.Outcome some = AdminActions.queued(4);
        assertTrue(some.success());
        assertEquals(List.of("regenerate.queued"), some.lines().stream().map(AdminActions.Line::key).toList());
        assertEquals(List.of("count", 4), List.of(some.lines().getFirst().placeholders()));
        AdminActions.Outcome none = AdminActions.queued(0);
        assertEquals(List.of("regenerate.queued", "regenerate.nothing-queued"),
                none.lines().stream().map(AdminActions.Line::key).toList());
    }
}
