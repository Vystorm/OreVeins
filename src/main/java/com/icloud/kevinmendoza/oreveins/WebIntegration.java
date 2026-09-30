/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * Optional web page (ore guide and admin panel) on another plugin's web
 * platform. OreVeins itself never needs it: the implementation lives in a
 * separate source set that is only compiled when its platform jar is available
 * at build time, and it is only loaded when that platform runs on the server.
 * Without it every call site simply sees {@code null}.
 */
interface WebIntegration {
    /** Plugin name of Vystorm Core, the only platform supported so far. */
    String CORE_PLUGIN = "vystorm_core";

    /** Implemented by the optional integration and found with {@link ServiceLoader}. */
    interface Factory {
        /** Returns the started integration, or {@code null} when the platform lacks what it needs. */
        WebIntegration start(OreVeinsPlugin plugin, AdminActions actions);
    }

    /** Called after {@code /oreveins reload} succeeded (server thread). */
    void reloaded();

    /** Called when a world was loaded or unloaded (server thread). */
    void worldsChanged();

    /** {@code /oreveins web}: opens the page in the overlay or sends a link (server thread). */
    void open(Player player);

    /** Unregisters everything (server thread, plugin disable). */
    void stop();

    /**
     * Starts the integration when Vystorm Core is enabled and the integration
     * is part of this jar; otherwise returns {@code null} without side effects.
     */
    static WebIntegration load(OreVeinsPlugin plugin, AdminActions actions) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled(CORE_PLUGIN)) {
            return null;
        }
        try {
            for (Factory factory : ServiceLoader.load(Factory.class, OreVeinsPlugin.class.getClassLoader())) {
                WebIntegration integration = factory.start(plugin, actions);
                if (integration != null) {
                    return integration;
                }
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, plugin.messages().console("web.start-failed",
                    "error", String.valueOf(failure)), failure);
        }
        return null;
    }
}
