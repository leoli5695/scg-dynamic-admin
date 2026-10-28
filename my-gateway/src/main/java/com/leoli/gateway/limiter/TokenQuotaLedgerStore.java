package com.leoli.gateway.limiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Atomic, bounded snapshots. Updating an admitted record always has reserved disk headroom. */
final class TokenQuotaLedgerStore implements AutoCloseable {
    private static final int RECORD_BYTES = 16384;
    private final Path directory;
    private final int capacity;
    private final ObjectMapper mapper;
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String, TokenQuotaLedger.Entry> entries = new LinkedHashMap<>();
    record Baseline(String key,long used,long observedAt,long expiresAt) { }
    private final Map<String,Baseline> baselines = new LinkedHashMap<>(16,0.75f,true);
    private boolean closed;

    TokenQuotaLedgerStore(Path directory, int capacity, ObjectMapper mapper) throws IOException {
        if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("Ledger capacity must be 1..100000");
        this.directory = directory; this.capacity = capacity; this.mapper = mapper;
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Quota ledger already owned by another process");
            lock = acquired;
            try (var files = Files.newDirectoryStream(directory,"*.json")) {
                for (Path file : files) {
                    if (Files.size(file) > RECORD_BYTES) throw new IOException("Oversized quota ledger record: " + file);
                    var entry = mapper.readValue(Files.readAllBytes(file),TokenQuotaLedger.Entry.class);
                    if (entry.id == null || !file.getFileName().toString().equals(UUID.fromString(entry.id) + ".json"))
                        throw new IOException("Invalid quota ledger identity: " + file);
                    TokenQuotaLedger.validateEntry(entry);
                    entries.put(entry.id,entry);
                    if (entries.size() > capacity) throw new IOException("Increase ledger capacity to recover existing entries");
                }
            }
            try (var files = Files.newDirectoryStream(directory,"*.tmp")) { for (Path file : files) Files.delete(file); }
            try (var files = Files.newDirectoryStream(directory,"*.baseline")) {
                for (Path file : files) {
                    if (Files.size(file) > 1024) throw new IOException("Oversized quota baseline");
                    Baseline baseline = mapper.readValue(Files.readAllBytes(file),Baseline.class);
                    if (baseline.key() == null || baseline.used() < 0 || baseline.used() > 9007199254740991L
                            || !file.getFileName().toString().equals(baselineName(baseline.key())))
                        throw new IOException("Invalid quota baseline");
                    baselines.put(baseline.key(),baseline);
                    if (baselines.size() > baselineCapacity()) throw new IOException("Increase ledger capacity to recover quota baselines");
                }
            }
        } catch (Exception error) {
            if (acquired != null) acquired.release();
            channel.close(); throw error;
        }
    }

    synchronized TokenQuotaLedger.Entry get(String id) {
        return entries.containsKey(id) ? mapper.convertValue(entries.get(id),TokenQuotaLedger.Entry.class) : null;
    }
    synchronized List<TokenQuotaLedger.Entry> all() {
        return entries.values().stream().map(e -> mapper.convertValue(e,TokenQuotaLedger.Entry.class)).toList();
    }
    synchronized int size() { return entries.size(); }
    synchronized Baseline baseline(String key,long now) {
        Baseline baseline = baselines.get(key);
        return baseline != null && baseline.expiresAt() > now ? baseline : null;
    }
    synchronized void saveBaseline(Baseline baseline) throws IOException {
        if (closed) throw new IOException("Quota ledger closed");
        if (!baselines.containsKey(baseline.key()) && baselines.size() >= baselineCapacity()) {
            String oldest = baselines.keySet().iterator().next();
            Files.deleteIfExists(directory.resolve(baselineName(oldest))); baselines.remove(oldest);
        }
        write(baselineName(baseline.key()),mapper.writeValueAsBytes(baseline));
        baselines.put(baseline.key(),baseline);
    }
    private int baselineCapacity() { return Math.max(10000,capacity * 2); }
    private String baselineName(String key) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".baseline"; }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    record Pending(long monthly,long daily,boolean uncertain) { }
    synchronized long localSize() { return entries.values().stream().filter(e -> "LOCAL".equals(e.backend) && !"DELIVERING".equals(e.phase)).count(); }
    synchronized Pending pending(String tenant,String month,String day) {
        long monthly = 0,daily = 0; boolean uncertain = false;
        for (var e : entries.values()) {
            if (!tenant.equals(e.tenant) || "DELIVERING".equals(e.phase)) continue;
            if ("REDIS".equals(e.backend)) { if (!"ADMITTED".equals(e.phase)) uncertain = true; continue; }
            long charged = e.target != null ? e.target : e.reserved;
            if (e.monthly && month.equals(e.month)) monthly = Math.addExact(monthly,charged);
            if (e.daily && day.equals(e.day)) daily = Math.addExact(daily,charged);
        }
        return new Pending(monthly,daily,uncertain);
    }
    synchronized void save(TokenQuotaLedger.Entry entry) throws IOException {
        if (closed) throw new IOException("Quota ledger closed");
        UUID.fromString(entry.id);
        if (!entries.containsKey(entry.id) && entries.size() >= capacity) throw new IOException("Quota ledger capacity exceeded");
        byte[] bytes = mapper.writeValueAsBytes(entry);
        if (bytes.length > RECORD_BYTES) throw new IOException("Quota ledger record too large");
        write(entry.id + ".json",bytes);
        entries.put(entry.id, mapper.readValue(bytes,TokenQuotaLedger.Entry.class));
    }
    private void write(String name,byte[] bytes) throws IOException {
        Path temp = directory.resolve(name.replace(".json","") + ".tmp"), target = directory.resolve(name);
        try {
            try (FileChannel output = FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    synchronized void remove(String id) throws IOException {
        if (closed) throw new IOException("Quota ledger closed");
        UUID.fromString(id); Files.deleteIfExists(directory.resolve(id + ".json")); entries.remove(id);
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
