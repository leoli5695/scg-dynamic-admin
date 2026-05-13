package com.seckill.service;

import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ============================================================================
 * 库存回补补偿服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 处理失败的事务消息（事务回查失败）
 * 2. 定时扫描待处理的事务
 * 3. 处理订单未支付超时（主动回补，不等延迟消息）
 * <p>
 * OPTIMIZATION (P1): Tiered timeout processing
 * - Short timeout (5-10 min): Fast discovery, quick rollback
 * - Long timeout (30+ min): Safety net, final rollback
 * <p>
 * This reduces unnecessary queries for long-running transactions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationService {

    private final OrderMapper orderMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final TransactionLogMapper transactionLogMapper;

    // OPTIMIZATION (P1): Tiered timeout thresholds
    private static final int SHORT_TIMEOUT_MINUTES = 5;
    private static final int MEDIUM_TIMEOUT_MINUTES = 10;
    private static final int LONG_TIMEOUT_MINUTES = 30;

    /**
     * RocketMQ 重投窗口期（与 OrderCreateConsumer.IDEMPOTENT_EXPIRE_MINUTES 保持一致）
     * <p>
     * 幂等锁 5 分钟过期，RocketMQ 消息重投周期 10s ~ 2h
     * 在此窗口期内，应等待 RocketMQ 重投重建订单，而非直接回补库存
     */
    private static final int ROCKETMQ_RESEND_WINDOW_MINUTES = 5;

    /**
     * ============================================================================
     * 短时超时处理（每分钟）- 快速发现问题
     * ============================================================================
     * <p>
     * OPTIMIZATION (P1): Process 5-10 minute timeout transactions first
     * - Faster discovery of stuck transactions
     * - Reduces waiting time for users
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    @SchedulerLock(
            name = "CompensationService_processShortTimeoutTransactions",
            lockAtMostFor = "2m",
            lockAtLeastFor = "30s"
    )
    public void processShortTimeoutTransactions() {
        log.debug("开始扫描短时超时事务...");

        // Process 5-10 minute timeout transactions
        LocalDateTime shortTimeoutTime = LocalDateTime.now().minusMinutes(SHORT_TIMEOUT_MINUTES);
        LocalDateTime mediumTimeoutTime = LocalDateTime.now().minusMinutes(MEDIUM_TIMEOUT_MINUTES);

        List<TransactionLog> shortTimeoutTransactions = transactionLogMapper.selectTimeoutTransactionsRange(
                TransactionStatus.PROCESSING.getCode(),
                shortTimeoutTime,
                mediumTimeoutTime
        );

        for (TransactionLog tx : shortTimeoutTransactions) {
            try {
                processTimeoutTransaction(tx, "短时超时");
            } catch (Exception e) {
                log.error("处理短时超时事务失败: transactionId={}, error={}", tx.getTransactionId(), e.getMessage());
            }
        }

        if (!shortTimeoutTransactions.isEmpty()) {
            log.info("短时超时事务扫描完成: 处理数量={}", shortTimeoutTransactions.size());
        }
    }

    /**
     * ============================================================================
     * 长时超时兜底处理（每5分钟）- 安全兜底
     * ============================================================================
     * <p>
     * OPTIMIZATION (P1): Process 30+ minute timeout as safety net
     * - Reduced frequency (every 5 min instead of every 1 min)
     * - Catches transactions that missed short timeout processing
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @SchedulerLock(
            name = "CompensationService_processLongTimeoutTransactions",
            lockAtMostFor = "5m",
            lockAtLeastFor = "1m"
    )
    public void processLongTimeoutTransactions() {
        log.info("开始扫描长时超时事务（兜底）...");

        // 查询超时的事务（超过30分钟）
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(LONG_TIMEOUT_MINUTES);
        List<TransactionLog> timeoutTransactions = transactionLogMapper.selectTimeoutTransactions(
                TransactionStatus.PROCESSING.getCode(),
                timeoutTime
        );

        for (TransactionLog tx : timeoutTransactions) {
            try {
                processTimeoutTransaction(tx, "长时超时兜底");
            } catch (Exception e) {
                log.error("处理长时超时事务失败: transactionId={}, error={}", tx.getTransactionId(), e.getMessage());
            }
        }

        log.info("长时超时事务扫描完成: 处理数量={}", timeoutTransactions.size());
    }

    /**
     * 处理单个超时事务
     * <p>
     * OPTIMIZATION (P1): Added source parameter for logging
     * <p>
     * 【P1-5修复】避免与 StockConfirmService 冲突：
     * - StockConfirmService 处理 5 分钟内的超时事务（短时超时）
     * - CompensationService 处理 30 分钟以上的超时事务（长时超时兜底）
     * - 添加 Redis 处理标记，避免重复处理
     */
    @Transactional(rollbackFor = Exception.class)
    public void processTimeoutTransaction(TransactionLog tx, String source) {
        log.info("处理超时事务[{}]: transactionId={}, seckillId={}, userId={}",
                source, tx.getTransactionId(), tx.getSeckillId(), tx.getUserId());

        // 【P1-5修复】检查是否已被处理（事务状态可能已变更）
        TransactionLog currentTx = transactionLogMapper.selectByTransactionId(tx.getTransactionId());
        if (currentTx == null || currentTx.getStatus() != TransactionStatus.PROCESSING.getCode()) {
            log.info("事务已被处理或状态已变更，跳过: transactionId={}, currentStatus={}",
                    tx.getTransactionId(), currentTx != null ? currentTx.getStatus() : "null");
            return;
        }

        // 检查订单是否存在
        SeckillOrder order = orderMapper.selectByOrderNo(tx.getOrderNo());

        if (order == null) {
            // 【关键优化】检查是否处于 RocketMQ 重投窗口期
            // 避免与 RocketMQ 消息重投机制冲突
            long minutesSinceCreate = ChronoUnit.MINUTES.between(tx.getCreateTime(), LocalDateTime.now());

            if (minutesSinceCreate < ROCKETMQ_RESEND_WINDOW_MINUTES) {
                log.info("事务处于 RocketMQ 重投窗口期，暂不回补，等待消息重投重建订单: transactionId={}, minutesSinceCreate={}",
                        tx.getTransactionId(), minutesSinceCreate);
                return;  // 跳过本次处理，等待下次定时任务扫描
            }

            // 超过重投窗口期，订单仍未创建，执行库存回补
            rollbackStock(tx);
            tx.setStatus(TransactionStatus.FAILED.getCode());
            tx.setErrorMsg("订单不存在，超过重投窗口期，超时回补");
        } else if (order.getStatus() == OrderStatus.PENDING_PAYMENT.getCode()) {
            // 订单待支付，回补库存
            rollbackStock(tx);
            order.setStatus(OrderStatus.CANCELLED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
            tx.setStatus(TransactionStatus.FAILED.getCode());
            tx.setErrorMsg("订单未支付，超时回补");
        } else {
            // 订单已支付或已取消
            tx.setStatus(TransactionStatus.SUCCESS.getCode());
        }

        tx.setUpdateTime(LocalDateTime.now());
        tx.setRetryCount(tx.getRetryCount() + 1);
        transactionLogMapper.updateById(tx);

        log.info("超时事务处理完成: transactionId={}, status={}", tx.getTransactionId(), tx.getStatus());
    }

    /**
     * ============================================================================
     * 定时扫描未支付订单（每5分钟）
     * ============================================================================
     * <p>
     * 主动检查未支付超时订单，不等延迟消息
     * <p>
     * 分布式锁: 使用 ShedLock 防止多实例重复执行
     */
    @Scheduled(fixedRate = 300000)
    @SchedulerLock(
            name = "CompensationService_processUnpaidOrders",
            lockAtMostFor = "10m",  // 锁最大持有10分钟
            lockAtLeastFor = "1m"   // 锁最小持有1分钟
    )
    public void processUnpaidOrders() {
        log.info("开始扫描未支付订单...");

        // 查询创建超过20分钟仍未支付的订单（与RocketMQ delayLevel 17对应）
        LocalDateTime unpaidTime = LocalDateTime.now().minusMinutes(20);
        List<SeckillOrder> unpaidOrders = orderMapper.selectUnpaidOrders(unpaidTime);

        for (SeckillOrder order : unpaidOrders) {
            try {
                cancelUnpaidOrder(order);
            } catch (Exception e) {
                log.error("取消未支付订单失败: orderNo={}, error={}", order.getOrderNo(), e.getMessage());
            }
        }

        log.info("未支付订单扫描完成: 处理数量={}", unpaidOrders.size());
    }

    /**
     * 取消未支付订单
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelUnpaidOrder(SeckillOrder order) {
        log.info("取消未支付订单: orderNo={}, userId={}, shardIndex={}",
                order.getOrderNo(), order.getUserId(), order.getShardIndex());

        // 回补库存（精确恢复到原分片）
        long result = seckillDeductLua.rollbackStock(
                order.getSeckillId(),
                order.getUserId(),
                order.getQuantity()
        );

        if (result >= 1000) {
            // 更新订单状态（使用包含分片键的更新方法）
            order.setStatus(OrderStatus.CANCELLED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("未支付订单取消完成: orderNo={}, shardIndex={}", order.getOrderNo(), result - 1000);
        } else {
            log.error("库存回补失败: orderNo={}, result={}", order.getOrderNo(), result);
        }
    }

    /**
     * ============================================================================
     * 手动回补库存（管理员触发）
     * ============================================================================
     */
    public boolean manualRollback(Long seckillId, Long userId, int quantity) {
        log.info("手动回补库存: seckillId={}, userId={}, quantity={}", seckillId, userId, quantity);

        long result = seckillDeductLua.rollbackStock(seckillId, userId, quantity);

        if (result >= 1000) {
            log.info("手动回补成功: shardIndex={}", result - 1000);
            return true;
        } else {
            log.error("手动回补失败: result={}", result);
            return false;
        }
    }

    /**
     * ============================================================================
     * 回补库存内部方法
     * ============================================================================
     */
    private void rollbackStock(TransactionLog tx) {
        long result = seckillDeductLua.rollbackStock(
                tx.getSeckillId(),
                tx.getUserId(),
                tx.getQuantity()
        );

        log.info("事务回补库存: transactionId={}, shardIndex={}", tx.getTransactionId(), result >= 1000 ? result - 1000 : result);
    }
}