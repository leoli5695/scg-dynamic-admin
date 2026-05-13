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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * Anti-Scraping Service - 防刷机制
 * ============================================================================
 * <p>
 * SECURITY FIX (P1): Comprehensive anti-scraping mechanism
 * <p>
 * Features:
 * 1. IP-level rate limiting (sliding window with Lua)
 * 2. IP blacklist management (block malicious IPs with per-member TTL)
 * 3. User-level rate limiting (sliding window with Lua)
 * 4. Auto-blacklist trigger (auto-block IPs exceeding threshold)
 * <p>
 * Implementation:
 * - Redis Lua sliding window (精确限流，避免边界突刺)
 * - Redis-based blacklist storage (Hash结构支持per-member TTL)
 *   - Key: seckill:anti:ip_blacklist
 *   - Field: IP地址
 *   - Value: 过期时间戳(毫秒)
 * - Automatic cleanup of stale/expired blacklist entries
 * <p>
 * OPTIMIZATION (P2): Per-member TTL for blacklist
 * - 原问题: Redis Set不支持per-member TTL
 * - 解决方案: 使用Hash结构存储IP+过期时间戳
 * - 定时任务清理过期条目
 * - 检查黑名单时实时验证过期时间
 * <p>
 * Monitoring:
 * - seckill.anti_scraping.ip_blocked: IP blocked count
 * - seckill.anti_scraping.ip_rate_limit: IP rate limit trigger count
 * - seckill.anti_scraping.user_rate_limit: User rate limit trigger count
 * - seckill.anti_scraping.blacklist_expired: Blacklist entries expired count
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
    private final Counter blacklistExpiredCounter;  // OPTIMIZATION (P2): 新增过期计数器

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
        // OPTIMIZATION (P2): 新增黑名单过期计数器
        this.blacklistExpiredCounter = Counter.builder("seckill.anti_scraping.blacklist_expired")
                .description("Blacklist entries expired and removed count")
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
     * Check if IP is blacklisted (with TTL validation)
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): Hash结构支持per-member TTL
     * - 检查IP是否在黑名单Hash中
     * - 实时验证过期时间戳
     * - 过期则自动删除并返回false
     *
     * @param clientIp Client IP address
     * @return true: blacklisted and not expired, false: not blacklisted or expired
     */
    public boolean isIpBlacklisted(String clientIp) {
        // 从Hash中获取过期时间戳
        String expireTimestamp = redisTemplate.opsForHash().get(IP_BLACKLIST_KEY, clientIp);

        if (expireTimestamp == null) {
            return false;  // 不在黑名单中
        }

        // 检查是否已过期
        long expireTime = Long.parseLong(expireTimestamp);
        long currentTime = System.currentTimeMillis();

        if (currentTime > expireTime) {
            // 已过期，自动删除
            redisTemplate.opsForHash().delete(IP_BLACKLIST_KEY, clientIp);
            blacklistExpiredCounter.increment();
            log.info("IP blacklist entry expired and removed: ip={}, expireTime={}", clientIp, expireTime);
            return false;
        }

        return true;  // 在黑名单中且未过期
    }

    /**
     * ============================================================================
     * Add IP to blacklist with TTL
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): Hash结构存储IP+过期时间戳
     * - field: IP地址
     * - value: 过期时间戳(毫秒) = 当前时间 + blacklistTtlHours
     *
     * @param clientIp Client IP address
     */
    public void addToBlacklist(String clientIp) {
        // 计算过期时间戳
        long expireTimestamp = System.currentTimeMillis() + (blacklistTtlHours * 60 * 60 * 1000L);

        // 存入Hash结构
        redisTemplate.opsForHash().put(IP_BLACKLIST_KEY, clientIp, String.valueOf(expireTimestamp));

        log.info("IP added to blacklist with TTL: ip={}, ttlHours={}, expireTimestamp={}",
                clientIp, blacklistTtlHours, expireTimestamp);
    }

    /**
     * ============================================================================
     * Remove IP from blacklist
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): 使用HDEL删除Hash中的field
     *
     * @param clientIp Client IP address
     */
    public void removeFromBlacklist(String clientIp) {
        redisTemplate.opsForHash().delete(IP_BLACKLIST_KEY, clientIp);
        log.info("IP removed from blacklist: ip={}", clientIp);
    }

    /**
     * ============================================================================
     * Get all blacklisted IPs (excluding expired)
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): 从Hash获取所有IP，过滤过期的条目
     *
     * @return Set of valid (non-expired) blacklisted IPs
     */
    public Set<String> getBlacklistedIps() {
        // 获取Hash中所有field（IP地址）
        Set<Object> keys = redisTemplate.opsForHash().keys(IP_BLACKLIST_KEY);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }

        long currentTime = System.currentTimeMillis();

        // 过滤过期的IP
        return keys.stream()
                .map(Object::toString)
                .filter(ip -> {
                    String expireTimestamp = redisTemplate.opsForHash().get(IP_BLACKLIST_KEY, ip);
                    if (expireTimestamp == null) {
                        return false;
                    }
                    long expireTime = Long.parseLong(expireTimestamp);
                    return currentTime <= expireTime;  // 未过期
                })
                .collect(Collectors.toSet());
    }

    /**
     * ============================================================================
     * Get blacklist size (total entries, including expired)
     * ============================================================================
     * <p>
     * 用于监控，返回Hash总大小（不验证过期）
     *
     * @return Total blacklist entries count
     */
    public long getBlacklistSize() {
        Long size = redisTemplate.opsForHash().size(IP_BLACKLIST_KEY);
        return size != null ? size : 0;
    }

    /**
     * ============================================================================
     * Get valid blacklist size (excluding expired)
     * ============================================================================
     * <p>
     * 用于监控，返回有效黑名单大小
     *
     * @return Valid (non-expired) blacklist entries count
     */
    public long getValidBlacklistSize() {
        return getBlacklistedIps().size();
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
     * Scheduled cleanup - Clean expired blacklist entries
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): Hash结构清理过期条目
     * - 定时扫描黑名单Hash
     * - 删除过期的条目
     * - 记录清理数量到监控指标
     * <p>
     * Runs every hour to clean up expired blacklist entries
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupBlacklist() {
        if (!enabled) {
            return;
        }

        // 获取Hash中所有条目
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(IP_BLACKLIST_KEY);

        if (entries == null || entries.isEmpty()) {
            log.info("Blacklist cleanup: no entries found");
            return;
        }

        long currentTime = System.currentTimeMillis();
        int expiredCount = 0;

        // 遍历并删除过期条目
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String ip = entry.getKey().toString();
            String expireTimestamp = entry.getValue().toString();

            try {
                long expireTime = Long.parseLong(expireTimestamp);

                if (currentTime > expireTime) {
                    // 删除过期条目
                    redisTemplate.opsForHash().delete(IP_BLACKLIST_KEY, ip);
                    expiredCount++;
                    blacklistExpiredCounter.increment();
                    log.debug("Blacklist entry expired and removed: ip={}", ip);
                }
            } catch (NumberFormatException e) {
                // 无效的过期时间格式，删除条目
                log.warn("Invalid expire timestamp in blacklist: ip={}, value={}", ip, expireTimestamp);
                redisTemplate.opsForHash().delete(IP_BLACKLIST_KEY, ip);
            }
        }

        if (expiredCount > 0) {
            log.info("Blacklist cleanup completed: total={}, expired={}, removed={}",
                    entries.size(), expiredCount, expiredCount);
        } else {
            log.info("Blacklist cleanup: total={}, all valid", entries.size());
        }
    }
}