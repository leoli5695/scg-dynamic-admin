package com.seckill.service;

import com.seckill.dto.OrderMessage;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 库存预扣减异步确认服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 定时检查预扣减的库存是否已确认（订单创建成功）
 * 2. 未确认的预扣减自动回补（防止库存泄漏）
 * 3. Redis临时标记与MySQL事务日志对账
 * <p>
 * 设计原理:
 * - Redis Lua脚本扣减库存后，记录临时标记
 * - RocketMQ消费成功后，确认标记
 * - 如果超时未确认，自动回补库存
 * <p>
 * 防止的问题:
 * 1. MQ消息丢失导致库存不回补
 * 2. Consumer宕机导致库存泄漏
 * 3. 数据库写入失败但Redis已扣减
 * <p>
 * 数据结构:
 * - Redis临时标记: seckill:pending:{seckillId}:{orderNo}
 * Value: {userId, quantity, shardIndex, createTime}
 * TTL: 5分钟
 * <p>
 * - MySQL事务日志: transaction_log表
 * 用于对账和最终确认
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockConfirmService {

    private final SeckillDeductLua seckillDeductLua;
    private final StringRedisTemplate redisTemplate;
    private final TransactionLogMapper transactionLogMapper;

    /**
     * Redis临时标记Key前缀
     */
    private static final String PENDING_KEY_PREFIX = "seckill:pending:";

    /**
     * 临时标记过期时间（秒）
     */
    private static final long PENDING_TTL_SECONDS = 300;  // 5分钟

    /**
     * ============================================================================
     * 创建预扣减临时标记
     * ============================================================================
     * <p>
     * 在Lua脚本扣减库存后调用
     * 用于后续确认或回补
     */
    public void createPendingMark(OrderMessage orderMessage) {
        String pendingKey = buildPendingKey(orderMessage.getSeckillId(), orderMessage.getOrderNo());

        try {
            // 存储临时标记
            String value = String.format("%d:%d:%d:%d",
                    orderMessage.getUserId(),
                    orderMessage.getQuantity(),
                    orderMessage.getShardIndex(),
                    System.currentTimeMillis());

            redisTemplate.opsForValue().set(pendingKey, value, PENDING_TTL_SECONDS, TimeUnit.SECONDS);

            log.info("创建预扣减临时标记: key={}, value={}", pendingKey, value);

        } catch (Exception e) {
            log.error("创建预扣减临时标记失败: orderNo={}, error={}",
                    orderMessage.getOrderNo(), e.getMessage());
            // 标记创建失败不影响主流程，由对账服务兜底
        }
    }

    /**
     * ============================================================================
     * 确认预扣减（订单创建成功后调用）
     * ============================================================================
     * <p>
     * 删除Redis临时标记，表示库存扣减已确认
     */
    public void confirmPendingMark(Long seckillId, String orderNo) {
        String pendingKey = buildPendingKey(seckillId, orderNo);

        try {
            Boolean deleted = redisTemplate.delete(pendingKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("确认预扣减成功: key={}", pendingKey);
            } else {
                log.warn("确认预扣减失败（标记不存在）: key={}", pendingKey);
            }
        } catch (Exception e) {
            log.error("确认预扣减失败: orderNo={}, error={}", orderNo, e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 定时检查未确认的预扣减（每分钟）
     * ============================================================================
     * <p>
     * 流程:
     * 1. 查询处理中的事务日志
     * 2. 检查是否超时（超过5分钟）
     * 3. 检查Redis临时标记是否存在
     * 4. 未确认的自动回补库存
     * <p>
     * 分布式锁: 使用ShedLock防止多实例重复执行
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(
            name = "StockConfirmService_checkPendingStock",
            lockAtMostFor = "5m",
            lockAtLeastFor = "30s"
    )
    public void checkPendingStock() {
        log.info("开始检查未确认的预扣减...");

        // 查询5分钟前创建且仍处于处理中的事务
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(5);
        List<TransactionLog> pendingTransactions = transactionLogMapper.selectTimeoutTransactions(
                TransactionStatus.PROCESSING.getCode(),
                timeoutTime
        );

        int confirmedCount = 0;
        int rollbackCount = 0;

        for (TransactionLog tx : pendingTransactions) {
            try {
                boolean processed = processPendingTransaction(tx);
                if (processed) {
                    rollbackCount++;
                } else {
                    confirmedCount++;
                }
            } catch (Exception e) {
                log.error("处理预扣减事务失败: transactionId={}, error={}",
                        tx.getTransactionId(), e.getMessage());
            }
        }

        log.info("未确认预扣减检查完成: 总数={}, 已确认={}, 需回补={}",
                pendingTransactions.size(), confirmedCount, rollbackCount);
    }

    /**
     * ============================================================================
     * 处理单个预扣减事务
     * ============================================================================
     * <p>
     * 返回值:
     * - true: 需要回补库存
     * - false: 已确认或不需要处理
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processPendingTransaction(TransactionLog tx) {
        Long seckillId = tx.getSeckillId();
        String orderNo = tx.getOrderNo();

        // Step 1: 检查Redis临时标记
        String pendingKey = buildPendingKey(seckillId, orderNo);
        String pendingValue = redisTemplate.opsForValue().get(pendingKey);

        if (pendingValue == null) {
            // 标记不存在，可能已确认或已过期
            // 检查事务日志状态
            if (tx.getStatus() == TransactionStatus.PROCESSING.getCode()) {
                // 处理中但标记不存在，需要检查订单是否存在
                log.warn("预扣减标记不存在，事务仍处理中: transactionId={}, orderNo={}",
                        tx.getTransactionId(), orderNo);

                // 更新事务状态为失败（后续由补偿服务处理）
                tx.setStatus(TransactionStatus.FAILED.getCode());
                tx.setErrorMsg("预扣减超时未确认");
                tx.setUpdateTime(LocalDateTime.now());
                transactionLogMapper.updateById(tx);

                return true;  // 需要回补
            }
            return false;  // 不需要处理
        }

        // Step 2: 标记存在且超时，需要回补
        log.warn("预扣减超时未确认: seckillId={}, orderNo={}, pendingValue={}",
                seckillId, orderNo, pendingValue);

        // 解析临时标记
        String[] parts = pendingValue.split(":");
        if (parts.length >= 3) {
            Long userId = Long.parseLong(parts[0]);
            int quantity = Integer.parseInt(parts[1]);
            int shardIndex = Integer.parseInt(parts[2]);

            // Step 3: 回补库存
            rollbackStock(seckillId, userId, quantity, shardIndex);

            // Step 4: 删除临时标记
            redisTemplate.delete(pendingKey);

            // Step 5: 更新事务状态
            tx.setStatus(TransactionStatus.FAILED.getCode());
            tx.setErrorMsg("预扣减超时自动回补");
            tx.setUpdateTime(LocalDateTime.now());
            transactionLogMapper.updateById(tx);

            log.info("预扣减超时回补完成: transactionId={}, seckillId={}, userId={}",
                    tx.getTransactionId(), seckillId, userId);

            return true;
        }

        return false;
    }

    /**
     * ============================================================================
     * 回补库存
     * ============================================================================
     */
    private void rollbackStock(Long seckillId, Long userId, int quantity, int shardIndex) {
        try {
            // 使用Lua脚本精确回补到原分片
            long result = seckillDeductLua.rollbackStock(seckillId, userId, quantity);

            if (result >= 1000) {
                log.info("库存回补成功: seckillId={}, userId={}, quantity={}, shardIndex={}",
                        seckillId, userId, quantity, result - 1000);
            } else {
                log.error("库存回补失败: seckillId={}, userId={}, result={}",
                        seckillId, userId, result);
            }
        } catch (Exception e) {
            log.error("库存回补异常: seckillId={}, userId={}, error={}",
                    seckillId, userId, e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 对账检查（每5分钟）
     * ============================================================================
     * <p>
     * 对比Redis临时标记与MySQL事务日志
     * 发现不一致的数据进行修正
     */
    @Scheduled(fixedRate = 300000)
    @SchedulerLock(
            name = "StockConfirmService_reconciliation",
            lockAtMostFor = "10m",
            lockAtLeastFor = "1m"
    )
    public void reconciliation() {
        log.info("开始预扣减对账...");

        // 统计Redis临时标记数量（使用SCAN避免阻塞）
        // 这里简化实现，实际应该使用SCAN命令遍历所有pending key

        // 统计MySQL处理中的事务数量
        int processingCount = transactionLogMapper.countProcessingTransactions(null);

        log.info("预扣减对账完成: 处理中事务数={}", processingCount);
    }

    /**
     * ============================================================================
     * Key构建方法
     * ============================================================================
     */
    private String buildPendingKey(Long seckillId, String orderNo) {
        return PENDING_KEY_PREFIX + seckillId + ":" + orderNo;
    }
}