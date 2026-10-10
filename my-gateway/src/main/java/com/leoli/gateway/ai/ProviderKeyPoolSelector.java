package com.leoli.gateway.ai;

import com.leoli.gateway.model.AiProxyConfig;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Selects a credential ref for a provider from its configured key pool
 * (Higress ai-load-balancer counterpart at credential level).
 * <p>
 * Strategies:
 * <ul>
 *   <li>ROUND_ROBIN (default): local atomic rotation over the pool.</li>
 *   <li>LEAST_INFLIGHT: fewest ongoing requests on THIS gateway instance.
 *       Higress' global_least_request is Redis-backed across replicas; the local
 *       counter is the deliberate, event-loop-safe deviation (no blocking round
 *       trip on the routing hot path). Prefix-cache and endpoint-metrics policies
 *       are not ported.</li>
 * </ul>
 * Providers without a pool fall back to the legacy single-ref
 * {@code providerApiKeyRefs} entry.
 *
 * @author leoli
 */
@Component
public class ProviderKeyPoolSelector {

    private final Map<String, AtomicLong> roundRobin = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    /**
     * Pure selection: pool first (strategy-aware), legacy single ref otherwise.
     *
     * @return the chosen credential ref, or null when none is configured
     */
    public String selectRef(String providerId, AiProxyConfig config) {
        AiProxyConfig.KeyPool pool = poolFor(providerId, config);
        List<String> refs = poolRefs(pool);
        if (refs.isEmpty()) {
            Map<String, String> legacy = config.getProviderApiKeyRefs();
            return legacy == null ? null : legacy.get(providerId);
        }
        if (refs.size() == 1) {
            return refs.get(0);
        }
        if (isLeastInflight(pool)) {
            return refs.stream()
                    .min(Comparator.comparingInt(this::inflight).thenComparing(refs::indexOf))
                    .orElse(refs.get(0));
        }
        long idx = roundRobin.computeIfAbsent(providerId, k -> new AtomicLong()).getAndIncrement();
        return refs.get((int) Math.floorMod(idx, refs.size()));
    }

    /** Count a request as ongoing on the selected ref (LEAST_INFLIGHT pools only). */
    public void acquire(String providerId, String ref, AiProxyConfig config) {
        AiProxyConfig.KeyPool pool = poolFor(providerId, config);
        if (pool != null && isLeastInflight(pool) && poolRefs(pool).contains(ref)) {
            inflight.computeIfAbsent(ref, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Release an ongoing-request slot; no-op for refs never acquired. */
    public void release(String providerId, String ref, AiProxyConfig config) {
        AiProxyConfig.KeyPool pool = poolFor(providerId, config);
        if (pool != null && isLeastInflight(pool) && poolRefs(pool).contains(ref)) {
            AtomicInteger counter = inflight.get(ref);
            if (counter != null) {
                counter.updateAndGet(v -> v > 0 ? v - 1 : 0);
            }
        }
    }

    /** Ongoing requests on a ref (this instance). */
    public int inflight(String ref) {
        AtomicInteger counter = inflight.get(ref);
        return counter == null ? 0 : counter.get();
    }

    private AiProxyConfig.KeyPool poolFor(String providerId, AiProxyConfig config) {
        Map<String, AiProxyConfig.KeyPool> pools = config.getProviderKeyPools();
        return pools == null ? null : pools.get(providerId);
    }

    private List<String> poolRefs(AiProxyConfig.KeyPool pool) {
        if (pool == null || pool.getCredentialRefs() == null) {
            return List.of();
        }
        return pool.getCredentialRefs().stream()
                .filter(r -> r != null && !r.isBlank())
                .toList();
    }

    private boolean isLeastInflight(AiProxyConfig.KeyPool pool) {
        return pool != null && "LEAST_INFLIGHT".equalsIgnoreCase(pool.getStrategy());
    }
}
