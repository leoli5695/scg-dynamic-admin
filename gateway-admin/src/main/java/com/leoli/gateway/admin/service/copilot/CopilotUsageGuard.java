package com.leoli.gateway.admin.service.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * AI Copilot 成本闸门
 * ============================================================================
 * <p>
 * AI 调用是真金白银的 token 花费，且任何 ADMIN（{@code GET /explain} 甚至只需登录）
 * 都能触发。这里给 copilot 自身的 AI 消耗加两道闸，<b>与网关业务流量的
 * TokenQuotaService 完全独立</b>（那是产品功能，这是助手自用成本，键空间也分开）：
 * <ol>
 *   <li><b>请求限流</b>：每用户每分钟最多 N 次 AI 请求，防抖/防刷；</li>
 *   <li><b>每日 token 预算</b>：每用户每天累计 token 上限，超出当天不再放行。</li>
 * </ol>
 * <p>
 * FIX (点6 单机内存态): 计数改为优先落 <b>admin 自己的 Redis</b>（原子 INCR / INCRBY + TTL），
 * 解决「多 Pod 各算各的（预算被放大 N 倍）」与「重启即清零」。Redis 缺失或抖动时
 * 自动降级回内存态（与 TokenQuotaService 的可选 Redis posture 一致），闸门不会因 Redis 故障而失效。
 * <p>
 * 记账仍是事后式（token 只有 LLM 返回后才可知，无法真正预占）；共享原子计数已把
 * 并发击穿的窗口收敛到「同一时刻在途请求的成本」，且下一次检查即自纠。
 * 限额走配置，设为 &lt;=0 表示关闭对应闸门。按 operator 维度统计。
 *
 * @author leoli
 */
@Slf4j
@Component
public class CopilotUsageGuard {

    /** 每用户每分钟最大 AI 请求数；<=0 关闭限流。 */
    private final int rateLimitPerMinute;

    /** 每用户每日 token 预算；<=0 关闭预算闸。 */
    private final long dailyTokenBudget;

    /**
     * admin 侧可选 Redis（与业务配额共用连接、独立键空间）。缺失时降级为内存态。
     * 用字段注入而非构造器参数，保持 {@code (int, long)} 构造器供单元测试与降级路径使用。
     */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** operator -> [分钟纪元, 该分钟内计数]（仅内存降级态使用）。 */
    private final Map<String, MinuteCounter> rateCounters = new ConcurrentHashMap<>();

    /** "operator|yyyy-MM-dd" -> 当日累计 token（仅内存降级态使用）。 */
    private final Map<String, AtomicLong> dailyTokens = new ConcurrentHashMap<>();

    private static final String DAILY_PREFIX = "copilot:usage:daily:";
    private static final String RATE_PREFIX = "copilot:usage:rate:";
    /** 分钟窗口计数存活 2 分钟，足够覆盖一个分钟纪元并自然滚动。 */
    private static final long RATE_TTL_SECONDS = 120L;

    /** 原子 INCR + 首次设 TTL，返回自增后的计数。 */
    private final DefaultRedisScript<Long> rateScript = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    /** 原子 INCRBY + 刷新到当日结束的 TTL，返回累计值。 */
    private final DefaultRedisScript<Long> dailyScript = new DefaultRedisScript<>(
            "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
                    + "return v",
            Long.class);

    public CopilotUsageGuard(
            @Value("${gateway.copilot.rate-limit-per-minute:30}") int rateLimitPerMinute,
            @Value("${gateway.copilot.daily-token-budget:500000}") long dailyTokenBudget) {
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.dailyTokenBudget = dailyTokenBudget;
        log.info("CopilotUsageGuard initialized: rateLimit/min={}, dailyTokenBudget={}",
                rateLimitPerMinute, dailyTokenBudget);
    }

    private static final class MinuteCounter {
        volatile long minuteEpoch;
        final AtomicInteger count = new AtomicInteger();
    }

