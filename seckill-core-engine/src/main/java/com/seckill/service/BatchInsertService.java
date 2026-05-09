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
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 【攒批落库】批量订单写入服务
 * ============================================================================
 *
 * 功能:
 * 1. 内存队列攒批订单（达到阈值触发批量写入）
 * 2. 定时刷新（防止队列积压太久）
 * 3. 批量 insertBatch 提升写入性能
 * 4. 【关键】落库成功后才更新事务状态，防止状态抢跑
 *
 * 性能优化:
 * - 单条 insert: 每次 ~5ms，TPS ~2000
 * - 批量 insert: 每次 ~50ms（50条），TPS ~10000+
 *
 * 设计原则:
 * - 幂等检查在攒批前完成（单条消息处理时）
 * - 攒批期间数据在内存，应用崩溃可能丢失（但已持久化到 transaction_log）
 * - 批量写入失败时，逐条重试或告警
 * - 【关键】只有真正落库后才更新事务状态为 SUCCESS
 *
 * 风险控制（已修复内存膨胀问题）:
 * - 攒批超时时间：100ms（防止积压太久）
 * - 最大攒批数量：50 条（平衡性能和风险）
 * - 【新增】队列上限：200 条（防止内存无限膨胀）
 * - 【新增】满队列拒绝策略：触发告警 + 强制刷新
 * - 失败补偿：由 transaction_log 定时任务兜底
 *
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
    private final SeckillConfig seckillConfig;
    private final AlertService alertService;
    private final TransactionLogMapper transactionLogMapper;

    /**
     * 攒批队列 - 使用有界队列防止内存膨胀
     * 
     * 原问题：ConcurrentLinkedQueue 无界，秒杀流量洪峰时内存无限膨胀
     * 修复：改用 ArrayBlockingQueue，队列满时触发告警 + 强制刷新
     */
    private final ArrayBlockingQueue<SeckillOrder> orderQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * 队列容量上限（防止内存膨胀）
     * 队列满时触发告警 + 强制刷新，拒绝新订单
     */
    private static final int QUEUE_CAPACITY = 200;

    /**
     * 批量写入阈值（达到此数量触发批量写入）
     */
    private static final int BATCH_THRESHOLD = 50;

    /**
     * 最大攒批时间（超过此时间强制刷新，单位：毫秒）
     */
    private static final long MAX_BATCH_WAIT_MS = 100;

    /**
     * 上次刷新时间
     */
    private volatile long lastFlushTime = System.currentTimeMillis();

    /**
     * ============================================================================
     * 将订单加入攒批队列
     * ============================================================================
     *
     * 【关键】order 对象携带 transactionId（非持久化字段）
     * - 落库成功后用此字段更新事务状态
     *
     * 返回值：
     * - true: 加入成功
     * - false: 加入失败（队列满，触发告警）
     *
     * 【内存安全修复】：
     * - 队列满时触发告警 + 强制刷新，拒绝新订单
     * - 防止秒杀流量洪峰时内存无限膨胀
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
        log.debug("订单加入攒批队列: orderNo={}, transactionId={}, queueSize={}",
                order.getOrderNo(), order.getTransactionId(), currentSize);

        // 达到阈值触发批量写入
        if (currentSize >= BATCH_THRESHOLD) {
            flushBatch();
        }

        return true;
    }

    /**
     * ============================================================================
     * 定时刷新（每 100ms）
     * ============================================================================
     *
     * 目的：防止队列积压太久，即使未达到阈值也定期刷新
     */
    @Scheduled(fixedRate = 100)
    public void scheduledFlush() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastFlushTime;

        // 队列有数据且超过最大攒批时间
        int queueSize = orderQueue.size();
        if (queueSize > 0 && elapsed >= MAX_BATCH_WAIT_MS) {
            log.debug("定时刷新触发: queueSize={}, elapsedMs={}", queueSize, elapsed);
            flushBatch();
        }
    }

    /**
     * ============================================================================
     * 批量写入订单
     * ============================================================================
     *
     * 【关键设计】防止状态抢跑：
     * 1. 执行批量写入
     * 2. 成功后异步更新事务状态为 SUCCESS
     * 3. 失败时逐条重试，成功的更新状态，失败的保持 PROCESSING
     *
     * 这样保证了：只有真正落库的事务才标记成功
     */
    public void flushBatch() {
        // 检查队列是否有数据
        if (orderQueue.isEmpty()) {
            return;
        }

        // 取出所有订单（最多 BATCH_THRESHOLD * 2 条，防止一次取出太多）
        List<SeckillOrder> batch = new ArrayList<>();
        int maxBatchSize = BATCH_THRESHOLD * 2;

        // 使用 drainTo 更高效地批量取出
        orderQueue.drainTo(batch, maxBatchSize);

        if (batch.isEmpty()) {
            return;
        }

        log.info("批量写入订单: batchSize={}", batch.size());

        // 执行批量写入
        try {
            int inserted = orderMapper.insertBatch(batch);
            lastFlushTime = System.currentTimeMillis();

            log.info("批量写入成功: batchSize={}, inserted={}", batch.size(), inserted);

            // 【关键】落库成功后，异步批量更新事务状态
            asyncUpdateTransactionStatus(batch, true);

        } catch (Exception e) {
            log.error("批量写入失败: batchSize={}, error={}", batch.size(), e.getMessage());

            // 批量写入失败，尝试逐条重试
            retryInsertOneByOne(batch);
        }
    }

    /**
     * ============================================================================
     * 【关键】异步批量更新事务状态
     * ============================================================================
     *
     * 只有订单真正落库后才更新 transaction_log.status = SUCCESS
     *
     * 设计原则：
     * - 异步执行，不阻塞攒批流程
     * - 失败不影响主流程，由定时任务兜底
     *
     * @param batch 订单批次
     * @param success 是否批量写入成功
     */
    private void asyncUpdateTransactionStatus(List<SeckillOrder> batch, boolean success) {
        // 异步执行，不阻塞攒批线程
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
        }).exceptionally(ex -> {
            log.error("异步更新事务状态异常: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * ============================================================================
     * 批量写入失败时，逐条重试
     * ============================================================================
     *
     * 【关键设计】：
     * - 成功写入的订单：更新事务状态为 SUCCESS
     * - 失败的订单：保持 PROCESSING，由定时任务兜底
     */
    private void retryInsertOneByOne(List<SeckillOrder> batch) {
        List<SeckillOrder> successOrders = new ArrayList<>();
        int failCount = 0;

        for (SeckillOrder order : batch) {
            try {
                orderMapper.insert(order);
                successOrders.add(order);
                log.debug("单条写入成功: orderNo={}", order.getOrderNo());
            } catch (Exception e) {
                failCount++;
                log.error("单条写入失败: orderNo={}, error={}", order.getOrderNo(), e.getMessage());
            }
        }

        log.warn("逐条重试完成: success={}, fail={}", successOrders.size(), failCount);

        // 【关键】成功的订单更新事务状态
        if (!successOrders.isEmpty()) {
            asyncUpdateTransactionStatus(successOrders, true);
        }

        if (failCount > 0) {
            alertService.sendAlert("批量写入失败",
                    "批量写入失败后逐条重试，成功: " + successOrders.size() + "，失败: " + failCount + "，失败订单由 transaction_log 补偿");
        }
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
        order.setTransactionId(orderMessage.getTransactionId());  // 【关键】传递 transactionId
        return order;
    }

    /**
     * ============================================================================
     * 【关键】优雅停机：应用关闭前强制刷新队列
     * ============================================================================
     *
     * 防止 kill -9 或正常停机时丢失内存中的订单：
     * - Spring 容器关闭时自动调用此方法
     * - 强制刷新队列中所有待写入订单
     * - 多次刷新确保队列完全清空
     * - 超时保护：最多等待 30 秒
     *
     * 注意：kill -9 无法触发 @PreDestroy，需要外部信号（SIGTERM）
     * Docker/K8s 默认发送 SIGTERM，等待 terminationGracePeriodSeconds
     */
    @PreDestroy
    public void shutdown() {
        log.info("=== BatchInsertService 优雅停机开始 ===");
        
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
            
            // 等待短暂时间让异步任务完成
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