package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.entity.SeckillActivity;
import com.seckill.entity.SeckillProduct;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.SeckillResult;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.ProductMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import com.seckill.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 秒杀核心服务
 * ============================================================================
 * 
 * 核心流程（简化为2层防重）:
 * 1. Lua脚本原子操作（Redis层快速失败）
 *    - SISMEMBER 防重检查
 *    - 分片库存扣减
 *    - HSET 记录分片索引
 *    - SADD 记录购买
 * 
 * 2. RocketMQ事务消息
 *    - 发送半消息
 *    - 执行本地事务（写事务日志）
 *    - 提交/回滚消息
 * 
 * 3. 数据库唯一索引（Layer 2防重）
 *    - 最终一致性保障
 * 
 * 去掉的设计:
 * - Redisson分布式锁：Lua脚本已保证原子性
 * - Caffeine本地缓存库存：会导致超卖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final SeckillDeductLua seckillDeductLua;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final TransactionLogMapper transactionLogMapper;
    private final LocalCacheService localCacheService;
    private final RedisDegradeService redisDegradeService;
    private final LocalFallbackService localFallbackService;
    private final MQDegradeService mqDegradeService;
    private final SeckillConfig seckillConfig;
    private final ObjectMapper objectMapper;
    private final Counter seckillRequestCounter;
    private final Counter seckillSuccessCounter;
    private final Counter seckillStockInsufficientCounter;
    private final Counter seckillAlreadyBoughtCounter;
    private final Counter seckillNotWarmedCounter;

    // RocketMQ事务消息生产者（通过 setter 注入，由 RocketMQProducerConfig 管理）
    private TransactionMQProducer transactionMQProducer;

    /**
     * 设置事务消息生产者（由 RocketMQProducerConfig 在 @PostConstruct 中调用）
     * 
     * 注意: 不在 SeckillService 的 @PostConstruct 中启动 producer，
     * 因为此时 producer 可能还未注入。Producer 的启动由 RocketMQProducerConfig 管理。
     */
    public void setTransactionMQProducer(TransactionMQProducer producer) {
        this.transactionMQProducer = producer;
        if (producer != null) {
            try {
                producer.start();
                log.info("RocketMQ事务消息生产者启动成功");
            } catch (Exception e) {
                log.error("RocketMQ事务消息生产者启动失败", e);
            }
        }
    }

    /**
     * 旧的 init 方法已移除，producer 启动逻辑移到 setTransactionMQProducer 中
     */

    /**
     * ============================================================================
     * 秒杀入口方法
     * ============================================================================
     */
    public SeckillResponse doSeckill(SeckillRequest request) {
        seckillRequestCounter.increment();

        Long userId = request.getUserId();
        Long seckillId = request.getSeckillId();
        Long productId = request.getProductId();
        int quantity = request.getQuantity();

        log.info("秒杀请求: userId={}, seckillId={}, productId={}, quantity={}", 
                userId, seckillId, productId, quantity);

        try {
            // ========================================================================
            // Step 1: 活动校验（优先从本地缓存获取）
            // ========================================================================
            SeckillActivity activity;
            try {
                activity = localCacheService.getActivity(seckillId);
            } catch (Exception e) {
                log.warn("本地缓存获取活动失败，降级查询DB: seckillId={}", seckillId);
                activity = activityMapper.selectById(seckillId);  // fallback
            }
            if (activity == null) {
                return SeckillResponse.fail(SeckillResult.ACTIVITY_NOT_FOUND);
            }

            if (activity.isNotStarted()) {
                return SeckillResponse.fail(SeckillResult.ACTIVITY_NOT_STARTED);
            }

            if (activity.isEnded()) {
                return SeckillResponse.fail(SeckillResult.ACTIVITY_ENDED);
            }

            // ========================================================================
            // Step 2: 库存扣减（支持 Redis 降级）
            // ========================================================================
            long luaResult;
            boolean isDegradeMode = false;

            if (redisDegradeService.isDegraded()) {
                // Redis 降级模式：使用本地库存计数器
                log.warn("Redis降级模式，使用本地库存扣减: seckillId={}, userId={}", seckillId, userId);
                int localResult = localFallbackService.deductStockLocal(seckillId, userId, quantity);
                isDegradeMode = true;
                
                if (localResult == -2) {
                    seckillAlreadyBoughtCounter.increment();
                    log.warn("本地防重检查：已购买过: userId={}, seckillId={}", userId, seckillId);
                    return SeckillResponse.fail(SeckillResult.ALREADY_BOUGHT);
                } else if (localResult == -1) {
                    seckillStockInsufficientCounter.increment();
                    log.warn("本地库存不足: userId={}, seckillId={}", userId, seckillId);
                    return SeckillResponse.fail(SeckillResult.STOCK_INSUFFICIENT);
                }
                
                luaResult = 0;  // 本地扣减成功
                
            } else {
                // 正常模式：Redis Lua 脚本原子扣减
                luaResult = seckillDeductLua.deductStock(seckillId, userId, quantity);
            }

            SeckillResult result = SeckillResult.fromLuaResult(luaResult);

            if (result != SeckillResult.SUCCESS) {
                // 记录失败原因
                if (result == SeckillResult.ALREADY_BOUGHT) {
                    seckillAlreadyBoughtCounter.increment();
                } else if (result == SeckillResult.STOCK_INSUFFICIENT) {
                    seckillStockInsufficientCounter.increment();
                } else if (result == SeckillResult.STOCK_NOT_WARMED) {
                    seckillNotWarmedCounter.increment();
                    log.error("库存未预热: seckillId={}, 请调用WarmupService进行预热", seckillId);
                }

                log.warn("秒杀失败: userId={}, seckillId={}, result={}", userId, seckillId, result);
                return SeckillResponse.fail(result);
            }

            // ========================================================================
            // Step 3: 预生成订单号和事务ID
            // ========================================================================
            String orderNo = String.valueOf(snowflakeIdGenerator.nextId());
            String transactionId = String.valueOf(snowflakeIdGenerator.nextId());
            int shardIndex = (int) luaResult; // Lua返回的就是分片索引

            // ========================================================================
            // Step 4: 获取商品价格，计算总金额（优先从本地缓存获取）
            // ========================================================================
            SeckillProduct product;
            try {
                product = localCacheService.getProduct(productId);
            } catch (Exception e) {
                log.warn("本地缓存获取商品失败，降级查询DB: productId={}", productId);
                product = productMapper.selectById(productId);  // fallback
            }
            if (product == null) {
                log.error("商品不存在: productId={}", productId);
                return SeckillResponse.systemError("商品不存在");
            }
            BigDecimal totalAmount = product.getSeckillPrice().multiply(BigDecimal.valueOf(quantity));

            // ========================================================================
            // Step 5: 发送订单消息（支持 MQ 降级）
            // ========================================================================
            OrderMessage orderMessage = new OrderMessage();
            orderMessage.setTransactionId(transactionId);
            orderMessage.setOrderNo(orderNo);
            orderMessage.setUserId(userId);
            orderMessage.setSeckillId(seckillId);
            orderMessage.setProductId(productId);
            orderMessage.setQuantity(quantity);
            orderMessage.setTotalAmount(totalAmount);
            orderMessage.setShardIndex(shardIndex);
            orderMessage.setCreateTime(System.currentTimeMillis());
            orderMessage.setTraceId(request.getTraceId()); // 链路追踪ID

            // MQ 降级检查
            if (mqDegradeService.isDegraded()) {
                // MQ 降级模式：先写入本地缓冲队列
                log.warn("MQ降级模式，订单写入缓冲队列: orderNo={}", orderNo);
                localFallbackService.bufferOrder(orderNo, orderMessage);
                
                // 直接执行本地事务（写事务日志）
                executeLocalTransaction(orderMessage);
                
            } else {
                // 正常模式：发送 RocketMQ 事务消息
                sendTransactionMessage(orderMessage);
            }

            // ========================================================================
            // Step 6: 返回成功响应
            // ========================================================================
            seckillSuccessCounter.increment();

            log.info("秒杀成功: traceId={}, userId={}, seckillId={}, orderNo={}, shardIndex={}", 
                    request.getTraceId(), userId, seckillId, orderNo, shardIndex);

            return SeckillResponse.success(orderNo);

        } catch (Exception e) {
            log.error("秒杀异常: userId={}, seckillId={}, error={}", userId, seckillId, e.getMessage(), e);

            // 异常情况下，需要回补库存
            try {
                seckillDeductLua.rollbackStock(seckillId, userId, quantity);
            } catch (Exception rollbackEx) {
                log.error("库存回补失败: userId={}, seckillId={}", userId, seckillId, rollbackEx);
            }

            return SeckillResponse.systemError("系统繁忙，请稍后再试");
        }
    }

    /**
     * ============================================================================
     * 发送RocketMQ事务消息
     * ============================================================================
     * 
     * 开发模式下（RocketMQ禁用）会跳过发送，仅记录日志
     */
    private void sendTransactionMessage(OrderMessage orderMessage) {
        // 开发模式下RocketMQ可能禁用
        if (transactionMQProducer == null) {
            log.warn("RocketMQ未启用，跳过发送事务消息: transactionId={}", orderMessage.getTransactionId());
            // 开发模式：直接执行本地事务并记录
            executeLocalTransaction(orderMessage);
            return;
        }

        try {
            Message message = new Message(
                    RocketMQConfig.SECKILL_ORDER_TOPIC,
                    null,
                    orderMessage.getTransactionId(),
                    serializeOrderMessage(orderMessage)
            );

            // 发送事务半消息
            transactionMQProducer.sendMessageInTransaction(message, null);

            log.info("事务消息发送成功: transactionId={}", orderMessage.getTransactionId());

        } catch (Exception e) {
            log.error("事务消息发送失败: transactionId={}, error={}", 
                    orderMessage.getTransactionId(), e.getMessage(), e);
            throw new RuntimeException("消息发送失败", e);
        }
    }

    /**
     * ============================================================================
     * 执行本地事务（写事务日志表）
     * ============================================================================
     * 
     * 这是RocketMQ事务消息的本地事务执行逻辑
     * 由 TransactionListener 调用
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean executeLocalTransaction(OrderMessage orderMessage) {
        try {
            TransactionLog transactionLog = new TransactionLog();
            transactionLog.setTransactionId(orderMessage.getTransactionId());
            transactionLog.setSeckillId(orderMessage.getSeckillId());
            transactionLog.setUserId(orderMessage.getUserId());
            transactionLog.setProductId(orderMessage.getProductId());
            transactionLog.setQuantity(orderMessage.getQuantity());
            transactionLog.setTotalAmount(orderMessage.getTotalAmount());
            transactionLog.setOrderNo(orderMessage.getOrderNo());
            transactionLog.setShardIndex(orderMessage.getShardIndex());
            transactionLog.setStatus(TransactionStatus.PROCESSING.getCode());
            transactionLog.setRetryCount(0);
            transactionLog.setCreateTime(LocalDateTime.now());
            transactionLog.setUpdateTime(LocalDateTime.now());
            transactionLog.setExpireTime(LocalDateTime.now().plusMinutes(30));
            transactionLog.setTraceId(orderMessage.getTraceId()); // 链路追踪ID

            transactionLogMapper.insert(transactionLog);

            log.info("本地事务执行成功: traceId={}, transactionId={}", 
                    orderMessage.getTraceId(), orderMessage.getTransactionId());
            return true;

        } catch (Exception e) {
            log.error("本地事务执行失败: traceId={}, transactionId={}, error={}", 
                    orderMessage.getTraceId(), orderMessage.getTransactionId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * ============================================================================
     * 检查本地事务状态（事务回查）
     * ============================================================================
     */
    public TransactionStatus checkLocalTransaction(String transactionId) {
        TransactionLog transactionLog = transactionLogMapper.selectByTransactionId(transactionId);

        if (transactionLog == null) {
            // 未找到事务记录，回滚
            log.warn("事务回查: 未找到事务记录, transactionId={}", transactionId);
            return TransactionStatus.FAILED;
        }

        TransactionStatus status = TransactionStatus.fromCode(transactionLog.getStatus());
        log.info("事务回查: transactionId={}, status={}", transactionId, status);
        return status;
    }

    /**
     * ============================================================================
     * 更新事务状态为成功
     * ============================================================================
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTransactionSuccess(String transactionId) {
        TransactionLog transactionLog = transactionLogMapper.selectByTransactionId(transactionId);
        if (transactionLog != null) {
            transactionLog.setStatus(TransactionStatus.SUCCESS.getCode());
            transactionLog.setUpdateTime(LocalDateTime.now());
            transactionLogMapper.updateById(transactionLog);
        }
    }

    /**
     * ============================================================================
     * 更新事务状态为失败（并回补库存）
     * ============================================================================
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTransactionFailed(String transactionId) {
        TransactionLog transactionLog = transactionLogMapper.selectByTransactionId(transactionId);
        if (transactionLog != null) {
            transactionLog.setStatus(TransactionStatus.FAILED.getCode());
            transactionLog.setUpdateTime(LocalDateTime.now());
            transactionLogMapper.updateById(transactionLog);

            // 回补库存
            seckillDeductLua.rollbackStock(
                    transactionLog.getSeckillId(),
                    transactionLog.getUserId(),
                    transactionLog.getQuantity()
            );
        }
    }

    /**
     * ============================================================================
     * 序列化OrderMessage
     * ============================================================================
     */
    private byte[] serializeOrderMessage(OrderMessage orderMessage) {
        try {
            return objectMapper.writeValueAsBytes(orderMessage);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
}