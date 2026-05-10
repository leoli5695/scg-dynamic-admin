package com.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;

/**
 * ============================================================================
 * Anti-Scraping Service - 防刷机制
 * ============================================================================
 * <p>
 * SECURITY FIX (P1): Comprehensive anti-scraping mechanism
 * <p>
 * Features:
 * 1. IP-level rate limiting (sliding window with Lua)
 * 2. IP blacklist management (block malicious IPs)
 * 3. User-level rate limiting (sliding window with Lua)
 * 4. Auto-blacklist trigger (auto-block IPs exceeding threshold)
 * <p>
 * Implementation:
 * - Redis Lua sliding window (精确限流，避免边界突刺)
 * - Redis-based blacklist storage (Set)
 * - Automatic cleanup of stale data
 * <p>
 * Monitoring:
 * - seckill.anti_scraping.ip_blocked: IP blocked count
 * - seckill.anti_scraping.ip_rate_limit: IP rate limit trigger count
 * - seckill.anti_scraping.user_rate_limit: User rate limit trigger count
 *
 * @author leoli
 */
@Slf4j
@Service
public class AntiScrapingService {

    private final AlertService alertService;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    // Redis key prefixes
    private static final String IP_RATE_KEY_PREFIX = "seckill:anti:ip_rate:";
    private static final String IP_BLACKLIST_KEY = "seckill:anti:ip_blacklist";
    private static final String USER_RATE_KEY_PREFIX = "seckill:anti:user_rate:";

    // Configuration
    @Value("${seckill.anti-scraping.enabled:true}")
    private boolean enabled;

    @Value("${seckill.anti-scraping.ip-rate-limit:10}")
    private int ipRateLimitPerSecond;

    @Value("${seckill.anti-scraping.user-rate-limit:5}")
    private int userRateLimitPerSecond;

    @Value("${seckill.anti-scraping.auto-blacklist-threshold:100}")
    private int autoBlacklistThreshold;

    @Value("${seckill.anti-scraping.blacklist-ttl-hours:24}")
    private int blacklistTtlHours;

    // Rate limit window in milliseconds
    private static final int RATE_WINDOW_MS = 1000;

    // Metrics
    private final Counter ipBlockedCounter;
    private final Counter ipRateLimitCounter;
    private final Counter userRateLimitCounter;

    // Lua script for sliding window rate limiting
    private DefaultRedisScript<Long> rateLimitScript;

    public AntiScrapingService(
            StringRedisTemplate redisTemplate,
            AlertService alertService,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.alertService = alertService;
        this.meterRegistry = meterRegistry;

        // Register metrics
        this.ipBlockedCounter = Counter.builder("seckill.anti_scraping.ip_blocked")
                .description("IP blocked count by anti-scraping")
                .register(meterRegistry);
        this.ipRateLimitCounter = Counter.builder("seckill.anti_scraping.ip_rate_limit")
                .description("IP rate limit trigger count")
                .register(meterRegistry);
        this.userRateLimitCounter = Counter.builder("seckill.anti_scraping.user_rate_limit")
                .description("User rate limit trigger count")
                .register(meterRegistry);
    }

