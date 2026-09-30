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
import java.util.OptionalInt;
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
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class OreVeinsPlugin extends JavaPlugin implements Listener {
    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "regenerate");
    private static final List<String> SUBCOMMANDS_WITH_WEB = List.of("reload", "status", "regenerate", "web");
    private static final List<String> REGENERATE_TARGETS = List.of("all", "cancel", "loaded", "chunk", "8");

    private volatile GeneratorSettings settings;
    private volatile Messages messages = Messages.bundled("auto");
    private FileConfiguration configuration;
    private VeinPopulator populator;
    private ExistingChunkRetrofitter retrofitter;
    private AdminActions actions;
    private volatile boolean legacyConfig;
    private volatile WebIntegration web;

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
            legacyConfig = loaded.legacy();
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
        actions = new AdminActions(() -> settings, retrofitter, this::reloadSettings);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(retrofitter, this);
        for (World world : Bukkit.getWorlds()) {
            attach(world);
        }
        retrofitter.start();
        getLogger().info(messages.console("startup.loaded", "count", settings.veins().size()));
        getLogger().info(messages.console(settings.retrofitExistingChunks()
                ? "startup.retrofit-on" : "startup.retrofit-off", "version", settings.retrofitVersion()));
        web = WebIntegration.load(this, actions);
    }

    @Override
    public void onDisable() {
        WebIntegration current = web;
        web = null;
        if (current != null) {
            current.stop();
        }
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

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        WebIntegration current = web;
        if (current != null) {
            current.worldsChanged();
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        WebIntegration current = web;
        if (current != null) {
            current.worldsChanged();
        }
    }

    /**
     * {@code /oreveins reload}: reads config.yml and the language files again.
     * On any error the previous settings stay active.
     */
    AdminActions.Outcome reloadSettings() {
        try {
            ConfigFiles.Loaded loaded = readConfiguration();
            Messages newMessages = Messages.load(loaded.config().getString("language", "auto"),
                    getDataFolder(), this::getResource, getLogger());
            GeneratorSettings newSettings = GeneratorSettings.load(loaded.config(), getLogger(), newMessages);
            configuration = loaded.config();
            legacyConfig = loaded.legacy();
            messages = newMessages;
            settings = newSettings;
            for (World world : Bukkit.getWorlds()) {
                attach(world);
            }
            retrofitter.enqueueLoadedChunks();
            WebIntegration current = web;
            if (current != null) {
                current.reloaded();
            }
            return AdminActions.Outcome.ok(new AdminActions.Line("reload.done", "count", newSettings.veins().size()));
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            return AdminActions.Outcome.failed(new AdminActions.Line("reload.failed", "error", exception.getMessage()));
        }
    }

    Messages messages() {
        return messages;
    }

    GeneratorSettings settings() {
        return settings;
    }

    boolean legacyConfig() {
        return legacyConfig;
    }

    String languageSetting() {
        return getConfig().getString("language", "auto");
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
            AdminActions.Outcome outcome = actions.reload();
            send(sender, messages, outcome);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            send(sender, text, actions.status(text.get(sender, "status.all-worlds")));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("web")) {
            openWeb(sender, text);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("regenerate")) {
            return handleRegenerate(sender, args, text);
        }
        text.send(sender, "usage", "label", label);
        return true;
    }

    private boolean handleRegenerate(CommandSender sender, String[] args, Messages text) {
        if (args.length == 2 && args[1].equalsIgnoreCase("loaded")) {
            send(sender, text, actions.regenerateLoaded());
        } else if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
            send(sender, text, actions.regenerateAll(finished -> send(sender, text, finished)));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            send(sender, text, actions.cancel());
        } else if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                text.send(sender, "regenerate.radius.player-only");
                return true;
            }
            OptionalInt radius = AdminActions.parseRadius(args[1]);
            if (radius.isEmpty()) {
                text.send(sender, "regenerate.radius.not-a-number");
                return true;
            }
            send(sender, text, actions.regenerateRadius(player, radius.getAsInt()));
        } else if (args.length == 5 && args[1].equalsIgnoreCase("chunk")) {
            send(sender, text, actions.regenerateChunk(args[2], args[3], args[4]));
        } else {
            text.send(sender, "usage", "label", "oreveins");
        }
        return true;
    }

    private static void send(CommandSender sender, Messages text, AdminActions.Outcome outcome) {
        for (AdminActions.Line line : outcome.lines()) {
            text.send(sender, line.key(), line.placeholders());
        }
    }

    private void openWeb(CommandSender sender, Messages text) {
        WebIntegration current = web;
        if (current == null) {
            text.send(sender, "web.unavailable");
        } else if (!(sender instanceof Player player)) {
            text.send(sender, "web.player-only");
        } else {
            current.open(player);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        List<String> options;
        if (args.length == 1) {
            options = web != null ? SUBCOMMANDS_WITH_WEB : SUBCOMMANDS;
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