    /**
     * 请求前检查是否放行。放行时会占用一个当分钟的限流名额。
     *
     * @param operator 操作者（用户名），null 归并为 "anonymous"
     * @return 空表示放行；否则为拒绝原因（可直接回给用户）
     */
    public Optional<String> checkRequestAllowed(String operator) {
        String key = normalize(operator);

        // 1. 每日 token 预算
        if (dailyTokenBudget > 0) {
            long used = dailyUsed(key);
            if (used >= dailyTokenBudget) {
                log.warn("Copilot daily token budget exceeded for {}: {} >= {}", key, used, dailyTokenBudget);
                return Optional.of(String.format(
                        "今日 AI 助手 token 预算已用尽（%d/%d），请明天再试或联系管理员提高额度。",
                        used, dailyTokenBudget));
            }
        }

        // 2. 每分钟请求限流（原子自增，返回自增后计数；> 上限即拒）
        if (rateLimitPerMinute > 0) {
            long count = incrementRate(key);
            if (count > rateLimitPerMinute) {
                log.warn("Copilot rate limit exceeded for {}: {}/min", key, rateLimitPerMinute);
                return Optional.of(String.format(
                        "AI 助手请求过于频繁（每分钟上限 %d 次），请稍后再试。", rateLimitPerMinute));
            }
        }
        return Optional.empty();
    }

    /**
     * 记录一次 AI 调用消耗的 token（累加到当日预算）。
     *
     * @param operator 操作者
     * @param tokens   本次消耗 token 数（<=0 忽略）
     */
    public void recordUsage(String operator, long tokens) {
        if (tokens <= 0) {
            return;
        }
        String key = normalize(operator);
        if (redisTemplate != null) {
            try {
                redisTemplate.execute(dailyScript,
                        Collections.singletonList(dailyKey(key)),
                        String.valueOf(tokens),
                        String.valueOf(dailyTtlSeconds()));
                return;
            } catch (Exception e) {
                log.warn("Copilot usage record via Redis failed, falling back to memory for {}: {}",
                        key, e.getMessage());
            }
        }
        dailyTokens.computeIfAbsent(dayKey(key), k -> new AtomicLong()).addAndGet(tokens);
    }

    /** 某用户当日已用 token。 */
    public long dailyUsed(String operator) {
        String key = normalize(operator);
        if (redisTemplate != null) {
            try {
                String value = redisTemplate.opsForValue().get(dailyKey(key));
                return value != null ? Long.parseLong(value) : 0L;
            } catch (Exception e) {
                log.warn("Copilot usage read via Redis failed, falling back to memory for {}: {}",
                        key, e.getMessage());
            }
        }
        AtomicLong v = dailyTokens.get(dayKey(key));
        return v != null ? v.get() : 0L;
    }

    /** 原子自增当分钟计数并返回自增后的值；Redis 优先，缺失/异常降级内存。 */
    private long incrementRate(String key) {
        long nowMinute = System.currentTimeMillis() / 60_000L;
        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.execute(rateScript,
                        Collections.singletonList(RATE_PREFIX + key + ":" + nowMinute),
                        String.valueOf(RATE_TTL_SECONDS));
                return count != null ? count : 0L;
            } catch (Exception e) {
                log.warn("Copilot rate limit via Redis failed, falling back to memory for {}: {}",
                        key, e.getMessage());
            }
        }
        MinuteCounter mc = rateCounters.computeIfAbsent(key, k -> new MinuteCounter());
        synchronized (mc) {
            if (mc.minuteEpoch != nowMinute) {
                mc.minuteEpoch = nowMinute;
                mc.count.set(0);
            }
            return mc.count.incrementAndGet();
        }
    }

    private static String normalize(String operator) {
        return operator != null && !operator.isEmpty() ? operator : "anonymous";
    }

    private String dailyKey(String key) {
        return DAILY_PREFIX + key + ":" + LocalDate.now();
    }

    private String dayKey(String operator) {
        return operator + "|" + LocalDate.now();
    }

    /** TTL 到当日结束（+60s 余量），使日计数自然滚动、无需清理任务。 */
    private long dailyTtlSeconds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfDay = now.toLocalDate().atTime(23, 59, 59);
        return Duration.between(now, endOfDay).getSeconds() + 60;
    }

    /** 每天清理过期的内存态计数（Redis 态由 TTL 自然过期），避免 map 无限增长。 */
    @Scheduled(fixedDelay = 3_600_000L)
    public void evictStaleCounters() {
        String today = LocalDate.now().toString();
        int before = dailyTokens.size();
        dailyTokens.keySet().removeIf(k -> !k.endsWith(today));
        rateCounters.clear();
        int removed = before - dailyTokens.size();
        if (removed > 0) {
            log.debug("Evicted {} stale copilot usage counters", removed);
        }
    }
}
