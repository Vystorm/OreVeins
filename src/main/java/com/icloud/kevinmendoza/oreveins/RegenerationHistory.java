/*
 * OreVeins - ore vein generation for Paper/Purpur
 * Copyright (C) 2026 Esoren
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.icloud.kevinmendoza.oreveins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Informational, bounded history of regeneration runs; it is never a retry checkpoint. */
final class RegenerationHistory {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 100;
    static final int MAX_ACTIVE = 32;

    enum Status { ACTIVE, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

    record Entry(UUID id, String actor, String scope, long startedMillis, long endedMillis,
                 long queued, long completed, long failed, long skipped, long cancelled, Status status) {}

    private final Path file;
    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private boolean readable = true;
    private boolean dirty;

    RegenerationHistory(Path file) {
        this(file, Clock.systemUTC());
    }

    RegenerationHistory(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    synchronized boolean readable() { return readable; }
    synchronized boolean dirty() { return dirty; }
    synchronized List<Entry> snapshot() { return List.copyOf(entries); }

    synchronized UUID begin(String actor, String scope) {
        if (!readable || activeCount() >= MAX_ACTIVE || !validText(actor, 128) || !validText(scope, 256)) return null;
        pruneForOneMore();
        if (entries.size() >= MAX_ENTRIES) return null;
        UUID id = UUID.randomUUID();
        long now = clock.millis();
        entries.add(new Entry(id, actor, scope, now, 0, 0, 0, 0, 0, 0, Status.ACTIVE));
        dirty = true;
        return id;
    }

    synchronized boolean queued(UUID id, long count) { return add(id, Counter.QUEUED, count); }
    synchronized boolean completed(UUID id) { return add(id, Counter.COMPLETED, 1); }
    synchronized boolean failed(UUID id) { return add(id, Counter.FAILED, 1); }
    synchronized boolean skipped(UUID id) { return add(id, Counter.SKIPPED, 1); }
    synchronized boolean cancelled(UUID id, long count) { return add(id, Counter.CANCELLED, count); }

    synchronized boolean finish(UUID id, Status status) {
        if (!readable || status == null || status == Status.ACTIVE) return false;
        int index = indexOf(id);
        if (index < 0 || entries.get(index).status() != Status.ACTIVE) return false;
        Entry old = entries.get(index);
        entries.set(index, new Entry(old.id(), old.actor(), old.scope(), old.startedMillis(), clock.millis(),
                old.queued(), old.completed(), old.failed(), old.skipped(), old.cancelled(), status));
        dirty = true;
        return true;
    }

    synchronized void flush() throws IOException {
        if (!readable) throw new IOException("Regeneration history is unreadable");
        if (!dirty) return;
        Properties properties = new Properties();
        properties.setProperty("schema", "1");
        properties.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "id", entry.id().toString());
            properties.setProperty(prefix + "actor", entry.actor());
            properties.setProperty(prefix + "scope", entry.scope());
            properties.setProperty(prefix + "started", Long.toString(entry.startedMillis()));
            properties.setProperty(prefix + "ended", Long.toString(entry.endedMillis()));
            properties.setProperty(prefix + "queued", Long.toString(entry.queued()));
            properties.setProperty(prefix + "completed", Long.toString(entry.completed()));
            properties.setProperty(prefix + "failed", Long.toString(entry.failed()));
            properties.setProperty(prefix + "skipped", Long.toString(entry.skipped()));
            properties.setProperty(prefix + "cancelled", Long.toString(entry.cancelled()));
            properties.setProperty(prefix + "status", entry.status().name());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "OreVeins regeneration history; informational only, not a retry checkpoint");
        byte[] serialized = bytes.toByteArray();
        if (serialized.length > MAX_BYTES) throw new IOException("Regeneration history exceeds size limit");

        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "regeneration-history-", ".tmp");
        try {
            Files.write(temporary, serialized);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("oversize");
            Properties properties = new Properties();
            try (ByteArrayInputStream input = new ByteArrayInputStream(Files.readAllBytes(file))) {
                properties.load(input);
            }
            if (!"1".equals(properties.getProperty("schema"))) throw new IOException("schema");
            int count = parseInt(properties.getProperty("count"));
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("count");
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                Entry entry = parseEntry(properties, prefix);
                if (entries.stream().anyMatch(existing -> existing.id().equals(entry.id()))) throw new IOException("duplicate id");
                entries.add(entry);
            }
            if (properties.size() != 2 + count * 11) throw new IOException("unexpected fields");
            int active = activeCount();
            if (active > MAX_ACTIVE) throw new IOException("active cap");
            long now = clock.millis();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.status() == Status.ACTIVE) {
                    entries.set(i, new Entry(entry.id(), entry.actor(), entry.scope(), entry.startedMillis(), now,
                            entry.queued(), entry.completed(), entry.failed(), entry.skipped(), entry.cancelled(), Status.INTERRUPTED));
                    dirty = true;
                }
            }
        } catch (IOException | RuntimeException malformed) {
            entries.clear();
            dirty = false;
            readable = false;
        }
    }

    private static Entry parseEntry(Properties p, String prefix) throws IOException {
        UUID id = UUID.fromString(required(p, prefix + "id"));
        String actor = required(p, prefix + "actor");
        String scope = required(p, prefix + "scope");
        long started = parseLong(required(p, prefix + "started"));
        long ended = parseLong(required(p, prefix + "ended"));
        long queued = parseLong(required(p, prefix + "queued"));
        long completed = parseLong(required(p, prefix + "completed"));
        long failed = parseLong(required(p, prefix + "failed"));
        long skipped = parseLong(required(p, prefix + "skipped"));
        long cancelled = parseLong(required(p, prefix + "cancelled"));
        Status status = Status.valueOf(required(p, prefix + "status"));
        if (!validText(actor, 128) || !validText(scope, 256) || started < 0 || ended < 0
                || queued < 0 || completed < 0 || failed < 0 || skipped < 0 || cancelled < 0
                || (status == Status.ACTIVE && ended != 0)) throw new IOException("invalid entry");
        return new Entry(id, actor, scope, started, ended, queued, completed, failed, skipped, cancelled, status);
    }

    private boolean add(UUID id, Counter counter, long count) {
        if (!readable || count < 0) return false;
        int index = indexOf(id);
        if (index < 0) return false;
        Entry old = entries.get(index);
        if (old.status() != Status.ACTIVE) return false;
        long current = counter.value(old);
        if (count > Long.MAX_VALUE - current) return false;
        entries.set(index, counter.replace(old, current + count));
        dirty = true;
        return true;
    }

    private int activeCount() { return (int) entries.stream().filter(e -> e.status() == Status.ACTIVE).count(); }
    private int indexOf(UUID id) {
        if (id == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) return i;
        return -1;
    }
    private void pruneForOneMore() {
        while (entries.size() >= MAX_ENTRIES) {
            int oldest = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).status() != Status.ACTIVE && (oldest < 0
                        || entries.get(i).startedMillis() < entries.get(oldest).startedMillis())) oldest = i;
            }
            if (oldest < 0) return;
            entries.remove(oldest);
            dirty = true;
        }
    }
    private static boolean validText(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) throw new IOException("missing " + key);
        return value;
    }
    private static int parseInt(String value) throws IOException {
        try { return Integer.parseInt(value); } catch (RuntimeException ex) { throw new IOException("integer", ex); }
    }
    private static long parseLong(String value) throws IOException {
        try { return Long.parseLong(value); } catch (RuntimeException ex) { throw new IOException("long", ex); }
    }

    private enum Counter {
        QUEUED { long value(Entry e) { return e.queued(); } Entry replace(Entry e, long n) { return copy(e, n, e.completed(), e.failed(), e.skipped(), e.cancelled()); } },
        COMPLETED { long value(Entry e) { return e.completed(); } Entry replace(Entry e, long n) { return copy(e, e.queued(), n, e.failed(), e.skipped(), e.cancelled()); } },
        FAILED { long value(Entry e) { return e.failed(); } Entry replace(Entry e, long n) { return copy(e, e.queued(), e.completed(), n, e.skipped(), e.cancelled()); } },
        SKIPPED { long value(Entry e) { return e.skipped(); } Entry replace(Entry e, long n) { return copy(e, e.queued(), e.completed(), e.failed(), n, e.cancelled()); } },
        CANCELLED { long value(Entry e) { return e.cancelled(); } Entry replace(Entry e, long n) { return copy(e, e.queued(), e.completed(), e.failed(), e.skipped(), n); } };
        abstract long value(Entry entry);
        abstract Entry replace(Entry entry, long value);
        static Entry copy(Entry e, long queued, long completed, long failed, long skipped, long cancelled) {
            return new Entry(e.id(), e.actor(), e.scope(), e.startedMillis(), e.endedMillis(), queued, completed, failed, skipped, cancelled, e.status());
        }
    }
}
