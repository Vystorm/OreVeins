package com.icloud.kevinmendoza.oreveins;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable bounded failure index. Entries are removed only after an explicit retry succeeds. */
final class FailedChunks {
    record Key(UUID world, int x, int z) {}
    record Failure(Key key, String reason, long time, int attempts) {}
    private final Path file;
    private Map<Key, Failure> entries = new LinkedHashMap<>();
    private boolean readable = true;
    FailedChunks(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 4_194_304) throw new IOException("oversize");
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) { properties.load(input); }
            if (!"1".equals(properties.getProperty("schema"))) throw new IOException("schema");
            for (String name : properties.stringPropertyNames()) {
                if (name.equals("schema")) continue;
                String[] parts = name.split(",", -1), value = properties.getProperty(name).split(",", -1);
                if (parts.length != 3 || value.length != 3) throw new IOException("entry");
                Key key = new Key(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                int attempts = Integer.parseInt(value[1]);
                if (attempts < 1 || !value[2].matches("[a-zA-Z0-9_-]{1,80}")) throw new IOException("entry");
                entries.put(key, new Failure(key, value[2], Long.parseLong(value[0]), attempts));
                if (entries.size() > 10_000) throw new IOException("capacity");
            }
        } catch (IOException | RuntimeException ex) { entries.clear(); readable = false; }
    }
    boolean readable() { return readable; }
    boolean contains(UUID world, int x, int z) { return entries.containsKey(new Key(world, x, z)); }
    List<Failure> snapshot() { return entries.values().stream().sorted(Comparator.comparingLong(Failure::time).reversed()).toList(); }
    void add(UUID world, int x, int z, String reason) throws IOException {
        if (!readable) throw new IOException("Failure index unreadable");
        Key key = new Key(world, x, z); Failure old = entries.get(key);
        if (old == null && entries.size() >= 10_000) throw new IOException("Failure index full");
        Map<Key, Failure> next = new LinkedHashMap<>(entries);
        next.put(key, new Failure(key, reason.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(80, reason.length())),
                System.currentTimeMillis(), old == null ? 1 : Math.min(Integer.MAX_VALUE - 1, old.attempts()) + 1));
        save(next);
    }
    void remove(UUID world, int x, int z) throws IOException {
        Key key = new Key(world, x, z); if (!entries.containsKey(key)) return;
        Map<Key, Failure> next = new LinkedHashMap<>(entries); next.remove(key); save(next);
    }
    private void save(Map<Key, Failure> next) throws IOException {
        if (!readable) throw new IOException("Failure index unreadable");
        Properties properties = new Properties(); properties.setProperty("schema", "1");
        for (Failure f : next.values()) properties.setProperty(f.key.world + "," + f.key.x + "," + f.key.z,
                f.time + "," + f.attempts + "," + f.reason);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "failed-chunks-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { properties.store(output, "OreVeins failure index; not a world backup"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            entries = next;
        } finally { Files.deleteIfExists(temporary); }
    }
}
