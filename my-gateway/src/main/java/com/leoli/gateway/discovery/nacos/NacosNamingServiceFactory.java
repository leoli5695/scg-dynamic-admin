package com.leoli.gateway.discovery.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Properties;

/**
 * Factory for creating and caching Nacos NamingService instances per namespace.
 * <p>
 * Nacos NamingService is bound to a specific namespace at creation time.
 * To query services in different namespaces, we need separate NamingService instances.
 * This factory creates and caches NamingService instances for each namespace.
 *
 * @author leoli
 */
@Slf4j
@Component
public class NacosNamingServiceFactory {

    @Value("${spring.cloud.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    /**
     * Upper bound on cached NamingService instances. Each entry holds its own Nacos client
     * threads/connections, so an unbounded map leaks resources when namespaces churn. In real
     * deployments namespaces are bounded by the number of gateway instances (far below this),
     * so size-based eviction should never fire; it is a safety net, not a hot path.
     */
    private static final long MAX_CACHED_NAMESPACES = 200;

    // Cache: namespace -> NamingService (bounded; evicted clients are shut down to release threads)
    private final Cache<String, NamingService> namingServiceCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_NAMESPACES)
            .removalListener((String ns, NamingService svc, RemovalCause cause) -> {
                // Only auto-evictions (size) release the client here; explicit invalidateAll()
                // during destroy() shuts each one down itself to avoid a double shutDown().
                if (svc != null && cause != RemovalCause.EXPLICIT) {
                    try {
                        svc.shutDown();
                        log.warn("Evicted NamingService for namespace '{}' (cache exceeded {}); released its resources",
                                ns == null || ns.isEmpty() ? "public" : ns, MAX_CACHED_NAMESPACES);
                    } catch (NacosException e) {
                        log.warn("Error shutting down evicted NamingService for namespace '{}': {}", ns, e.getMessage());
                    }
                }
            })
            .build();

    // Default namespace (gateway's own namespace)
    @Value("${spring.cloud.nacos.namespace:}")
    private String defaultNamespace;

    /**
     * Get or create NamingService for a specific namespace.
     *
     * @param namespace Nacos namespace (use null or empty for default namespace)
     * @return NamingService instance for the namespace
     */
    public NamingService getNamingService(String namespace) {
        // Normalize namespace: empty/null -> public (default)
        String targetNamespace = normalizeNamespace(namespace);

        return namingServiceCache.get(targetNamespace, ns -> {
            try {
                return createNamingService(ns);
            } catch (NacosException e) {
                log.error("Failed to create NamingService for namespace: {}", ns, e);
                throw new RuntimeException("Failed to create Nacos NamingService for namespace: " + ns, e);
            }
        });
    }

    /**
     * Get NamingService for default namespace (gateway's own namespace).
     *
     * @return NamingService for default namespace
     */
    public NamingService getDefaultNamingService() {
        return getNamingService(defaultNamespace);
    }

    /**
     * Create a new NamingService for a namespace.
     */
    private NamingService createNamingService(String namespace) throws NacosException {
        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr);

        // Set namespace: empty string means "public" namespace in Nacos
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty("namespace", namespace);
        }
        // If namespace is empty/null, don't set it - Nacos will use public namespace

        log.info("Creating NamingService for namespace: {}, server: {}",
                namespace != null && !namespace.isEmpty() ? namespace : "public",
                serverAddr);

        NamingService namingService = NacosFactory.createNamingService(props);
        log.info("NamingService created successfully for namespace: {}",
                namespace != null && !namespace.isEmpty() ? namespace : "public");

        return namingService;
    }

    /**
     * Normalize namespace string.
     * - null or empty -> "" (means public namespace in Nacos)
     * - otherwise -> original value
     */
    private String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.trim().isEmpty()) {
            return ""; // Empty string means public namespace
        }
        return namespace.trim();
    }

    /**
     * Cleanup all NamingService instances on shutdown.
     */
    @PreDestroy
    public void destroy() {
        log.info("Shutting down all cached NamingService instances...");

        for (Map.Entry<String, NamingService> entry : namingServiceCache.asMap().entrySet()) {
            String namespace = entry.getKey();
            NamingService namingService = entry.getValue();

            try {
                namingService.shutDown();
                log.info("NamingService for namespace '{}' shut down successfully",
                        namespace.isEmpty() ? "public" : namespace);
            } catch (NacosException e) {
                log.warn("Error shutting down NamingService for namespace '{}': {}",
                        namespace.isEmpty() ? "public" : namespace, e.getMessage());
            }
        }

        // EXPLICIT removals are skipped by the removalListener (already shut down above).
        namingServiceCache.invalidateAll();
        log.info("All NamingService instances cleaned up");
    }

    /**
     * Get the number of cached NamingService instances (for monitoring).
     */
    public int getCacheSize() {
        return (int) namingServiceCache.estimatedSize();
    }

    /**
     * Get all cached namespaces (for monitoring).
     */
    public java.util.Set<String> getCachedNamespaces() {
        return new java.util.HashSet<>(namingServiceCache.asMap().keySet());
    }
}