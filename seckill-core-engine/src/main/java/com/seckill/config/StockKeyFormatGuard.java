package com.seckill.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockKeyFormatGuard {
    private final SeckillConfig config;
    private final Environment environment;
    @PostConstruct public void validate() {
        if ((environment.containsProperty("spring.data.redis.cluster.nodes")
                || environment.containsProperty("spring.data.redis.cluster.nodes[0]"))
                && !config.isStockHashTagEnabled())
            throw new IllegalStateException("Redis Cluster requires migrated stock keys and seckill.stock-hash-tag-enabled=true");
        if (config.getOrderRoutingVersion() != 1 && config.getOrderRoutingVersion() != 2)
            throw new IllegalArgumentException("Order routing version must be 1 or 2");
    }
}
