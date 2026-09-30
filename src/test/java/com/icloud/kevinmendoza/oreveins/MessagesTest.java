/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2014 Kevin Mendoza
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessagesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z]+}");
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\"((?:startup|config|reload|status|regenerate|retrofit|scan)\\.[a-z.-]+|usage)\"");

    @Test
    void germanAndEnglishHaveTheSameKeysAndPlaceholders() {
        Messages messages = Messages.bundled("auto");
        YamlConfiguration english = messages.bundle("en");
        YamlConfiguration german = messages.bundle("de");
        Set<String> englishKeys = leafKeys(english);
        assertEquals(englishKeys, leafKeys(german));
        for (String key : englishKeys) {
            assertEquals(placeholders(english.getString(key)), placeholders(german.getString(key)), key);
        }
    }

    @Test
    void everyKeyUsedInTheCodeExists() throws IOException {
        Set<String> english = leafKeys(Messages.bundled("en").bundle("en"));
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY_LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!matcher.group(1).endsWith(".yml")) {
                        used.add(matcher.group(1));
                    }
                }
            }
        }
        assertTrue(used.size() > 30, "found only " + used);
        for (String key : used) {
            assertTrue(english.contains(key), "missing message key " + key);
        }
    }

    @Test
    void autoPicksGermanOnlyForGermanClients() {
        Messages auto = Messages.bundled("auto");
        assertEquals("de", auto.languageForLocale(Locale.GERMANY));
        assertEquals("de", auto.languageForLocale(Locale.forLanguageTag("de-AT")));
        assertEquals("en", auto.languageForLocale(Locale.US));
        assertEquals("en", auto.languageForLocale(Locale.FRANCE));
        assertEquals("en", auto.consoleLanguage());
        assertEquals("de", Messages.bundled("DE").consoleLanguage());
        assertEquals("en", Messages.bundled("en").languageForLocale(Locale.GERMANY));
        assertEquals("en", Messages.bundled("klingon").consoleLanguage());
    }

    @Test
    void formatsPlaceholdersAndPrefix() {
        Messages messages = Messages.bundled("auto");
        assertEquals("[OreVeins] Queued 3 loaded chunk(s). Only ores and configured deep source lava will be regenerated.",
                messages.format("en", "regenerate.queued", "count", 3));
        assertEquals("[OreVeins] 3 geladene(r) Chunk(s) eingereiht. Nur Erze und konfigurierte tiefe Lavaquellen werden neu erzeugt.",
                messages.format("de", "regenerate.queued", "count", 3));
        assertEquals("no.such.key", messages.format("de", "no.such.key"));
    }

    private static Set<String> leafKeys(YamlConfiguration yaml) {
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
