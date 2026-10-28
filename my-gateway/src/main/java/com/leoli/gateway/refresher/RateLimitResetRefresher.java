package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.filter.ratelimit.HybridRateLimiterFilter;
import com.leoli.gateway.filter.ratelimit.MultiDimRateLimiterFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Rate limit reset command listener.
 * Listens to Nacos command channel for rate limit counter reset requests.
 * <p>
 * When a strategy is re-enabled after being disabled, Admin publishes a reset command
 * to clear existing counters (Redis + local cache) for a clean start.
 * <p>
 * Command data ID: config.gateway.command.rate-limit-reset
 * Command content: JSON with strategyId and scope information
 * <p>
 * Example command:
 * {
 *   "strategyId": "uuid-xxx",
 *   "strategyType": "RATE_LIMITER",
 *   "scope": "ROUTE",
 *   "routeId": "route-123",
 *   "timestamp": 1234567890
 * }
 *
 * @author leoli
 */
@Slf4j
@Component
public class RateLimitResetRefresher {

    private static final String RATE_LIMIT_RESET_COMMAND = "config.gateway.command.rate-limit-reset";

    private final ObjectMapper objectMapper;
    private final ConfigCenterService configService;

    @Autowired(required = false)
    private HybridRateLimiterFilter hybridRateLimiterFilter;

    @Autowired(required = false)
    private MultiDimRateLimiterFilter multiDimRateLimiterFilter;

    private ConfigCenterService.ConfigListener commandListener;

    /**
     * Timestamp of the last reset command actually applied, used to de-duplicate.
     * <p>
     * This channel carries one-shot commands, not state, so it cannot be reconciled the
     * way the index-driven refreshers do: the published command stays in the config center
     * until a strategy is deleted, and re-applying it would clear the local rate-limit
     * windows on every cycle - which resets the counters that are supposed to be counting,
     * i.e. silently disables the limiter. Anything at or below this stamp is already done.
     */
    private final AtomicLong appliedCommandTimestamp = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public RateLimitResetRefresher(ConfigCenterService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        log.info("RateLimitResetRefresher initialized for config center: {}", configService.getCenterType());
    }

    @PostConstruct
    public void init() {
        registerCommandListener();
        adoptExistingCommandAsBaseline();
        log.info("RateLimitResetRefresher: Listening for reset commands on {}", RATE_LIMIT_RESET_COMMAND);
    }

    @PreDestroy
    public void destroy() {
        if (commandListener != null) {
            configService.removeListener(RATE_LIMIT_RESET_COMMAND, GROUP, commandListener);
            log.info("RateLimitResetRefresher: Command listener removed");
        }
    }

    /**
     * Register listener for rate limit reset commands.
     */
    private void registerCommandListener() {
        commandListener = (dataId, group, content) -> {
            log.info("Received rate limit reset command: {}", content);
            applyIfNewer(content);
        };

        configService.addListener(RATE_LIMIT_RESET_COMMAND, GROUP, commandListener);
        log.debug("Registered listener for rate limit reset commands");
    }

    /**
     * Mark the command already sitting in the config center as applied, without applying it.
     * <p>
     * A restart is itself a clean start - the local caches this command clears are empty at
     * boot - so replaying a command published before the restart would only wipe counters
     * that legitimately accumulated since.
     */
    private void adoptExistingCommandAsBaseline() {
        try {
            String content = configService.getConfig(RATE_LIMIT_RESET_COMMAND, GROUP);
            long timestamp = extractTimestamp(content);
            if (timestamp > appliedCommandTimestamp.get()) {
                appliedCommandTimestamp.set(timestamp);
                log.info("Adopted existing rate limit reset command as baseline (timestamp={}), not replaying it",
                        timestamp);
            }
        } catch (Exception e) {
            log.warn("Could not read the existing reset command baseline - the first periodic sync will adopt it", e);
        }
    }

    /**
     * Periodic fallback for the reset command channel.
     * <p>
     * nacos-client 3.0.3 never receives pushes from nacos-server 2.5.4 (measured live), so
     * without this a re-enabled strategy would keep its stale counters until restart.
     * De-duplicated by the command timestamp the admin stamps on every publish - see
     * {@link #appliedCommandTimestamp}.
     */
    @Scheduled(fixedRateString = "${gateway.config-sync.interval-ms:60000}")
    public void periodicSyncResetCommand() {
        try {
            String content = configService.getConfig(RATE_LIMIT_RESET_COMMAND, GROUP);
            if (content == null || content.isBlank()) {
                return; // no pending command (cleared when the strategy was deleted)
            }
            applyIfNewer(content);
        } catch (Exception e) {
            log.warn("Periodic rate limit reset sync failed - a pending reset stays unapplied until it succeeds", e);
        }
    }

    /**
     * Apply a reset command only if it is newer than the last one applied.
     */
    private void applyIfNewer(String content) {
        long timestamp = extractTimestamp(content);
        if (timestamp < 0) {
            // No usable stamp means no way to tell a new command from one already applied.
            // Skipping is the safe direction: a missed reset leaves counters as they are,
            // while a replayed one clears windows that are still supposed to be counting.
            log.warn("Reset command carries no usable timestamp, ignoring to avoid replaying it every cycle: {}",
                    content);
            return;
        }

        long last = appliedCommandTimestamp.get();
        if (timestamp <= last) {
            return; // already applied
        }
        if (!appliedCommandTimestamp.compareAndSet(last, timestamp)) {
            return; // another thread (listener callback) got there first
        }

        onResetCommand(content);
    }

