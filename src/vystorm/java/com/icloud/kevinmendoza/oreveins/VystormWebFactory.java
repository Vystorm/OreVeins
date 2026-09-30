/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import Vystorm.vystorm_core.CoreApi;

/**
 * Entry point of the optional Vystorm Core web page, found through
 * {@code META-INF/services}. OreVeins only asks for it when the plugin
 * {@code vystorm_core} is enabled; it returns {@code null} for Core versions
 * older than 0.24.0 or without the web capabilities.
 */
public final class VystormWebFactory implements WebIntegration.Factory {
    static final String MINIMUM_CORE = "0.24.0";

    public VystormWebFactory() {
    }

    @Override
    public WebIntegration start(OreVeinsPlugin plugin, AdminActions actions) {
        boolean supported;
        try {
            supported = CoreApi.atLeast(MINIMUM_CORE)
                    && CoreApi.has(CoreApi.Capability.WEB)
                    && CoreApi.has(CoreApi.Capability.WEB_LIVE)
                    && CoreApi.has(CoreApi.Capability.WEB_OPEN)
                    && CoreApi.has(CoreApi.Capability.LOCALIZATION)
                    && CoreApi.has(CoreApi.Capability.WEB_LOCALIZATION)
                    && CoreApi.api().isPresent();
        } catch (LinkageError tooOld) {
            supported = false;
        }
        if (!supported) {
            plugin.getLogger().info(plugin.messages().console("web.core-too-old", "version", MINIMUM_CORE));
            return null;
        }
        VystormWeb web = new VystormWeb(plugin, actions);
        web.start();
        return web;
    }
}
