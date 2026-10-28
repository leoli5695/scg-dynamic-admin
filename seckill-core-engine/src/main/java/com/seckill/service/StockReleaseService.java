package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import com.seckill.redis.lua.StockKeys;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockReleaseService {
    private final OrderMapper orders;
    private final StringRedisTemplate redis;
    private final SeckillConfig config;
    private final MeterRegistry metrics;
    private final DefaultRedisScript<Long> script = createScript();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "seckill-stock-release"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    private static DefaultRedisScript<Long> createScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/order_stock_release.lua")); script.setResultType(Long.class); return script;
    }

    @PostConstruct public void start() {
        worker.scheduleWithFixedDelay(() -> {
            try { dispatch(); } catch (Exception e) { log.warn("Stock release scan deferred: {}", e.toString()); }
        }, 5, 5, TimeUnit.SECONDS);
    }
    public void dispatch() {
        for (SeckillOrder order : orders.selectPendingReleases()) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            try { if (!release(order)) orders.deferRelease(order.getUserId(), order.getOrderNo()); }
            catch (Exception e) {
                metrics.counter("seckill.stock.release.failed").increment();
                log.warn("Stock release pending: orderNo={}, error={}", order.getOrderNo(), e.toString());
                orders.deferRelease(order.getUserId(), order.getOrderNo());
            }
        }
    }
    public boolean release(SeckillOrder order) {
        if (order.getStockReleaseId() == null || !order.getStockReleaseId().equals(order.getOrderNo())
                || !Integer.valueOf(1).equals(order.getStockReleaseState())
                || !(Integer.valueOf(2).equals(order.getStatus()) || Integer.valueOf(3).equals(order.getStatus()))
                || order.getShardIndex() == null || order.getShardIndex() < 0
                || order.getShardIndex() >= config.getShardCount()) return false;
        boolean tagged = config.isStockHashTagEnabled();
        String ledger = StockKeys.released(order.getSeckillId(), tagged);
        Long result = redis.execute(script, List.of(
                StockKeys.prefix(order.getSeckillId(), tagged)+order.getShardIndex(),
                StockKeys.bought(order.getSeckillId(), tagged), StockKeys.users(order.getSeckillId(), tagged), ledger),
                order.getStockReleaseId(), order.getUserId().toString(), order.getQuantity().toString(), order.getShardIndex().toString());
        if (result == null || result < 1000) {
            metrics.counter("seckill.stock.release.failed").increment();
            return false;
        }
        orders.confirmRelease(order.getUserId(), order.getOrderNo(), order.getStockReleaseId());
        return true;
    }
    @PreDestroy public void shutdown() {
        closed = true; worker.shutdownNow();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
