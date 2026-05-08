package com.seckill.service;

import com.seckill.dto.OrderMessage;
import com.seckill.entity.SeckillOrder;
import com.seckill.enums.OrderStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ============================================================================
 * 本地事务直接处理服务
 * ============================================================================
 *
 * 功能:
 * 1. MQ 故障时的降级订单处理
 * 2. 直接写入事务日志表和订单表
 * 3. 使用数据库事务保证一致性
 *
 * 降级流程:
 * 1. 写入事务日志表（status = PROCESSING）
 * 2. 写入订单表（status = PENDING_PAYMENT）
 * 3. 写入 ES 索引
 * 4. 更新事务日志表（status = SUCCESS）
 * 5. 提交事务
 *
 * 注意:
 * - 此服务仅在 MQ 故障时使用
 * - 性能较低，不适合高并发场景
 * - 需配合补偿服务处理超时订单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalTransactionService {

    private final TransactionLogMapper transactionLogMapper;
    private final OrderMapper orderMapper;
    private final ElasticsearchService elasticsearchService;

    /**
     * ============================================================================
     * 本地事务直接处理订单（MQ 降级模式）
     * ============================================================================
     *
     * 流程:
     * 1. 写入事务日志
     * 2. 写入订单表
     * 3. 同步 ES 索引
     * 4. 更新事务状态
     *
     * @param orderMessage 订单消息
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processOrderDirectly(OrderMessage orderMessage) {
        log.info("本地事务直接处理订单（MQ降级模式）: transactionId={}, orderNo={}",
                orderMessage.getTransactionId(), orderMessage.getOrderNo());

        try {
            // Step 1: 写入事务日志
            com.seckill.entity.TransactionLog transactionLog = new com.seckill.entity.TransactionLog();
            transactionLog.setTransactionId(orderMessage.getTransactionId());
            transactionLog.setSeckillId(orderMessage.getSeckillId());
            transactionLog.setUserId(orderMessage.getUserId());
            transactionLog.setProductId(orderMessage.getProductId());
            transactionLog.setQuantity(orderMessage.getQuantity());
            transactionLog.setTotalAmount(orderMessage.getTotalAmount());
            transactionLog.setOrderNo(orderMessage.getOrderNo());
            transactionLog.setShardIndex(orderMessage.getShardIndex());
            transactionLog.setStatus(0); // 处理中
            transactionLog.setRetryCount(0);
            transactionLog.setCreateTime(LocalDateTime.now());
            transactionLog.setUpdateTime(LocalDateTime.now());
            transactionLog.setExpireTime(LocalDateTime.now().plusMinutes(30));
            transactionLog.setTraceId(orderMessage.getTraceId());

            transactionLogMapper.insert(transactionLog);

            // Step 2: 写入订单表
            SeckillOrder order = new SeckillOrder();
            order.setOrderNo(orderMessage.getOrderNo());
            order.setUserId(orderMessage.getUserId());
            order.setSeckillId(orderMessage.getSeckillId());
            order.setProductId(orderMessage.getProductId());
            order.setQuantity(orderMessage.getQuantity());
            order.setTotalAmount(orderMessage.getTotalAmount());
            order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
            order.setShardIndex(orderMessage.getShardIndex());
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());

            orderMapper.insert(order);

            // Step 3: 同步 ES 索引
            elasticsearchService.indexOrder(orderMessage);

            // Step 4: 更新事务状态为成功
            transactionLog.setStatus(1); // 成功
            transactionLog.setUpdateTime(LocalDateTime.now());
            transactionLogMapper.updateById(transactionLog);

            log.info("本地事务处理成功: transactionId={}, orderNo={}",
                    orderMessage.getTransactionId(), orderMessage.getOrderNo());

            return true;

        } catch (Exception e) {
            log.error("本地事务处理失败: transactionId={}, error={}",
                    orderMessage.getTransactionId(), e.getMessage(), e);
            throw e;
        }
    }
}