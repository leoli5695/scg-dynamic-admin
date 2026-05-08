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
import java.util.List;

/**
 * ============================================================================
 * 库存回补补偿服务
 * ============================================================================
 * 
 * 功能:
 * 1. 处理失败的事务消息（事务回查失败）
 * 2. 定时扫描待处理的事务
 * 3. 处理订单未支付超时（主动回补，不等延迟消息）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationService {

    private final TransactionLogMapper transactionLogMapper;
    private final OrderMapper orderMapper;
    private final SeckillDeductLua seckillDeductLua;

    /**
     * ============================================================================
     * 定时扫描处理中的事务（每分钟）
     * ============================================================================
     * 
     * 处理超时的PROCESSING状态事务
     * 
     * 分布式锁: 使用 ShedLock 防止多实例重复执行
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(
            name = "CompensationService_processTimeoutTransactions",
            lockAtMostFor = "5m",   // 锁最大持有5分钟（任务本身不超过1分钟）
            lockAtLeastFor = "30s"  // 锁最小持有30秒（防止短任务重复执行）
    )
    public void processTimeoutTransactions() {
        log.info("开始扫描超时事务...");

        // 查询超时的事务（超过30分钟）
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(30);
        List<TransactionLog> timeoutTransactions = transactionLogMapper.selectTimeoutTransactions(
                TransactionStatus.PROCESSING.getCode(),
                timeoutTime
        );

        for (TransactionLog tx : timeoutTransactions) {
            try {
                processTimeoutTransaction(tx);
            } catch (Exception e) {
                log.error("处理超时事务失败: transactionId={}, error={}", tx.getTransactionId(), e.getMessage());
            }
        }

        log.info("超时事务扫描完成: 处理数量={}", timeoutTransactions.size());
    }

    /**
     * 处理单个超时事务
     */
    @Transactional(rollbackFor = Exception.class)
    public void processTimeoutTransaction(TransactionLog tx) {
        log.info("处理超时事务: transactionId={}, seckillId={}, userId={}", 
                tx.getTransactionId(), tx.getSeckillId(), tx.getUserId());

        // 检查订单是否存在
        SeckillOrder order = orderMapper.selectByOrderNo(tx.getOrderNo());

        if (order == null) {
            // 订单不存在，回补库存
            rollbackStock(tx);
            tx.setStatus(TransactionStatus.FAILED.getCode());
            tx.setErrorMsg("订单不存在，超时回补");
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
     * 
     * 主动检查未支付超时订单，不等延迟消息
     * 
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

        // 查询创建超过15分钟仍未支付的订单
        LocalDateTime unpaidTime = LocalDateTime.now().minusMinutes(15);
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

        if (result >= 0) {
            // 更新订单状态（使用包含分片键的更新方法）
            order.setStatus(OrderStatus.CANCELLED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("未支付订单取消完成: orderNo={}, shardIndex={}", order.getOrderNo(), result);
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

        if (result >= 0) {
            log.info("手动回补成功: shardIndex={}", result);
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

        log.info("事务回补库存: transactionId={}, shardIndex={}", tx.getTransactionId(), result);
    }
}