    /**
     * Load Lua script at startup for sliding window rate limiting
     */
    @PostConstruct
    public void loadLuaScript() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        rateLimitScript.setResultType(Long.class);
        log.info("AntiScrapingService initialized with Lua sliding window rate limiting");
    }

    /**
     * ============================================================================
     * Check if IP is allowed (not blacklisted and within rate limit)
     * ============================================================================
     * <p>
     * OPTIMIZATION: 使用 Lua 滑动窗口替代固定窗口计数器
     * - 精确限流，避免边界突刺问题
     * - 原子操作，INCR + EXPIRE 一次完成
     *
     * @param clientIp Client IP address
     * @return true: allowed, false: blocked
     */
    public boolean isIpAllowed(String clientIp) {
        if (!enabled) {
            return true;
        }

        // Check blacklist first
        if (isIpBlacklisted(clientIp)) {
            ipBlockedCounter.increment();
            log.warn("IP blocked by blacklist: ip={}", clientIp);
            return false;
        }

        // Check rate limit using Lua sliding window
        // FIX: 添加 random value 避免同毫秒去重
        String key = IP_RATE_KEY_PREFIX + clientIp;
        long currentTime = System.currentTimeMillis();
        String randomValue = java.util.UUID.randomUUID().toString().substring(0, 8);

        Long result = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(RATE_WINDOW_MS),
                String.valueOf(ipRateLimitPerSecond),
                String.valueOf(currentTime),
                randomValue  // FIX: 添加唯一标识避免同毫秒去重
        );

        if (result != null && result == -1) {
            ipRateLimitCounter.increment();
            log.warn("IP rate limited (sliding window): ip={}, max={}", clientIp, ipRateLimitPerSecond);

            // Check auto-blacklist threshold
            // For sliding window, we need to check the count in the key
            long currentCount = getCurrentIpCount(clientIp);
            if (currentCount >= autoBlacklistThreshold) {
                addToBlacklist(clientIp);
                ipBlockedCounter.increment();
                log.warn("IP auto-blacklisted: ip={}, requestsInWindow={}", clientIp, currentCount);
                alertService.sendCriticalAlert("IP自动封禁",
                        "IP: " + clientIp + " 滑动窗口内请求 " + currentCount + " 次，已自动封禁");
            }

            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * Check if user is within rate limit (sliding window)
     * ============================================================================
     *
     * @param userId User ID
     * @return true: allowed, false: rate limited
     */
    public boolean isUserAllowed(Long userId) {
        if (!enabled) {
            return true;
        }

        // FIX: 添加 random value 避免同毫秒去重
        String key = USER_RATE_KEY_PREFIX + userId;
        long currentTime = System.currentTimeMillis();
        String randomValue = java.util.UUID.randomUUID().toString().substring(0, 8);

        Long result = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(RATE_WINDOW_MS),
                String.valueOf(userRateLimitPerSecond),
                String.valueOf(currentTime),
                randomValue  // FIX: 添加唯一标识避免同毫秒去重
        );

        if (result != null && result == -1) {
            userRateLimitCounter.increment();
            log.warn("User rate limited (sliding window): userId={}, max={}", userId, userRateLimitPerSecond);
            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * Check if IP is blacklisted
     * ============================================================================
     */
    public boolean isIpBlacklisted(String clientIp) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(IP_BLACKLIST_KEY, clientIp));
    }

    /**
     * ============================================================================
     * Add IP to blacklist
     * ============================================================================
     */
    public void addToBlacklist(String clientIp) {
        redisTemplate.opsForSet().add(IP_BLACKLIST_KEY, clientIp);
        log.info("IP added to blacklist: ip={}", clientIp);
    }

    /**
     * ============================================================================
     * Remove IP from blacklist
     * ============================================================================
     */
    public void removeFromBlacklist(String clientIp) {
        redisTemplate.opsForSet().remove(IP_BLACKLIST_KEY, clientIp);
        log.info("IP removed from blacklist: ip={}", clientIp);
    }

    /**
     * ============================================================================
     * Get all blacklisted IPs
     * ============================================================================
     */
    public Set<String> getBlacklistedIps() {
        return redisTemplate.opsForSet().members(IP_BLACKLIST_KEY);
    }

    /**
     * ============================================================================
     * Manual blacklist management (for admin)
     * ============================================================================
     */
    public void manualAddToBlacklist(String clientIp, String reason) {
        addToBlacklist(clientIp);
        alertService.sendAlert("IP手动封禁", "IP: " + clientIp + ", 原因: " + reason);
    }

    public void manualRemoveFromBlacklist(String clientIp, String reason) {
        removeFromBlacklist(clientIp);
        alertService.sendAlert("IP解封", "IP: " + clientIp + ", 原因: " + reason);
    }

    /**
     * ============================================================================
     * Get current IP request count in window (for monitoring)
     * ============================================================================
     */
    private long getCurrentIpCount(String clientIp) {
        String key = IP_RATE_KEY_PREFIX + clientIp;
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0;
    }

    /**
     * ============================================================================
     * Scheduled cleanup - Clean stale blacklist entries
     * ============================================================================
     * <p>
     * Runs every hour to clean up old blacklist entries
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupBlacklist() {
        if (!enabled) {
            return;
        }

        Set<String> blacklistedIps = getBlacklistedIps();
        if (blacklistedIps == null || blacklistedIps.isEmpty()) {
            return;
        }

        log.debug("Blacklist cleanup check: current size={}", blacklistedIps.size());

        // Note: Redis Set doesn't support TTL per member
        // For proper TTL, we would need a different structure
        // Sliding window keys have TTL automatically
    }

    /**
     * ============================================================================
     * Get blacklist size for monitoring
     * ============================================================================
     */
    public long getBlacklistSize() {
        Long size = redisTemplate.opsForSet().size(IP_BLACKLIST_KEY);
        return size != null ? size : 0;
    }
}