package com.leoli.gateway.limiter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.config.RedisEnabledCondition;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.model.TokenQuotaConfig.TenantTokenQuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Token Quota Manager - Core component for AI Token rate limiting.
 *
 * Responsibilities:
 * - Check token quota before request processing
 * - Consume tokens after response parsing
 * - Manage quota periods (monthly/daily)
 * - Local cache fallback when Redis unavailable
 *
 * Redis Storage Structure:
 * - token_quota:monthly:{tenant}:total → Monthly quota
 * - token_quota:monthly:{tenant}:used → Used tokens this month
 * - token_quota:daily:{tenant}:total → Daily quota
 * - token_quota:daily:{tenant}:used → Used tokens today
 *
 * @author leoli
 */
@Component
@Slf4j
@Conditional(RedisEnabledCondition.class)
public class TokenQuotaManager {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private RedisHealthChecker redisHealthChecker;

    /**
     * Local cache for fallback when Redis unavailable.
     * Key: tenantId, Value: QuotaSnapshot
     */
    private final Cache<String, QuotaSnapshot> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * Lua script for atomic quota check and deduction.
     * Returns: {remaining, isAllowed}
     */
    private DefaultRedisScript<List<Long>> quotaCheckScript;

    /**
     * Lua script for token consumption (no check).
     */
    private DefaultRedisScript<Long> consumeScript;

    // ============== Quota Check Methods ==============

    /**
     * Check if tenant has sufficient quota.
     *
     * @param tenantId Tenant identifier
     * @param config   Token quota configuration
     * @return QuotaCheckResult with detailed status
     */
    public QuotaCheckResult checkQuota(String tenantId, TokenQuotaConfig config) {
        TenantTokenQuota quota = config.getTenantQuota(tenantId);

        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, using local cache fallback for tenant: {}", tenantId);
            return checkQuotaFromLocalCache(tenantId, quota, config);
        }

