package com.leoli.gateway.admin.service.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
 * TokenQuotaService 完全独立</b>（那是产品功能，这是助手自用成本）：
 * <ol>
 *   <li><b>请求限流</b>：每用户每分钟最多 N 次 AI 请求，防抖/防刷；</li>
 *   <li><b>每日 token 预算</b>：每用户每天累计 token 上限，超出当天不再放行。</li>
 * </ol>
 * 内存态实现（重启即清零），对管理台这种低频、少量用户的场景足够；限额走配置，
 * 设为 &lt;=0 表示关闭对应闸门。按 operator 维度统计，operator 由调用方从安全上下文取。
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

    /** operator -> [分钟纪元, 该分钟内计数]。 */
    private final Map<String, MinuteCounter> rateCounters = new ConcurrentHashMap<>();

    /** "operator|yyyy-MM-dd" -> 当日累计 token。 */
    private final Map<String, AtomicLong> dailyTokens = new ConcurrentHashMap<>();

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
        String key = operator != null && !operator.isEmpty() ? operator : "anonymous";

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

        // 2. 每分钟请求限流
        if (rateLimitPerMinute > 0) {
            long nowMinute = System.currentTimeMillis() / 60_000L;
            MinuteCounter mc = rateCounters.computeIfAbsent(key, k -> new MinuteCounter());
            synchronized (mc) {
                if (mc.minuteEpoch != nowMinute) {
                    mc.minuteEpoch = nowMinute;
                    mc.count.set(0);
                }
                if (mc.count.get() >= rateLimitPerMinute) {
                    log.warn("Copilot rate limit exceeded for {}: {}/min", key, rateLimitPerMinute);
                    return Optional.of(String.format(
                            "AI 助手请求过于频繁（每分钟上限 %d 次），请稍后再试。", rateLimitPerMinute));
                }
                mc.count.incrementAndGet();
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
        String key = operator != null && !operator.isEmpty() ? operator : "anonymous";
        dailyTokens.computeIfAbsent(dayKey(key), k -> new AtomicLong()).addAndGet(tokens);
    }

    /** 某用户当日已用 token。 */
    public long dailyUsed(String operator) {
        String key = operator != null && !operator.isEmpty() ? operator : "anonymous";
        AtomicLong v = dailyTokens.get(dayKey(key));
        return v != null ? v.get() : 0L;
    }

    private String dayKey(String operator) {
        return operator + "|" + LocalDate.now();
    }

    /** 每天清理过期的当日计数，避免 map 无限增长。 */
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
