package com.leoli.gateway.filter.token;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

/** Bounded local outbox. Only acknowledged records are removed; one process owns a directory. */
final class TokenUsageOutbox implements AutoCloseable {
    static final int MAX_RECORD_BYTES = 16384;
    record Entry(Path path, byte[] bytes) { }
    private final Path directory;
    private final long maxBytes;
    private final int maxRecords;
    private final NavigableMap<String, Long> pending = new TreeMap<>();
    private final Set<String> active = new HashSet<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private long bytes;
    private boolean closed;

    TokenUsageOutbox(Path directory, long maxBytes, int maxRecords) throws IOException {
        if (maxBytes < MAX_RECORD_BYTES || maxRecords < 1) throw new IllegalArgumentException("Invalid usage outbox limits");
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.maxRecords = maxRecords;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Usage outbox directory already in use: " + directory);
            lock = acquired;
            try (var files = Files.newDirectoryStream(directory, "*.pending")) {
                for (Path file : files) {
                    long size = Files.size(file);
                    if (size < 1 || size > MAX_RECORD_BYTES) throw new IOException("Invalid usage outbox record: " + file);
                    pending.put(file.getFileName().toString(), size);
                    bytes += size;
                }
            }
            // A process died after admission but before final usage was durable. Export the
            // UNKNOWN admission snapshot for review, never silently treat it as zero usage.
            try (var files = Files.newDirectoryStream(directory, "*.active")) {
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".active", ".pending");
                    if (pending.containsKey(name)) { Files.delete(file); continue; }
                    long size = Files.size(file);
                    if (size < 1 || size > MAX_RECORD_BYTES) throw new IOException("Invalid active usage record: " + file);
                    Files.move(file, directory.resolve(name));
                    pending.put(name, size);
                    bytes += size;
                }
            }
            // Temp files never became accepted records. The exclusive lock makes cleanup safe.
            try (var files = Files.newDirectoryStream(directory, "*.tmp")) {
                for (Path file : files) Files.delete(file);
            }
        } catch (Exception error) {
            if (acquired != null) acquired.release();
            lockChannel.close();
            throw error;
        }
    }

    synchronized void append(byte[] payload) throws IOException {
        checkOpen();
        if (payload.length > MAX_RECORD_BYTES || bytes + (long) active.size() * MAX_RECORD_BYTES + payload.length > maxBytes
                || pending.size() + active.size() >= maxRecords)
            throw new IOException("Usage outbox capacity exceeded");
        String name = String.format(Locale.ROOT, "%019d-%s", System.currentTimeMillis(), UUID.randomUUID());
        write(name, ".pending", payload);
        pending.put(name + ".pending", (long) payload.length);
        bytes += payload.length;
    }

    synchronized String begin(byte[] unknownSnapshot) throws IOException {
        checkOpen();
        if (unknownSnapshot.length > MAX_RECORD_BYTES || pending.size() + active.size() >= maxRecords
                || bytes + (long) (active.size() + 1) * MAX_RECORD_BYTES > maxBytes)
            throw new IOException("Usage outbox capacity exceeded; refusing unmetered upstream request");
        String name = String.format(Locale.ROOT, "%019d-%s", System.currentTimeMillis(), UUID.randomUUID());
        write(name, ".active", unknownSnapshot);
        active.add(name);
        return name;
    }

    synchronized void complete(String name, byte[] payload) throws IOException {
        checkOpen();
        if (!active.contains(name)) throw new IOException("Usage admission not found");
        if (payload.length > MAX_RECORD_BYTES) throw new IOException("Usage record too large");
        // Capacity was reserved BEFORE the model call. A full queue cannot reject its result.
        write(name, ".pending", payload);
        pending.put(name + ".pending", (long) payload.length);
        bytes += payload.length;
        active.remove(name);
        // If deletion fails, recovery prefers the finalized .pending snapshot.
        try { Files.deleteIfExists(directory.resolve(name + ".active")); } catch (IOException ignored) { }
    }

    private void write(String name, String extension, byte[] payload) throws IOException {
        Path temporary = directory.resolve(name + ".tmp");
        Path target = directory.resolve(name + extension);
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target); }
        } finally { Files.deleteIfExists(temporary); }
    }

    synchronized List<Entry> batch(int limit) throws IOException {
        checkOpen();
        List<Entry> result = new ArrayList<>();
        for (String name : pending.navigableKeySet()) {
            if (result.size() == limit) break;
            Path path = directory.resolve(name);
            result.add(new Entry(path, Files.readAllBytes(path)));
        }
        return result;
    }

    synchronized void acknowledge(List<Entry> entries) throws IOException {
        checkOpen();
        for (Entry entry : entries) {
            String name = entry.path().getFileName().toString();
            Long size = pending.get(name);
            if (size != null) {
                Files.deleteIfExists(directory.resolve(name.replace(".pending", ".active")));
                Files.deleteIfExists(entry.path());
                pending.remove(name);
                bytes -= size;
            }
        }
    }

    synchronized long bytes() { return bytes + (long) active.size() * MAX_RECORD_BYTES; }
    synchronized int size() { return pending.size() + active.size(); }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Usage outbox closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
