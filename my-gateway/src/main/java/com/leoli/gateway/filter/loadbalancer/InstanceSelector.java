package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Load balancing instance selection strategies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceSelector {

    // Per-service WRR selector; each group owns its own lock inside SmoothWeightedRoundRobin.
    private final Map<String, SmoothWeightedRoundRobin<ServiceInstance>> wrrSelectors = new ConcurrentHashMap<>();
    private final Map<String, CachedRing> hashRingCache = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    private final ClientIpResolver clientIpResolver;

    public ServiceInstance select(List<ServiceInstance> instances, String strategy,
                                  ServerWebExchange exchange) {
        if (instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.get(0);

        return switch (strategy.toLowerCase()) {
            case "round-robin" -> selectByRoundRobin(instances);
            case "random" -> selectByRandom(instances);
            case "consistent-hash" -> selectByConsistentHash(instances, exchange);
            default -> selectByWeightedRoundRobin(instances);
        };
    }

    private ServiceInstance selectByRoundRobin(List<ServiceInstance> instances) {
        String serviceId = instances.get(0).getServiceId();
        java.util.concurrent.atomic.AtomicInteger counter =
                roundRobinCounters.computeIfAbsent(serviceId, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % instances.size());
        return instances.get(index);
    }

    private ServiceInstance selectByRandom(List<ServiceInstance> instances) {
        double totalWeight = instances.stream().mapToDouble(this::getWeight).sum();
        double random = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double weightSum = 0;
        for (ServiceInstance instance : instances) {
            weightSum += getWeight(instance);
            if (random <= weightSum) return instance;
        }
        return instances.get(instances.size() - 1);
    }

    private ServiceInstance selectByConsistentHash(List<ServiceInstance> instances, ServerWebExchange exchange) {
        String hashKey = getHashKey(exchange);
        String serviceId = instances.get(0).getServiceId();
        String fingerprint = instanceFingerprint(instances);
        CachedRing cached = hashRingCache.get(serviceId);
        if (cached == null || !cached.fingerprint.equals(fingerprint)) {
            cached = new CachedRing(fingerprint, buildHashRing(instances));
            hashRingCache.put(serviceId, cached);
        }
        ServiceInstance selected = cached.ring.getNode(hashKey);
        return selected != null ? selected : instances.get(0);
    }

    // Signature of the current instance set (host:port:weight), so the cached ring is
    // rebuilt whenever membership or weights change instead of staying stale forever.
    private String instanceFingerprint(List<ServiceInstance> instances) {
        List<String> parts = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            parts.add(instance.getHost() + ":" + instance.getPort() + ":" + getWeight(instance));
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private String getHashKey(ServerWebExchange exchange) {
        // X-Hash-Key stays caller-controlled on purpose: it is the explicit affinity knob, and a
        // caller choosing its own bucket is the feature. What is NOT acceptable is the implicit
        // fallback doing the same thing behind that feature's back.
        String hashKey = exchange.getRequest().getHeaders().getFirst("X-Hash-Key");
        if (hashKey != null && !hashKey.isEmpty()) return hashKey;

        // FIX (caller-chosen backend): this used to take the first hop of a raw X-Forwarded-For, so
        // any client could name the hash key and therefore pick which instance served it - enough to
        // concentrate an attack on one instance while the others looked healthy, or to defeat
        // affinity for everyone else by colliding keys. ClientIpResolver honours the header only
        // when the TCP peer is a configured trusted proxy, which is what IPFilterGlobalFilter and
        // both rate limiters already rely on. It also null-checks getAddress(), which the inline
        // version here did not.
        //
        // Consequence worth knowing before enabling consistent-hash: gateway.trusted-proxy.proxy-ips
        // must list the reverse proxy, or every request hashes on the proxy's own address and the
        // strategy silently pins the whole service to one instance.
        String ip = clientIpResolver.resolve(exchange);
        return (ip == null || ip.isEmpty() || "unknown".equals(ip))
                ? String.valueOf(System.nanoTime())
                : ip;
    }

    private ConsistentHashRing buildHashRing(List<ServiceInstance> instances) {
        ConsistentHashRing ring = new ConsistentHashRing();
        int virtualNodes = 150;
        for (ServiceInstance instance : instances) {
            double weight = getWeight(instance);
            // Round instead of truncate so fractional weights keep proportional presence,
            // and floor at 1 for any positive weight so it is never dropped from the ring.
            int nodes = weight > 0 ? Math.max(1, (int) Math.round(virtualNodes * weight)) : 0;
            for (int i = 0; i < nodes; i++) {
                ring.addNode(instance.getHost() + ":" + instance.getPort() + "#" + i, instance);
            }
        }
        return ring;
    }

    private ServiceInstance selectByWeightedRoundRobin(List<ServiceInstance> instances) {
        String serviceId = instances.get(0).getServiceId();
        SmoothWeightedRoundRobin<ServiceInstance> selector =
                wrrSelectors.computeIfAbsent(serviceId, k -> new SmoothWeightedRoundRobin<>());
        return selector.select(instances,
                instance -> instance.getHost() + ":" + instance.getPort(),
                this::getWeight);
    }

    double getWeight(ServiceInstance instance) {
        String weightStr = instance.getMetadata().get("weight");
        if (weightStr == null) weightStr = instance.getMetadata().get("nacos.weight");
        try {
            return weightStr != null ? Double.parseDouble(weightStr) : 1.0;
        } catch (NumberFormatException e) {
            log.warn("Invalid weight '{}' for instance {}:{}, using default 1.0",
                    weightStr, instance.getHost(), instance.getPort());
            return 1.0;
        }
    }

    // --- Inner classes ---

    // Cached ring plus the instance-set fingerprint it was built from.
    private static final class CachedRing {
        final String fingerprint;
        final ConsistentHashRing ring;

        CachedRing(String fingerprint, ConsistentHashRing ring) {
            this.fingerprint = fingerprint;
            this.ring = ring;
        }
    }

    // --- Inner class: ConsistentHashRing ---
    private static class ConsistentHashRing {
        private final SortedMap<Long, ServiceInstance> ring = new TreeMap<>();

        void addNode(String key, ServiceInstance instance) {
            ring.put(hash(key), instance);
        }

        ServiceInstance getNode(String key) {
            if (ring.isEmpty()) return null;
            long h = hash(key);
            SortedMap<Long, ServiceInstance> tail = ring.tailMap(h);
            return ring.get(tail.isEmpty() ? ring.firstKey() : tail.firstKey());
        }

        private long hash(String key) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] d = md.digest(key.getBytes());
                return ((long) (d[3] & 0xFF) << 24) | ((long) (d[2] & 0xFF) << 16)
                        | ((long) (d[1] & 0xFF) << 8) | (d[0] & 0xFF);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MD5 not available", e);
            }
        }
    }
}
