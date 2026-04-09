package com.leoli.gateway.filter;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.web.server.ServerWebExchange;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Load balancing instance selection strategies.
 */
@Slf4j
@Component
public class InstanceSelector {

    private final Map<String, Double> currentWeights = new ConcurrentHashMap<>();
    private final Map<String, ConsistentHashRing> hashRingCache = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

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
        double random = Math.random() * totalWeight;
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
        ConsistentHashRing hashRing = hashRingCache.computeIfAbsent(serviceId,
                k -> buildHashRing(instances));
        ServiceInstance selected = hashRing.getNode(hashKey);
        return selected != null ? selected : instances.get(0);
    }

    private String getHashKey(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        String hashKey = headers.getFirst("X-Hash-Key");
        if (hashKey != null && !hashKey.isEmpty()) return hashKey;

        String forwardedFor = headers.getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }

        if (exchange.getRequest().getRemoteAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return String.valueOf(System.nanoTime());
    }

    private ConsistentHashRing buildHashRing(List<ServiceInstance> instances) {
        ConsistentHashRing ring = new ConsistentHashRing();
        int virtualNodes = 150;
        for (ServiceInstance instance : instances) {
            int nodes = virtualNodes * (int) getWeight(instance);
            for (int i = 0; i < nodes; i++) {
                ring.addNode(instance.getHost() + ":" + instance.getPort() + "#" + i, instance);
            }
        }
        return ring;
    }

    private ServiceInstance selectByWeightedRoundRobin(List<ServiceInstance> instances) {
        for (ServiceInstance instance : instances) {
            String key = instance.getHost() + ":" + instance.getPort();
            currentWeights.putIfAbsent(key, 0.0);
        }

        double totalWeight = 0;
        for (ServiceInstance instance : instances) {
            String key = instance.getHost() + ":" + instance.getPort();
            double weight = getWeight(instance);
            currentWeights.put(key, currentWeights.get(key) + weight);
            totalWeight += weight;
        }

        ServiceInstance selected = null;
        double maxWeight = -1;
        for (ServiceInstance instance : instances) {
            String key = instance.getHost() + ":" + instance.getPort();
            double currentWeight = currentWeights.get(key);
            if (currentWeight > maxWeight) {
                maxWeight = currentWeight;
                selected = instance;
            }
        }

        if (selected != null) {
            String key = selected.getHost() + ":" + selected.getPort();
            currentWeights.put(key, currentWeights.get(key) - totalWeight);
        }
        return selected;
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

    // --- Inner class: ConsistentHashRing ---
    private static class ConsistentHashRing {
        private final SortedMap<Long, ServiceInstance> ring = new TreeMap<>();
        private static final MessageDigest md5;

        static {
            try { md5 = MessageDigest.getInstance("MD5"); }
            catch (NoSuchAlgorithmException e) { throw new RuntimeException("MD5 not available", e); }
        }

        void addNode(String key, ServiceInstance instance) { ring.put(hash(key), instance); }

        ServiceInstance getNode(String key) {
            if (ring.isEmpty()) return null;
            long h = hash(key);
            SortedMap<Long, ServiceInstance> tail = ring.tailMap(h);
            return ring.get(tail.isEmpty() ? ring.firstKey() : tail.firstKey());
        }

        private long hash(String key) {
            byte[] d = md5.digest(key.getBytes());
            return ((long) (d[3] & 0xFF) << 24) | ((long) (d[2] & 0xFF) << 16)
                    | ((long) (d[1] & 0xFF) << 8) | (d[0] & 0xFF);
        }
    }
}
