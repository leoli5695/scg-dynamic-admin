package com.example.mygateway.ratelimiter;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.*;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeSlot;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowSlot;
import com.alibaba.csp.sentinel.slots.logger.LogSlot;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;
import com.alibaba.csp.sentinel.slots.statistic.StatisticSlot;
import com.alibaba.csp.sentinel.slots.system.SystemSlot;
import com.example.mygateway.model.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sentinel Slot Chain Builder with Redis Rate Limiting
 * <p>
 * 流程：
 * 1. RedisRateLimitSlot - 优先 Redis 全局限流
 * 2. FlowSlot - 降级到 Sentinel 单机限流
 */
@Component
public class RedisRateLimitSlotChainBuilder implements SlotChainBuilder {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitSlotChainBuilder.class);

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private RateLimiterConfigManager configManager;

    @Override
    public ProcessorSlotChain build() {
        DefaultProcessorSlotChain chain = new DefaultProcessorSlotChain();

        // 优先：Redis 全局限流 Slot
        chain.addLast(new RedisGlobalRateLimitSlot(redisRateLimiter, configManager));

        // Sentinel 默认 Slot
        chain.addLast(new NodeSelectorSlot());
        chain.addLast(new LogSlot());
        chain.addLast(new StatisticSlot());
        chain.addLast(new FlowSlot());      // Sentinel 本地限流（降级用）
        chain.addLast(new DegradeSlot());
        chain.addLast(new SystemSlot());

        return chain;
    }

    /**
     * Redis 全局限流 Slot
     * 优先级高于 FlowSlot
     */
    public static class RedisGlobalRateLimitSlot extends AbstractLinkedProcessorSlot {

        private final RedisRateLimiter redisRateLimiter;
        private final RateLimiterConfigManager configManager;

        public RedisGlobalRateLimitSlot(RedisRateLimiter redisRateLimiter,
                                        RateLimiterConfigManager configManager) {
            this.redisRateLimiter = redisRateLimiter;
            this.configManager = configManager;
        }

        @Override
        public void entry(Context context, ResourceWrapper resourceWrapper, Object o, int i, boolean b, Object... objects) throws Throwable {
            String routeId = context.getName();

            // 获取限流配置
            RateLimiterConfig config = configManager.getRateLimiterConfig(routeId);

            if (config == null || !config.isEnabled()) {
                // 无配置，跳过，让后续 Slot 处理
                fireNext(context, resourceWrapper, o, i, b, objects);
                return;
            }

            // ========== 优先：Redis 全局限流 ==========
            if (redisRateLimiter.isRedisAvailable() && config.getRedisQps() > 0) {
                String rateLimitKey = buildKey(routeId, context, config);

                RedisRateLimiter.RateLimitResult result = redisRateLimiter.tryAcquire(
                        rateLimitKey, config.getRedisQps(), config.getRedisBurstCapacity());

                if (result.isAllowed()) {
                    // Redis 放行，传递上下文让后续 Slot 统计
                    context.setOrigin(rateLimitKey);
                    fireNext(context, resourceWrapper, o, i, b, objects);
                    return;
                } else if (!result.isFallback()) {
                    // Redis 拒绝，抛出 FlowException
                    log.warn("Redis rate limit rejected: {}", rateLimitKey);
                    throw new FlowException("Redis rate limit exceeded: " + rateLimitKey);
                }
                // 降级到 Sentinel
            }

            // ========== 降级到 Sentinel ==========
            // 需要手动埋点，让 Sentinel 进行限流判断
            log.debug("Fallback to Sentinel for route: {}", routeId);
            
            try {
                // 使用 SphU.entry 让 Sentinel 进行限流埋点
                com.alibaba.csp.sentinel.Entry entry = com.alibaba.csp.sentinel.SphU.entry(routeId);
                // 放行，让后续 Slot 统计
                fireNext(context, resourceWrapper, o, i, b, objects);
            } catch (BlockException e) {
                // Sentinel 限流拒绝
                log.warn("Sentinel rate limit rejected for route: {}", routeId);
                throw e;
            } catch (Exception e) {
                // 其他异常，记录并放行
                fireNext(context, resourceWrapper, o, i, b, objects);
            }
        }

        @Override
        public void exit(Context context, ResourceWrapper resourceWrapper, int i, Object... objects) {
            // Sentinel 会自动处理 exit
        }

        private String buildKey(String routeId, Context context, RateLimiterConfig config) {
            StringBuilder key = new StringBuilder(config.getKeyPrefix()).append(routeId);

            String keyType = config.getKeyType();
            if ("ip".equalsIgnoreCase(keyType)) {
                // 优先使用 X-Forwarded-For 或 X-Real-IP
                String clientIp = extractClientIp(context);
                key.append(":").append(clientIp);
            } else if ("user".equalsIgnoreCase(keyType)) {
                String userId = context.getOrigin();
                key.append(":").append(userId != null ? userId : "anonymous");
            }
            // route: 只用 routeId

            return key.toString();
        }

        private String extractClientIp(Context context) {
            // 从 Entry 中尝试获取 origin（通常是调用方标识）
            String origin = context.getOrigin();
            if (origin != null && !origin.isEmpty()) {
                return origin;
            }
            return "unknown";
        }

        private void fireNext(Context context, ResourceWrapper resourceWrapper, Object o, int i, boolean b, Object... objects) throws Throwable {
            // 调用下一个 Slot
            if (this.getNext() != null) {
                this.getNext().entry(context, resourceWrapper, o, i, b, objects);
            }
        }
    }
}