        try {
            // Check monthly quota if configured
            if (shouldCheckMonthly(quota, config)) {
                QuotaCheckResult monthlyResult = checkMonthlyQuota(tenantId, quota);
                if (!monthlyResult.isAllowed()) {
                    return monthlyResult;
                }
            }

            // Check daily quota if configured
            if (shouldCheckDaily(quota, config)) {
                QuotaCheckResult dailyResult = checkDailyQuota(tenantId, quota);
                if (!dailyResult.isAllowed()) {
                    return dailyResult;
                }
            }

            // Both quotas OK
            return QuotaCheckResult.allowed(
                    getMonthlyRemaining(tenantId, quota),
                    getDailyRemaining(tenantId, quota)
            );

        } catch (Exception e) {
            log.error("Redis quota check failed for tenant {}: {}", tenantId, e.getMessage());
            return checkQuotaFromLocalCache(tenantId, quota, config);
        }
    }

    /**
     * Check monthly quota.
     */
    private QuotaCheckResult checkMonthlyQuota(String tenantId, TenantTokenQuota quota) {
        String usedKey = buildKey("monthly", tenantId, "used");
        String totalKey = buildKey("monthly", tenantId, "total");

        Long used = getUsedTokens(usedKey);
        Long total = getQuotaTotal(totalKey, quota.getMonthlyQuota());

        if (used >= total) {
            log.warn("Monthly quota exceeded for tenant {}: used={}, total={}", tenantId, used, total);
            return QuotaCheckResult.monthlyExceeded(total, used);
        }

        return QuotaCheckResult.allowed(total - used, -1);
    }

    /**
     * Check daily quota.
     */
    private QuotaCheckResult checkDailyQuota(String tenantId, TenantTokenQuota quota) {
        String usedKey = buildKey("daily", tenantId, "used");
        String totalKey = buildKey("daily", tenantId, "total");

        Long used = getUsedTokens(usedKey);
        Long total = getQuotaTotal(totalKey, quota.getDailyQuota());

        if (used >= total) {
            log.warn("Daily quota exceeded for tenant {}: used={}, total={}", tenantId, used, total);
            return QuotaCheckResult.dailyExceeded(total, used);
        }

        return QuotaCheckResult.allowed(-1, total - used);
    }

    // ============== Token Consumption Methods ==============

    /**
     * Consume tokens after response processing.
     *
     * @param tenantId      Tenant identifier
     * @param tokens        Number of tokens consumed
     * @param config        Token quota configuration
     * @return true if consumption successful, false if quota exceeded
     */
    public boolean consumeTokens(String tenantId, long tokens, TokenQuotaConfig config) {
        if (tokens <= 0) {
            log.debug("No tokens to consume for tenant {}", tenantId);
            return true;
        }

        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, using local cache for token consumption: tenant={}, tokens={}",
                    tenantId, tokens);
            return consumeFromLocalCache(tenantId, tokens, config);
        }

        try {
            TenantTokenQuota quota = config.getTenantQuota(tenantId);

            // Consume monthly tokens
            if (shouldCheckMonthly(quota, config)) {
                String monthlyUsedKey = buildKey("monthly", tenantId, "used");
                redisTemplate.opsForValue().increment(monthlyUsedKey, tokens);
                ensureMonthlyKeyTTL(monthlyUsedKey);
            }

            // Consume daily tokens
            if (shouldCheckDaily(quota, config)) {
                String dailyUsedKey = buildKey("daily", tenantId, "used");
                redisTemplate.opsForValue().increment(dailyUsedKey, tokens);
                ensureDailyKeyTTL(dailyUsedKey);
            }

            // Update local cache snapshot
            updateLocalCacheSnapshot(tenantId, config);

            log.info("Consumed {} tokens for tenant {}", tokens, tenantId);
            return true;

        } catch (Exception e) {
            log.error("Redis token consumption failed for tenant {}: {}", tenantId, e.getMessage());
            return consumeFromLocalCache(tenantId, tokens, config);
        }
    }

    /**
     * Atomic check and consume tokens.
     * Returns remaining tokens if successful, -1 if quota exceeded.
     */
    public long checkAndConsume(String tenantId, long tokens, TokenQuotaConfig config) {
        if (!isRedisAvailable()) {
            return checkAndConsumeFromLocalCache(tenantId, tokens, config);
        }

        try {
            TenantTokenQuota quota = config.getTenantQuota(tenantId);

            // Check monthly quota first
            if (shouldCheckMonthly(quota, config)) {
                QuotaCheckResult monthlyResult = checkMonthlyQuota(tenantId, quota);
                if (!monthlyResult.isAllowed()) {
                    return -1;
                }
            }

            // Check daily quota
            if (shouldCheckDaily(quota, config)) {
                QuotaCheckResult dailyResult = checkDailyQuota(tenantId, quota);
                if (!dailyResult.isAllowed()) {
                    return -1;
                }
            }

            // Both OK, consume tokens
            consumeTokens(tenantId, tokens, config);

            return getRemainingTokens(tenantId, config);

        } catch (Exception e) {
            log.error("Atomic check and consume failed for tenant {}: {}", tenantId, e.getMessage());
            return checkAndConsumeFromLocalCache(tenantId, tokens, config);
        }
    }

    // ============== Query Methods ==============

    /**
     * Get remaining tokens for a tenant.
     */
    public long getRemainingTokens(String tenantId, TokenQuotaConfig config) {
        TenantTokenQuota quota = config.getTenantQuota(tenantId);

        long monthlyRemaining = shouldCheckMonthly(quota, config)
                ? getMonthlyRemaining(tenantId, quota)
                : Long.MAX_VALUE;

        long dailyRemaining = shouldCheckDaily(quota, config)
                ? getDailyRemaining(tenantId, quota)
                : Long.MAX_VALUE;

        // Return the minimum (most restrictive)
        return Math.min(monthlyRemaining, dailyRemaining);
    }

    /**
     * Get monthly remaining tokens.
     */
    public long getMonthlyRemaining(String tenantId, TenantTokenQuota quota) {
        String usedKey = buildKey("monthly", tenantId, "used");
        String totalKey = buildKey("monthly", tenantId, "total");

        Long used = getUsedTokens(usedKey);
        Long total = getQuotaTotal(totalKey, quota.getMonthlyQuota());

        return Math.max(0, total - used);
    }

    /**
     * Get daily remaining tokens.
     */
    public long getDailyRemaining(String tenantId, TenantTokenQuota quota) {
        String usedKey = buildKey("daily", tenantId, "used");
        String totalKey = buildKey("daily", tenantId, "total");

        Long used = getUsedTokens(usedKey);
        Long total = getQuotaTotal(totalKey, quota.getDailyQuota());

        return Math.max(0, total - used);
    }

    /**
     * Get full quota status for a tenant.
     */
    public QuotaStatus getQuotaStatus(String tenantId, TokenQuotaConfig config) {
        TenantTokenQuota quota = config.getTenantQuota(tenantId);

        QuotaStatus status = new QuotaStatus();
        status.setTenantId(tenantId);

        if (shouldCheckMonthly(quota, config)) {
            status.setMonthlyQuota(quota.getMonthlyQuota());
            status.setMonthlyUsed(getUsedTokens(buildKey("monthly", tenantId, "used")));
            status.setMonthlyRemaining(getMonthlyRemaining(tenantId, quota));
        }

        if (shouldCheckDaily(quota, config)) {
            status.setDailyQuota(quota.getDailyQuota());
            status.setDailyUsed(getUsedTokens(buildKey("daily", tenantId, "used")));
            status.setDailyRemaining(getDailyRemaining(tenantId, quota));
        }

        status.setPeriodStart(getPeriodStart(quota, config));
        status.setPeriodEnd(getPeriodEnd(quota, config));

        return status;
    }

    // ============== Quota Management Methods ==============

    /**
     * Set tenant quota in Redis.
     */
    public void setTenantQuota(String tenantId, TenantTokenQuota quota, TokenQuotaConfig config) {
        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, cannot set quota for tenant {}", tenantId);
            return;
        }

        try {
            // Set monthly quota
            if (quota.getMonthlyQuota() > 0) {
                String monthlyTotalKey = buildKey("monthly", tenantId, "total");
                redisTemplate.opsForValue().set(monthlyTotalKey, String.valueOf(quota.getMonthlyQuota()));
                ensureMonthlyKeyTTL(monthlyTotalKey);
            }

            // Set daily quota
            if (quota.getDailyQuota() > 0) {
                String dailyTotalKey = buildKey("daily", tenantId, "total");
                redisTemplate.opsForValue().set(dailyTotalKey, String.valueOf(quota.getDailyQuota()));
                ensureDailyKeyTTL(dailyTotalKey);
            }

            log.info("Set quota for tenant {}: monthly={}, daily={}",
                    tenantId, quota.getMonthlyQuota(), quota.getDailyQuota());

        } catch (Exception e) {
            log.error("Failed to set quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Reset quota for a tenant (start new period).
     */
    public void resetQuota(String tenantId, String periodType) {
        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, cannot reset quota for tenant {}", tenantId);
            return;
        }

        try {
            if ("MONTHLY".equalsIgnoreCase(periodType) || "BOTH".equalsIgnoreCase(periodType)) {
                String monthlyUsedKey = buildKey("monthly", tenantId, "used");
                redisTemplate.delete(monthlyUsedKey);
                log.info("Reset monthly quota for tenant {}", tenantId);
            }

            if ("DAILY".equalsIgnoreCase(periodType) || "BOTH".equalsIgnoreCase(periodType)) {
                String dailyUsedKey = buildKey("daily", tenantId, "used");
                redisTemplate.delete(dailyUsedKey);
                log.info("Reset daily quota for tenant {}", tenantId);
            }

        } catch (Exception e) {
            log.error("Failed to reset quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Adjust quota dynamically (increase/decrease).
     */
    public void adjustQuota(String tenantId, long monthlyDelta, long dailyDelta, TokenQuotaConfig config) {
        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, cannot adjust quota for tenant {}", tenantId);
            return;
        }

        try {
            TenantTokenQuota quota = config.getTenantQuota(tenantId);

            if (monthlyDelta != 0 && quota.getMonthlyQuota() > 0) {
                String monthlyTotalKey = buildKey("monthly", tenantId, "total");
                long current = getQuotaTotal(monthlyTotalKey, quota.getMonthlyQuota());
                redisTemplate.opsForValue().set(monthlyTotalKey, String.valueOf(current + monthlyDelta));
            }

            if (dailyDelta != 0 && quota.getDailyQuota() > 0) {
                String dailyTotalKey = buildKey("daily", tenantId, "total");
                long current = getQuotaTotal(dailyTotalKey, quota.getDailyQuota());
                redisTemplate.opsForValue().set(dailyTotalKey, String.valueOf(current + dailyDelta));
            }

            log.info("Adjusted quota for tenant {}: monthlyDelta={}, dailyDelta={}",
                    tenantId, monthlyDelta, dailyDelta);

        } catch (Exception e) {
            log.error("Failed to adjust quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ============== Token Refund Methods ==============

    /**
     * Refund tokens when actual usage differs from pre-deducted amount.
     * Used for SSE streams where pre-deduct estimate may be inaccurate.
     *
     * @param tenantId Tenant identifier
     * @param tokensToRefund Number of tokens to refund (positive value)
     * @param config Token quota configuration
     * @param reason Reason for refund (e.g., "STREAM_INTERRUPTED", "ESTIMATE_DIFF")
     * @return true if refund successful
     */
    public boolean refundTokens(String tenantId, long tokensToRefund, TokenQuotaConfig config, String reason) {
        if (tokensToRefund <= 0) {
            log.debug("No tokens to refund for tenant {}", tenantId);
            return true;
        }

        // Check refund threshold
        if (config.isAutoRefundEnabled()) {
            long threshold = config.getRefundThresholdPercent();
            // Only refund if meaningful (avoid overhead for small diffs)
            if (tokensToRefund < 10) {  // Minimum 10 tokens for refund
                log.debug("Refund below threshold for tenant {}: {} tokens", tenantId, tokensToRefund);
                return true;  // Consider success, just skip
            }
        }

        // Cap refund to max
        long cappedRefund = Math.min(tokensToRefund, config.getMaxRefundPerRequest());

        if (!isRedisAvailable()) {
            log.warn("Redis unavailable, refunding in local cache: tenant={}, tokens={}",
                    tenantId, cappedRefund);
            return refundFromLocalCache(tenantId, cappedRefund, config);
        }

        try {
            TenantTokenQuota quota = config.getTenantQuota(tenantId);

            // Refund monthly tokens (decrease used count)
            if (shouldCheckMonthly(quota, config)) {
                String monthlyUsedKey = buildKey("monthly", tenantId, "used");
                long currentUsed = getUsedTokens(monthlyUsedKey);
                long newUsed = Math.max(0, currentUsed - cappedRefund);
                redisTemplate.opsForValue().set(monthlyUsedKey, String.valueOf(newUsed));
                ensureMonthlyKeyTTL(monthlyUsedKey);
            }

            // Refund daily tokens
            if (shouldCheckDaily(quota, config)) {
                String dailyUsedKey = buildKey("daily", tenantId, "used");
                long currentUsed = getUsedTokens(dailyUsedKey);
                long newUsed = Math.max(0, currentUsed - cappedRefund);
                redisTemplate.opsForValue().set(dailyUsedKey, String.valueOf(newUsed));
                ensureDailyKeyTTL(dailyUsedKey);
            }

            // Update local cache snapshot
            updateLocalCacheSnapshot(tenantId, config);

            log.info("Refunded {} tokens for tenant {} (reason: {})", cappedRefund, tenantId, reason);
            return true;

        } catch (Exception e) {
            log.error("Redis refund failed for tenant {}: {}", tenantId, e.getMessage());
            return refundFromLocalCache(tenantId, cappedRefund, config);
        }
    }

    /**
     * Pre-deduct estimated tokens for a request.
     * Returns the estimated tokens pre-deducted for later comparison.
     *
     * @param tenantId Tenant identifier
     * @param estimatedTokens Estimated tokens to pre-deduct
     * @param config Token quota configuration
     * @return Estimated tokens pre-deducted (for later refund calculation)
     */
    public long preDeductTokens(String tenantId, long estimatedTokens, TokenQuotaConfig config) {
        if (estimatedTokens <= 0) {
            return 0;
        }

        // Apply multiplier based on strategy
        long preDeductAmount = estimatedTokens;
        if ("ESTIMATE".equalsIgnoreCase(config.getPreDeductStrategy())) {
            preDeductAmount = estimatedTokens * config.getEstimateMultiplier();
        }

        // Check quota before pre-deduct
        QuotaCheckResult checkResult = checkQuota(tenantId, config);
        if (!checkResult.isAllowed()) {
            return -1;  // Quota exceeded
        }

        // Pre-deduct tokens
        boolean success = consumeTokens(tenantId, preDeductAmount, config);
        if (success) {
            log.info("Pre-deducted {} tokens for tenant {} (estimated: {})",
                    preDeductAmount, tenantId, estimatedTokens);
            return preDeductAmount;
        }

        return -1;
    }

    /**
     * Calculate actual token usage and refund difference.
     * Called after response is complete.
     *
     * @param tenantId Tenant identifier
     * @param preDeductedTokens Tokens that were pre-deducted
     * @param actualTokens Actual tokens consumed
     * @param config Token quota configuration
     */
    public void settleTokenUsage(String tenantId, long preDeductedTokens, long actualTokens,
                                  TokenQuotaConfig config) {
        if (preDeductedTokens <= 0) {
            // No pre-deduct, just consume actual
            if (actualTokens > 0) {
                consumeTokens(tenantId, actualTokens, config);
            }
            return;
        }

        long diff = preDeductedTokens - actualTokens;

        if (diff > 0) {
            // Pre-deducted more than actual, refund excess
            refundTokens(tenantId, diff, config, "ESTIMATE_DIFF");
        } else if (diff < 0) {
            // Actual exceeds pre-deducted, consume additional
            long additional = Math.abs(diff);
            consumeTokens(tenantId, additional, config);
            log.info("Additional {} tokens consumed for tenant {} (actual > estimate)",
                    additional, tenantId);
        } else {
            log.debug("Token usage exactly matched estimate for tenant {}", tenantId);
        }
    }

    private boolean refundFromLocalCache(String tenantId, long tokens, TokenQuotaConfig config) {
        QuotaSnapshot snapshot = localCache.get(tenantId,
                key -> createDefaultSnapshot(config.getTenantQuota(tenantId), config));

        snapshot.setMonthlyUsed(Math.max(0, snapshot.getMonthlyUsed() - tokens));
        snapshot.setDailyUsed(Math.max(0, snapshot.getDailyUsed() - tokens));
        localCache.put(tenantId, snapshot);

        log.info("Refunded {} tokens in local cache for tenant {}", tokens, tenantId);
        return true;
    }

    // ============== Helper Methods ==============

    private boolean isRedisAvailable() {
        return redisTemplate != null
                && (redisHealthChecker == null || redisHealthChecker.isRedisAvailableForRateLimiting());
    }

    private boolean shouldCheckMonthly(TenantTokenQuota quota, TokenQuotaConfig config) {
        String period = quota.getQuotaPeriod() != null ? quota.getQuotaPeriod() : config.getQuotaPeriod();
        return "MONTHLY".equalsIgnoreCase(period) || "BOTH".equalsIgnoreCase(period);
    }

    private boolean shouldCheckDaily(TenantTokenQuota quota, TokenQuotaConfig config) {
        String period = quota.getQuotaPeriod() != null ? quota.getQuotaPeriod() : config.getQuotaPeriod();
        return "DAILY".equalsIgnoreCase(period) || "BOTH".equalsIgnoreCase(period);
    }

    private String buildKey(String period, String tenantId, String field) {
        // Include period identifier in key
        String periodId = "MONTHLY".equalsIgnoreCase(period)
                ? getCurrentMonthId()
                : getCurrentDayId();
        return "token_quota:" + period + ":" + tenantId + ":" + periodId + ":" + field;
    }

    private String getCurrentMonthId() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private String getCurrentDayId() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private Long getUsedTokens(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : 0L;
    }

    private Long getQuotaTotal(String key, long defaultValue) {
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    private void ensureMonthlyKeyTTL(String key) {
        // Monthly quota expires at end of month (max 31 days)
        long ttlSeconds = calculateMonthlyTTLSeconds();
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    private void ensureDailyKeyTTL(String key) {
        // Daily quota expires at end of day
        long ttlSeconds = calculateDailyTTLSeconds();
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    private long calculateMonthlyTTLSeconds() {
        LocalDate today = LocalDate.now();
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());
        LocalDateTime endOfMonthTime = endOfMonth.atTime(23, 59, 59);
        return Duration.between(LocalDateTime.now(), endOfMonthTime).getSeconds() + 1;
    }

    private long calculateDailyTTLSeconds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfDay = now.toLocalDate().atTime(23, 59, 59);
        return Duration.between(now, endOfDay).getSeconds() + 1;
    }

    private LocalDateTime getPeriodStart(TenantTokenQuota quota, TokenQuotaConfig config) {
        if (shouldCheckMonthly(quota, config)) {
            return LocalDate.now().withDayOfMonth(1).atStartOfDay();
        }
        return LocalDate.now().atStartOfDay();
    }

    private LocalDateTime getPeriodEnd(TenantTokenQuota quota, TokenQuotaConfig config) {
        if (shouldCheckMonthly(quota, config)) {
            LocalDate today = LocalDate.now();
            return today.withDayOfMonth(today.lengthOfMonth()).atTime(23, 59, 59);
        }
        return LocalDate.now().atTime(23, 59, 59);
    }

    // ============== Local Cache Fallback ==============

    private QuotaCheckResult checkQuotaFromLocalCache(String tenantId, TenantTokenQuota quota,
                                                       TokenQuotaConfig config) {
        QuotaSnapshot snapshot = localCache.get(tenantId, key -> createDefaultSnapshot(quota, config));

        // Use conservative quota (e.g., 50% of normal)
        int fallbackPercent = config.getFallbackQuotaPercent();

        if (shouldCheckMonthly(quota, config)) {
            long effectiveQuota = quota.getMonthlyQuota() * fallbackPercent / 100;
            if (snapshot.getMonthlyUsed() >= effectiveQuota) {
                return QuotaCheckResult.monthlyExceeded(effectiveQuota, snapshot.getMonthlyUsed());
            }
        }

        if (shouldCheckDaily(quota, config)) {
            long effectiveQuota = quota.getDailyQuota() * fallbackPercent / 100;
            if (snapshot.getDailyUsed() >= effectiveQuota) {
                return QuotaCheckResult.dailyExceeded(effectiveQuota, snapshot.getDailyUsed());
            }
        }

        return QuotaCheckResult.fallback(
                quota.getMonthlyQuota() * fallbackPercent / 100 - snapshot.getMonthlyUsed(),
                quota.getDailyQuota() * fallbackPercent / 100 - snapshot.getDailyUsed()
        );
    }

    private boolean consumeFromLocalCache(String tenantId, long tokens, TokenQuotaConfig config) {
        QuotaSnapshot snapshot = localCache.get(tenantId, key -> createDefaultSnapshot(config.getTenantQuota(tenantId), config));

        // Update local cache
        snapshot.setMonthlyUsed(snapshot.getMonthlyUsed() + tokens);
        snapshot.setDailyUsed(snapshot.getDailyUsed() + tokens);
        localCache.put(tenantId, snapshot);

        log.info("Consumed {} tokens in local cache for tenant {}", tokens, tenantId);
        return true;
    }

    private long checkAndConsumeFromLocalCache(String tenantId, long tokens, TokenQuotaConfig config) {
        TenantTokenQuota quota = config.getTenantQuota(tenantId);
        QuotaCheckResult checkResult = checkQuotaFromLocalCache(tenantId, quota, config);

        if (!checkResult.isAllowed()) {
            return -1;
        }

        consumeFromLocalCache(tenantId, tokens, config);
        return checkResult.getMonthlyRemaining();
    }

    private void updateLocalCacheSnapshot(String tenantId, TokenQuotaConfig config) {
        TenantTokenQuota quota = config.getTenantQuota(tenantId);

        QuotaSnapshot snapshot = new QuotaSnapshot();
        snapshot.setTenantId(tenantId);
        snapshot.setMonthlyQuota(quota.getMonthlyQuota());
        snapshot.setDailyQuota(quota.getDailyQuota());

        if (shouldCheckMonthly(quota, config)) {
            snapshot.setMonthlyUsed(getUsedTokens(buildKey("monthly", tenantId, "used")));
        }
        if (shouldCheckDaily(quota, config)) {
            snapshot.setDailyUsed(getUsedTokens(buildKey("daily", tenantId, "used")));
        }

        localCache.put(tenantId, snapshot);
    }

    private QuotaSnapshot createDefaultSnapshot(TenantTokenQuota quota, TokenQuotaConfig config) {
        QuotaSnapshot snapshot = new QuotaSnapshot();
        snapshot.setTenantId("default");
        snapshot.setMonthlyQuota(quota.getMonthlyQuota());
        snapshot.setDailyQuota(quota.getDailyQuota());
        snapshot.setMonthlyUsed(0L);
        snapshot.setDailyUsed(0L);
        return snapshot;
    }

    // ============== Inner Classes ==============

    /**
     * Quota check result with detailed status.
     */
    @lombok.Data
    public static class QuotaCheckResult {
        private boolean allowed;
        private boolean redisAvailable;
        private boolean shouldFallback;
        private long monthlyRemaining;
        private long dailyRemaining;
        private String exceededType;  // "MONTHLY", "DAILY", or null
        private String errorMessage;

        public static QuotaCheckResult allowed(long monthlyRemaining, long dailyRemaining) {
            QuotaCheckResult result = new QuotaCheckResult();
            result.setAllowed(true);
            result.setRedisAvailable(true);
            result.setShouldFallback(false);
            result.setMonthlyRemaining(monthlyRemaining);
            result.setDailyRemaining(dailyRemaining);
            return result;
        }

        public static QuotaCheckResult monthlyExceeded(long total, long used) {
            QuotaCheckResult result = new QuotaCheckResult();
            result.setAllowed(false);
            result.setRedisAvailable(true);
            result.setShouldFallback(false);
            result.setExceededType("MONTHLY");
            result.setErrorMessage("Monthly quota exceeded: used " + used + " of " + total);
            return result;
        }

        public static QuotaCheckResult dailyExceeded(long total, long used) {
            QuotaCheckResult result = new QuotaCheckResult();
            result.setAllowed(false);
            result.setRedisAvailable(true);
            result.setShouldFallback(false);
            result.setExceededType("DAILY");
            result.setErrorMessage("Daily quota exceeded: used " + used + " of " + total);
            return result;
        }

        public static QuotaCheckResult fallback(long monthlyRemaining, long dailyRemaining) {
            QuotaCheckResult result = new QuotaCheckResult();
            result.setAllowed(true);
            result.setRedisAvailable(false);
            result.setShouldFallback(true);
            result.setMonthlyRemaining(monthlyRemaining);
            result.setDailyRemaining(dailyRemaining);
            result.setErrorMessage("Using local cache fallback");
            return result;
        }
    }

    /**
     * Quota snapshot for local cache.
     */
    @lombok.Data
    public static class QuotaSnapshot {
        private String tenantId;
        private long monthlyQuota;
        private long dailyQuota;
        private long monthlyUsed;
        private long dailyUsed;
        private long lastUpdated;
    }

    /**
     * Full quota status for API response.
     */
    @lombok.Data
    public static class QuotaStatus {
        private String tenantId;
        private long monthlyQuota;
        private long monthlyUsed;
        private long monthlyRemaining;
        private long dailyQuota;
        private long dailyUsed;
        private long dailyRemaining;
        private LocalDateTime periodStart;
        private LocalDateTime periodEnd;

        public double getMonthlyUsagePercent() {
            if (monthlyQuota == 0) return 0;
            return (double) monthlyUsed / monthlyQuota * 100;
        }

        public double getDailyUsagePercent() {
            if (dailyQuota == 0) return 0;
            return (double) dailyUsed / dailyQuota * 100;
        }
    }
}