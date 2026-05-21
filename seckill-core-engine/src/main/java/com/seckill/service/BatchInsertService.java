package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 【攒批落库】批量订单写入服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 内存队列攒批订单（达到阈值触发批量写入）
 * 2. 定时刷新（防止队列积压太久）
 * 3. 批量 insertBatch 提升写入性能
 * 4. 【关键】落库成功后才更新事务状态，防止状态抢跑
 * <p>
 * 性能优化:
 * - 单条 insert: 每次 ~5ms，TPS ~2000
 * - 批量 insert: 每次 ~50ms（50条），TPS ~10000+
 * <p>
 * OPTIMIZATION (P2): 动态配置支持
 * - 队列容量、阈值、最大等待时间可通过配置调整
 * - 支持根据队列填充率动态调整阈值
 * - 配置项：seckill.batch.*
 * <p>
 * 【防止吞单设计】：
 * - 消费者入队时不标记事务成功
 * - flushBatch 落库成功后才更新 transaction_log.status = SUCCESS
 * - 如果宕机：内存订单丢失，但事务状态仍为 PROCESSING
 * - 定时任务发现超时事务，触发回滚或重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchInsertService {

    private final OrderMapper orderMapper;
    private final AlertService alertService;
    private final TransactionLogMapper transactionLogMapper;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // OPTIMIZATION (P2): 动态配置（setter注入）
    private SeckillConfig seckillConfig;

    /**
     * OPTIMIZATION (P2): 动态阈值
     * 默认值与原常量一致，可通过配置覆盖
     */
    private int queueCapacity = 200;
    private int batchThreshold = 50;
    private long maxBatchWaitMs = 100;
    private boolean dynamicAdjustEnabled = false;

    /**
     * 攒批队列 - 使用有界队列防止内存膨胀
     * OPTIMIZATION (P2): 队列容量从配置获取
     */
    private ArrayBlockingQueue<SeckillOrder> orderQueue;

    @Autowired
    public void setSeckillConfig(SeckillConfig seckillConfig) {
        this.seckillConfig = seckillConfig;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        // OPTIMIZATION (P2): 从配置获取参数
        if (seckillConfig != null && seckillConfig.getBatch() != null) {
            SeckillConfig.BatchConfig batchConfig = seckillConfig.getBatch();
            this.queueCapacity = batchConfig.getQueueCapacity();
            this.batchThreshold = batchConfig.getThreshold();
            this.maxBatchWaitMs = batchConfig.getMaxWaitMs();
            this.dynamicAdjustEnabled = batchConfig.isDynamicAdjustEnabled();
        }

        // 初始化队列（使用配置容量）
        this.orderQueue = new ArrayBlockingQueue<>(queueCapacity);

        // 注册监控指标
        io.micrometer.core.instrument.Gauge.builder("seckill.batch_queue.size", orderQueue, ArrayBlockingQueue::size)
                .description("攒批队列当前深度")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("seckill.batch.threshold", () -> batchThreshold)
                .description("批量写入阈值")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("seckill.batch.queue_capacity", () -> queueCapacity)
                .description("队列容量上限")
                .register(meterRegistry);

        log.info("BatchInsertService 初始化完成: queueCapacity={}, threshold={}, maxWaitMs={}, dynamicAdjust={}",
                queueCapacity, batchThreshold, maxBatchWaitMs, dynamicAdjustEnabled);
    }

    /**
     * 【P1-7修复】专用线程池，避免使用 ForkJoinPool.commonPool()
     * 用于异步更新事务状态，防止耗尽 JVM 公共线程池
     */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);

    /**
     * 上次刷新时间
     */
    private volatile long lastFlushTime = System.currentTimeMillis();

    /**
     * ============================================================================
     * OPTIMIZATION (P2): 动态调整阈值
     * ============================================================================
     * <p>
     * 根据队列深度动态调整批量写入阈值：
     * - 队列深度高时降低阈值，更快落库释放内存
     * - 队列深度低时提高阈值，减少DB压力
     * <p>
     * 仅在 dynamicAdjustEnabled=true 时生效
     */
    private int getDynamicThreshold() {
        if (!dynamicAdjustEnabled) {
            return batchThreshold;  // 固定阈值
        }

        int queueSize = orderQueue.size();
        double fillRatio = queueSize / (double) queueCapacity;

        // 队列填充率 > 70%: 降低阈值到30，快速落库
        if (fillRatio > 0.7) {
            return Math.max(30, batchThreshold - 20);
        }
        // 队列填充率 < 30%: 提高阈值到70，减少DB压力
        if (fillRatio < 0.3) {
            return Math.min(70, batchThreshold + 20);
        }
        // 正常范围：使用默认阈值
        return batchThreshold;
    }

    /**
     * ============================================================================
     * 将订单加入攒批队列
     * ============================================================================
     */
    public boolean addToBatch(SeckillOrder order) {
        // 使用有界队列的 offer 方法，队列满时返回 false
        boolean success = orderQueue.offer(order);

        if (!success) {
            // 队列满，触发告警 + 强制刷新
            int queueSize = orderQueue.size();
            log.warn("攒批队列已满，触发强制刷新: queueSize={}, orderNo={}",
                    queueSize, order.getOrderNo());
            alertService.sendAlert("攒批队列满",
                    "队列大小: " + queueSize + "，触发强制刷新，订单: " + order.getOrderNo());

            // 强制刷新
            flushBatch();

            // 再次尝试入队
            if (!orderQueue.offer(order)) {
                // 刷新后仍然满，拒绝订单（极端情况）
                log.error("攒批队列刷新后仍满，拒绝订单: orderNo={}", order.getOrderNo());
                return false;
            }
        }

        int currentSize = orderQueue.size();
        log.info("订单加入攒批队列: orderNo={}, transactionId={}, queueSize={}",
                order.getOrderNo(), order.getTransactionId(), currentSize);

        // OPTIMIZATION (P2): 使用动态阈值触发批量写入
        int currentThreshold = getDynamicThreshold();
        if (currentSize >= currentThreshold) {
            log.debug("达到阈值触发批量写入: currentSize={}, threshold={}", currentSize, currentThreshold);
            flushBatch();
        }

        return true;
    }

    /**
     * ============================================================================
     * 定时刷新（每 100ms）
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): 使用配置的最大等待时间
     */
    @Scheduled(fixedRate = 100)
    public void scheduledFlush() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastFlushTime;

        // 队列有数据且超过最大攒批时间
        int queueSize = orderQueue.size();
        if (queueSize > 0 && elapsed >= maxBatchWaitMs) {
            log.info("定时刷新触发: queueSize={}, elapsedMs={}", queueSize, elapsed);
            flushBatch();
        }
    }

    /**
     * ============================================================================
     * 批量写入订单
     * ============================================================================
     */
    public void flushBatch() {
        // 检查队列是否有数据
        if (orderQueue.isEmpty()) {
            return;
        }

        // 取出所有订单（最多 threshold * 2 条，防止一次取出太多）
        List<SeckillOrder> batch = new ArrayList<>();
        int maxBatchSize = batchThreshold * 2;

        // 使用 drainTo 更高效地批量取出
        orderQueue.drainTo(batch, maxBatchSize);

        if (batch.isEmpty()) {
            return;
        }

        log.info("批量写入订单: batchSize={}", batch.size());

        // 按 user_id 分组后再批量插入
        Map<Long, List<SeckillOrder>> groupedByUser = batch.stream()
                .collect(Collectors.groupingBy(SeckillOrder::getUserId));

        int totalInserted = 0;
        int totalFailed = 0;
        List<SeckillOrder> allSuccessOrders = new ArrayList<>();

        for (Map.Entry<Long, List<SeckillOrder>> entry : groupedByUser.entrySet()) {
            Long userId = entry.getKey();
            List<SeckillOrder> userOrders = entry.getValue();

            try {
                // 同一 user_id 的订单可以批量插入（路由到同一分片）
                int inserted = orderMapper.insertBatch(userOrders);
                totalInserted += inserted;
                allSuccessOrders.addAll(userOrders);

                log.info("分片批量写入成功: userId={}, count={}", userId, inserted);

            } catch (Exception e) {
                log.error("分片批量写入失败: userId={}, count={}, error={}",
                        userId, userOrders.size(), e.getMessage());

                // 该分片失败，尝试逐条重试
                for (SeckillOrder order : userOrders) {
                    try {
                        orderMapper.insert(order);
                        allSuccessOrders.add(order);
                        totalInserted++;
                        log.debug("单条写入成功: orderNo={}, userId={}", order.getOrderNo(), userId);
                    } catch (Exception singleEx) {
                        totalFailed++;
                        log.error("单条写入失败: orderNo={}, userId={}, error={}",
                                order.getOrderNo(), userId, singleEx.getMessage());
                    }
                }
            }
        }

        lastFlushTime = System.currentTimeMillis();

        log.info("批量写入完成: total={}, inserted={}, failed={}", batch.size(), totalInserted, totalFailed);

        // 【关键】落库成功后，异步批量更新事务状态
        if (!allSuccessOrders.isEmpty()) {
            asyncUpdateTransactionStatus(allSuccessOrders, true);
        }

        if (totalFailed > 0) {
            alertService.sendAlert("批量写入部分失败",
                    "成功: " + totalInserted + "，失败: " + totalFailed + "，失败订单由 transaction_log 补偿");
        }
    }

    /**
     * ============================================================================
     * 【关键】异步批量更新事务状态
     * ============================================================================
     */
    private void asyncUpdateTransactionStatus(List<SeckillOrder> batch, boolean success) {
        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int failCount = 0;

            for (SeckillOrder order : batch) {
                String transactionId = order.getTransactionId();
                if (transactionId == null || transactionId.isEmpty()) {
                    log.warn("订单缺少 transactionId，无法更新事务状态: orderNo={}", order.getOrderNo());
                    continue;
                }

                try {
                    TransactionLog transactionLog = transactionLogMapper.selectByTransactionId(transactionId);
                    if (transactionLog != null) {
                        transactionLog.setStatus(success ? TransactionStatus.SUCCESS.getCode() : TransactionStatus.FAILED.getCode());
                        transactionLog.setUpdateTime(LocalDateTime.now());
                        transactionLogMapper.updateById(transactionLog);
                        successCount++;

                        log.debug("事务状态更新成功: transactionId={}, status={}",
                                transactionId, success ? "SUCCESS" : "FAILED");
                    } else {
                        log.warn("事务日志不存在: transactionId={}", transactionId);
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("事务状态更新失败: transactionId={}, error={}", transactionId, e.getMessage());
                }
            }

            log.info("批量更新事务状态完成: success={}, fail={}", successCount, failCount);

            if (failCount > 0) {
                alertService.sendAlert("事务状态更新异常",
                        "批量更新事务状态失败数量: " + failCount + "，由定时任务兜底");
            }
        }, asyncExecutor).exceptionally(ex -> {
            log.error("异步更新事务状态异常: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * ============================================================================
     * 获取队列大小（用于监控）
     * ============================================================================
     */
    public int getQueueSize() {
        return orderQueue.size();
    }

    /**
     * ============================================================================
     * 从 OrderMessage 创建 SeckillOrder
     * ============================================================================
     */
    public SeckillOrder createOrderFromMessage(OrderMessage orderMessage) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderMessage.getOrderNo());
        order.setUserId(orderMessage.getUserId());
        order.setSeckillId(orderMessage.getSeckillId());
        order.setProductId(orderMessage.getProductId());
        order.setQuantity(orderMessage.getQuantity());
        order.setTotalAmount(orderMessage.getTotalAmount());
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setShardIndex(orderMessage.getShardIndex());
        order.setCreateTime(java.time.LocalDateTime.now());
        order.setUpdateTime(java.time.LocalDateTime.now());
        order.setTransactionId(orderMessage.getTransactionId());
        return order;
    }

    /**
     * ============================================================================
     * 【关键】优雅停机：应用关闭前强制刷新队列
     * ============================================================================
     */
    @PreDestroy
    public void shutdown() {
        log.info("=== BatchInsertService 优雅停机开始 ===");

        // 先关闭专用线程池
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                log.warn("asyncExecutor 强制关闭");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        int remainingOrders = orderQueue.size();
        if (remainingOrders == 0) {
            log.info("攒批队列已空，无需刷新");
            return;
        }

        log.warn("攒批队列有 {} 条待写入订单，开始强制刷新", remainingOrders);

        // 多次刷新确保队列完全清空
        int maxAttempts = 10;
        int attempt = 0;

        while (orderQueue.size() > 0 && attempt < maxAttempts) {
            attempt++;
            log.info("优雅停机刷新第 {} 次，剩余订单: {}", attempt, orderQueue.size());

            flushBatch();

            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                log.warn("优雅停机等待被中断");
                Thread.currentThread().interrupt();
                break;
            }
        }

        int finalRemaining = orderQueue.size();
        if (finalRemaining > 0) {
            log.error("=== 优雅停机警告：仍有 {} 条订单未能写入数据库 ===", finalRemaining);
            alertService.sendAlert("优雅停机订单丢失警告",
                    "停机时仍有 " + finalRemaining + " 条订单在内存队列中未能落库。" +
                            "这些订单的事务状态为 PROCESSING，将由补偿任务处理。");
        } else {
            log.info("=== BatchInsertService 优雅停机完成，所有订单已落库 ===");
        }
    }
}