    /**
     * Read the command timestamp, or -1 when the content is absent or carries no usable stamp.
     */
    private long extractTimestamp(String content) {
        if (content == null || content.isBlank()) {
            return -1L;
        }
        try {
            Map<String, Object> command = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            Object raw = command.get("timestamp");
            return raw instanceof Number ? ((Number) raw).longValue() : -1L;
        } catch (Exception e) {
            log.warn("Failed to read timestamp from reset command: {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * Handle reset command.
     * Clears local rate limiter cache for the specified strategy.
     */
    private void onResetCommand(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Empty reset command received, ignoring");
            return;
        }

        try {
            Map<String, Object> command = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            String strategyId = (String) command.get("strategyId");
            String strategyType = (String) command.get("strategyType");
            String scope = (String) command.get("scope");
            String routeId = (String) command.get("routeId");
            Long timestamp = command.get("timestamp") != null ? ((Number) command.get("timestamp")).longValue() : null;

            log.info("Processing reset command: strategyId={}, type={}, scope={}, routeId={}, timestamp={}",
                    strategyId, strategyType, scope, routeId, timestamp);

            // Validate command
            if (strategyId == null || strategyId.isEmpty()) {
                log.warn("Reset command missing strategyId, ignoring");
                return;
            }

            // Reset local caches based on strategy type
            String keyPrefix = command.get("keyPrefix") instanceof String prefix ? prefix : null;
            resetLocalCaches(strategyId, strategyType, scope, routeId, keyPrefix);

            log.info("Rate limit counters reset completed for strategy: {}", strategyId);

        } catch (Exception e) {
            log.error("Failed to process reset command: {} - {}", content, e.getMessage(), e);
        }
    }

    /**
     * Reset local rate limiter caches.
     * Clears cache entries matching the strategy's key pattern.
     */
    private void resetLocalCaches(String strategyId, String strategyType, String scope, String routeId, String keyPrefix) {
        // Reset HybridRateLimiterFilter cache (for RATE_LIMITER type)
        if (hybridRateLimiterFilter != null && !"MULTI_DIM_RATE_LIMITER".equals(strategyType)) {
            Set<String> keysToReset = buildHybridCacheKeyPattern(strategyId, scope, routeId);
            if (keyPrefix != null) {
                keysToReset = keysToReset.stream().map(pattern -> keyPrefix + pattern.substring("rate_limit:".length()))
                        .collect(java.util.stream.Collectors.toSet());
            }
            hybridRateLimiterFilter.invalidateCache(keysToReset);
            log.info("HybridRateLimiter cache reset for strategy: {}, keys cleared: {}", strategyId, keysToReset.size());
        }

        // Reset MultiDimRateLimiterFilter cache (for MULTI_DIM_RATE_LIMITER type)
        if (multiDimRateLimiterFilter != null && "MULTI_DIM_RATE_LIMITER".equals(strategyType)) {
            Set<String> keysToReset = buildMultiDimCacheKeyPattern(scope, routeId);
            if (keyPrefix != null) {
                keysToReset = keysToReset.stream().map(pattern -> keyPrefix + pattern.substring("multi_rate:".length()))
                        .collect(java.util.stream.Collectors.toSet());
            }
            keysToReset.add((keyPrefix != null ? keyPrefix : "multi_rate:") + strategyId + ":*");
            multiDimRateLimiterFilter.invalidateCache(keysToReset);
            log.info("MultiDimRateLimiter cache reset for strategy: {}, keys cleared: {}", strategyId, keysToReset.size());
        }
    }

    /**
     * Build cache key pattern for hybrid rate limiter.
     * Key format: rate_limit:{strategyId}:{keyType}:{...}
     * Handles both ROUTE and GLOBAL scope strategies.
     */
    private Set<String> buildHybridCacheKeyPattern(String strategyId, String scope, String routeId) {
        Set<String> patterns = new java.util.HashSet<>();

        // Strategy-specific pattern (covers all key types) - works for both ROUTE and GLOBAL
        patterns.add("rate_limit:" + strategyId + ":*");

        // For backward compatibility with old key format (without strategyId)
        if ("GLOBAL".equals(scope)) {
            // Global strategy applies to all routes - clear all global-related keys
            patterns.add("rate_limit:global:*");
            patterns.add("rate_limit:ip:*");
            patterns.add("rate_limit:user:*");
            patterns.add("rate_limit:combined:*");
            patterns.add("rate_limit:route:*");
            log.info("Global hybrid strategy cache reset patterns: {}", patterns);
        } else if (routeId != null && !routeId.isEmpty()) {
            // Route-specific patterns
            patterns.add("rate_limit:combined:" + routeId + ":*");
            patterns.add("rate_limit:route:" + routeId);
            patterns.add("rate_limit:ip:" + routeId + ":*");
            log.info("Route hybrid strategy cache reset patterns for route {}: {}", routeId, patterns);
        }

        return patterns;
    }

    /**
     * Build cache key pattern for multi-dimensional rate limiter.
     * Key format: multi_rate:{dimension}:{routeId}:{...}
     */
    private Set<String> buildMultiDimCacheKeyPattern(String scope, String routeId) {
        Set<String> patterns = new java.util.HashSet<>();

        if ("GLOBAL".equals(scope)) {
            for (String dimension : java.util.List.of("global", "tenant", "user", "ip")) {
                patterns.add("multi_rate:" + dimension + ":*");
            }
            log.info("Global multi-dim strategy cache reset pattern: {}", patterns);
        } else if (routeId != null && !routeId.isEmpty()) {
            // Route-specific: clear keys for specific route
            patterns.add("multi_rate:global:" + routeId);
            patterns.add("multi_rate:tenant:" + routeId + ":*");
            patterns.add("multi_rate:user:" + routeId + ":*");
            patterns.add("multi_rate:ip:" + routeId + ":*");
            log.info("Route multi-dim strategy cache reset patterns for route {}: {}", routeId, patterns);
        }

        return patterns;
    }
}
