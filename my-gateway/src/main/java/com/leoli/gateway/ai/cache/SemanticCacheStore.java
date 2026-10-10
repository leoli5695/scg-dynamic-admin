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
 * All operations are {@code synchronized} on the small entry deque; searches are
 * O(n) dot products over a few hundred-to-thousand entries (sub-ms at that scale).
 *
 * @author leoli
 */
@Slf4j
@Component
public class SemanticCacheStore {

    @Value("${gateway.ai.cache.max-entries:1000}")
    private int maxEntries;

    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Best match at or above the threshold within the scope, or empty. */
    public synchronized Optional<String> search(String scope, float[] query, double threshold) {
        if (query == null || query.length == 0) {
            return Optional.empty();
        }
        float[] q = normalize(query.clone());
        long now = System.currentTimeMillis();
        Entry best = null;
        double bestScore = -1.0;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.expiresAtMillis < now) {
                it.remove();
                continue;
            }
            if (!e.scope.equals(scope) || e.vector.length != q.length) {
                continue;
            }
            double score = dot(e.vector, q);
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best != null && bestScore >= threshold) {
            // LRU touch: move the hit to the tail.
            entries.remove(best);
            entries.addLast(best);
            log.debug("Semantic cache HIT scope={} score={}", scope, bestScore);
            return Optional.of(best.response);
        }
        return Optional.empty();
    }

    /** Store a response, evicting the oldest entry when at capacity. */
    public synchronized void put(String scope, float[] vector, String response, long ttlSeconds) {
        if (vector == null || vector.length == 0 || response == null || scope == null) {
            return;
        }
        int cap = maxEntries > 0 ? maxEntries : 1000;
        while (entries.size() >= cap) {
            entries.pollFirst();
        }
        entries.addLast(new Entry(scope, normalize(vector.clone()), response,
                System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000L));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
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

        Entry(String scope, float[] vector, String response, long expiresAtMillis) {
            this.scope = scope;
            this.vector = vector;
            this.response = response;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
