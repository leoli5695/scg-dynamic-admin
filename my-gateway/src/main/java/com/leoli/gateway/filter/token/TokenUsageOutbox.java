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
                    if (pending.size() > maxRecords || bytes > maxBytes)
                        throw new IOException("Recovered usage outbox exceeds configured limits; increase limits to drain it");
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
        if (payload.length > MAX_RECORD_BYTES || bytes + payload.length > maxBytes || pending.size() >= maxRecords)
            throw new IOException("Usage outbox capacity exceeded");
        String name = String.format(Locale.ROOT, "%019d-%s", System.currentTimeMillis(), UUID.randomUUID());
        Path temporary = directory.resolve(name + ".tmp");
        Path target = directory.resolve(name + ".pending");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target); }
            pending.put(target.getFileName().toString(), (long) payload.length);
            bytes += payload.length;
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
                Files.deleteIfExists(entry.path());
                pending.remove(name);
                bytes -= size;
            }
        }
    }

    synchronized long bytes() { return bytes; }
    synchronized int size() { return pending.size(); }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Usage outbox closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
