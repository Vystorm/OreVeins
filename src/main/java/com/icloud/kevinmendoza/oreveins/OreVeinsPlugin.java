/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class OreVeinsPlugin extends JavaPlugin implements Listener {
    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "regenerate");
    private static final List<String> REGENERATE_TARGETS = List.of("all", "cancel", "loaded", "chunk", "8");

    private volatile GeneratorSettings settings;
    private volatile Messages messages = Messages.bundled("auto");
    private FileConfiguration configuration;
    private VeinPopulator populator;
    private ExistingChunkRetrofitter retrofitter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource(ConfigFiles.LEGACY_DEFAULTS_RESOURCE, true);
        try {
            ConfigFiles.Loaded loaded = readConfiguration();
            messages = Messages.load(loaded.config().getString("language", "auto"),
                    getDataFolder(), this::getResource, getLogger());
            settings = GeneratorSettings.load(loaded.config(), getLogger(), messages);
            configuration = loaded.config();
            if (loaded.legacy()) {
                getLogger().info(messages.console("startup.legacy-config"));
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().severe(messages.console("startup.failed", "error", exception.getMessage()));
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        populator = new VeinPopulator(() -> settings);
        retrofitter = new ExistingChunkRetrofitter(this, () -> settings, () -> messages);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(retrofitter, this);
        for (World world : Bukkit.getWorlds()) {
            attach(world);
        }
        retrofitter.start();
        getLogger().info(messages.console("startup.loaded", "count", settings.veins().size()));
        getLogger().info(messages.console(settings.retrofitExistingChunks()
                ? "startup.retrofit-on" : "startup.retrofit-off", "version", settings.retrofitVersion()));
    }

    @Override
    public void onDisable() {
        if (retrofitter != null) {
            retrofitter.stop();
        }
        if (populator == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.getPopulators().remove(populator);
        }
    }

    /**
     * Returns the configuration read by {@link ConfigFiles}; Bukkit's own
     * loader would attach the wrong defaults to configs written before 2.6.0.
     */
    @Override
    public @NotNull FileConfiguration getConfig() {
        if (configuration == null) {
            try {
                configuration = readConfiguration().config();
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }
        return configuration;
    }

    @Override
    public void reloadConfig() {
        configuration = null;
    }

    private ConfigFiles.Loaded readConfiguration() throws IOException, InvalidConfigurationException {
        return ConfigFiles.load(new File(getDataFolder(), "config.yml"), this::getResource);
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        attach(event.getWorld());
    }

    private void attach(World world) {
        GeneratorSettings current = settings;
        if (populator == null || current == null || !current.enables(world.getName(), world.getEnvironment())) {
            return;
        }
        List<BlockPopulator> populators = world.getPopulators();
        // PlugMan reloads use a new class loader. Remove an instance left by an
        // older OreVeins version before attaching the current populator.
        populators.removeIf(existing -> existing != populator
                && existing.getClass().getName().equals(VeinPopulator.class.getName()));
        if (!populators.contains(populator)) {
            populators.add(populator);
            getLogger().info(messages.console("startup.attached", "world", world.getName(),
                    "min", world.getMinHeight(), "max", world.getMaxHeight() - 1));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        Messages text = messages;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                ConfigFiles.Loaded loaded = readConfiguration();
                Messages newMessages = Messages.load(loaded.config().getString("language", "auto"),
                        getDataFolder(), this::getResource, getLogger());
                GeneratorSettings newSettings = GeneratorSettings.load(loaded.config(), getLogger(), newMessages);
                configuration = loaded.config();
                messages = newMessages;
                settings = newSettings;
                for (World world : Bukkit.getWorlds()) {
                    attach(world);
                }
                retrofitter.enqueueLoadedChunks();
                newMessages.send(sender, "reload.done", "count", newSettings.veins().size());
            } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
                text.send(sender, "reload.failed", "error", exception.getMessage());
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            GeneratorSettings current = settings;
            String worlds = current.enabledWorlds().isEmpty() ? text.get(sender, "status.all-worlds")
                    : String.join(", ", current.enabledWorlds());
            text.send(sender, "status.summary",
                    "passes", current.veins().size(),
                    "replace", current.replaceExistingOres(),
                    "retrofit", current.retrofitExistingChunks(),
                    "processed", retrofitter.processedChunks(),
                    "manual", retrofitter.manuallyRegeneratedChunks(),
                    "queued", retrofitter.queuedChunks(),
                    "scanning", retrofitter.allScanRunning(),
                    "worlds", worlds);
            if (retrofitter.campaignActive()) {
                text.send(sender, "status.regenerate-all",
                        "done", retrofitter.campaignDone(), "remaining", retrofitter.campaignRemaining());
            }
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("regenerate")) {
            return handleRegenerate(sender, args, text);
        }
        text.send(sender, "usage", "label", label);
        return true;
    }

    private boolean handleRegenerate(CommandSender sender, String[] args, Messages text) {
        int added;
        if (args.length == 2 && args[1].equalsIgnoreCase("loaded")) {
            added = retrofitter.regenerateLoadedChunks();
        } else if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            boolean started = retrofitter.regenerateAllChunks(result -> {
                text.send(sender, "regenerate.all.scan-finished",
                        "worlds", result.worlds(), "found", result.foundChunks(),
                        "queued", result.queuedChunks(), "failed", result.failedRegionFiles());
                text.send(sender, "regenerate.all.no-new-chunks");
            }, false);
            text.send(sender, started ? "regenerate.all.scanning" : "regenerate.all.already-running");
            return true;
        } else if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            text.send(sender, retrofitter.cancelCampaign() ? "regenerate.cancel.done" : "regenerate.cancel.none");
            return true;
        } else if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                text.send(sender, "regenerate.radius.player-only");
                return true;
            }
            int radius;
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                text.send(sender, "regenerate.radius.not-a-number");
                return true;
            }
            if (radius < 0 || radius > 32) {
                text.send(sender, "regenerate.radius.out-of-range");
                return true;
            }
            added = retrofitter.regenerateNearbyChunks(player.getWorld(),
                    player.getLocation().getBlockX() >> 4,
                    player.getLocation().getBlockZ() >> 4, radius);
        } else if (args.length == 5 && args[1].equalsIgnoreCase("chunk")) {
            World world = Bukkit.getWorld(args[2]);
            if (world == null) {
                text.send(sender, "regenerate.chunk.unknown-world", "world", args[2]);
                return true;
            }
            int chunkX;
            int chunkZ;
            try {
                chunkX = Integer.parseInt(args[3]);
                chunkZ = Integer.parseInt(args[4]);
            } catch (NumberFormatException exception) {
                text.send(sender, "regenerate.chunk.bad-coordinates");
                return true;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                text.send(sender, "regenerate.chunk.not-loaded");
                return true;
            }
            added = retrofitter.regenerateChunk(world.getChunkAt(chunkX, chunkZ)) ? 1 : 0;
        } else {
            text.send(sender, "usage", "label", "oreveins");
            return true;
        }
        text.send(sender, "regenerate.queued", "count", added);
        if (added == 0) {
            text.send(sender, "regenerate.nothing-queued");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        List<String> options;
        if (args.length == 1) {
            options = SUBCOMMANDS;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("regenerate")) {
            options = REGENERATE_TARGETS;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("regenerate") && args[1].equalsIgnoreCase("chunk")) {
            options = Bukkit.getWorlds().stream().map(World::getName).toList();
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
