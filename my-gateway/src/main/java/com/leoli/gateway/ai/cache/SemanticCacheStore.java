package com.leoli.gateway.ai.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * In-memory semantic cache store: bounded, LRU-evicting, TTL-expiring, brute-force
 * cosine search over L2-normalized vectors (the same approach as the control plane's
 * VectorIndex, scoped to cache entries). Per-instance and non-persistent for this MVP;
 * a shared store (Redis-Stack / external vector DB) is a follow-up.
 * <p>
 * Searches hold only their scope shard lock. Retained strings and vectors share an
 * instance-wide byte and entry budget; eviction is local to the admitting shard.
 *
 * @author leoli
 */
@Slf4j
@Component
public class SemanticCacheStore {

    @Value("${gateway.ai.cache.max-entries:1000}")
    private int maxEntries;

    @Value("${gateway.ai.cache.max-bytes:33554432}")
    private long maxBytes = 32L * 1024 * 1024;
    private static final int SHARDS = 16;
    private final Shard[] shards = java.util.stream.IntStream.range(0, SHARDS)
            .mapToObj(i -> new Shard()).toArray(Shard[]::new);
    private static final class Shard { final Deque<Entry> entries = new ArrayDeque<>(); }
    private final com.leoli.gateway.util.ByteBudget bytes = new com.leoli.gateway.util.ByteBudget();
    private final java.util.concurrent.atomic.AtomicInteger entryCount = new java.util.concurrent.atomic.AtomicInteger();
    private int shardIndex(String scope) { return Math.floorMod(scope.hashCode(), SHARDS); }
    private void retire(Entry e) { e.lease.close(); entryCount.decrementAndGet(); }
    private void removeFirst(Shard shard) { Entry e = shard.entries.pollFirst(); if (e != null) retire(e); }

    public Optional<String> search(String scope, float[] query, double threshold) {
        if (scope == null || query == null || query.length == 0) return Optional.empty();
        float[] q = normalize(query.clone());
        Shard shard = shards[shardIndex(scope)];
        synchronized (shard) {
            long now = System.currentTimeMillis();
            Entry best = null; double bestScore = -1;
            Iterator<Entry> it = shard.entries.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.expiresAtMillis < now) { it.remove(); retire(e); continue; }
                if (!e.scope.equals(scope) || e.vector.length != q.length) continue;
                double score = dot(e.vector, q);
                if (score > bestScore) { bestScore = score; best = e; }
            }
            if (best != null && bestScore >= threshold) {
                shard.entries.remove(best); shard.entries.addLast(best);
                return Optional.of(best.response);
            }
            return Optional.empty();
        }
    }

    public void put(String scope, float[] vector, String response, long ttlSeconds) {
        if (vector == null || vector.length == 0 || response == null || scope == null) return;
        int index = shardIndex(scope);
        int cap = maxEntries > 0 ? maxEntries : 1000;
        long entryBytes = 128L + 2L * scope.length() + 2L * response.length() + 4L * vector.length;
        if (entryBytes > maxBytes) return;
        float[] normalized = normalize(vector.clone());
        Shard shard = shards[index];
        synchronized (shard) {
            while (true) {
                int count = entryCount.get();
                if (count < cap) {
                    if (!entryCount.compareAndSet(count, count + 1)) continue;
                    var lease = bytes.tryAcquire(entryBytes, maxBytes);
                    if (lease != null) {
                        shard.entries.addLast(new Entry(scope, normalized, response,
                                System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000L, lease));
                        return;
                    }
                    entryCount.decrementAndGet();
                }
                // Evict within this shard; never wait on a different tenant's search lock.
                if (shard.entries.isEmpty()) return;
                removeFirst(shard);
            }
        }
    }
    public int size() { return entryCount.get(); }
    public long retainedBytes() { return bytes.used(); }
    public void clear() {
        for (Shard shard : shards) synchronized (shard) {
            while (!shard.entries.isEmpty()) removeFirst(shard);
        }
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float f : v) {
            sum += (double) f * f;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    private static final class Entry {
        final String scope;
        final float[] vector;
        final String response;
        final long expiresAtMillis;

        final com.leoli.gateway.util.ByteBudget.Lease lease;
        Entry(String scope, float[] vector, String response, long expiresAtMillis, com.leoli.gateway.util.ByteBudget.Lease lease) {
            this.lease = lease;
            this.scope = scope;
            this.vector = vector;
            this.response = response;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
