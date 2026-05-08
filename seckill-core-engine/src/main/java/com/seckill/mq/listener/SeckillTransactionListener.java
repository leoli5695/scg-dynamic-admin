package com.seckill.mq.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.dto.OrderMessage;
import com.seckill.enums.TransactionStatus;
import com.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * RocketMQ 事务消息监听器
 * ============================================================================
 * 
 * 功能:
 * 1. executeLocalTransaction: 发送半消息后，执行本地事务
 * 2. checkLocalTransaction: Broker回查时，检查本地事务状态
 * 
 * 流程:
 * - 发送半消息 → executeLocalTransaction → 写事务日志 → 提交/回滚
 * - 超时未确认 → checkLocalTransaction → 查事务日志 → 返回状态
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SeckillTransactionListener implements TransactionListener {

    private final SeckillService seckillService;
    private final ObjectMapper objectMapper;

    public SeckillTransactionListener(SeckillService seckillService, ObjectMapper objectMapper) {
        this.seckillService = seckillService;
        this.objectMapper = objectMapper;
    }

    /**
     * ============================================================================
     * 执行本地事务
     * ============================================================================
     * 
     * 在发送半消息后，Broker返回半消息ID时执行
     * 
     * @param msg 消息内容（包含OrderMessage）
     * @param arg 参数（可为空）
     * @return 本地事务状态:
     *         COMMIT_MESSAGE: 提交消息，消费者可消费
     *         ROLLBACK_MESSAGE: 回滚消息，消息丢弃 + 回补库存
     *         UNKNOW: 等待回查
     */
    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            // 解析消息内容
            OrderMessage orderMessage = deserializeOrderMessage(msg.getBody());
            log.info("执行本地事务: transactionId={}, orderNo={}", 
                    orderMessage.getTransactionId(), orderMessage.getOrderNo());

            // 执行本地事务：写事务日志表（状态为 PROCESSING）
            boolean success = seckillService.executeLocalTransaction(orderMessage);

            if (success) {
                // 本地事务执行成功，但状态仍为 PROCESSING
                // 由 Consumer 消费订单消息成功后，再更新为 SUCCESS
                // 这里返回 COMMIT_MESSAGE 让消息可以被消费者消费
                log.info("本地事务执行成功，提交消息: transactionId={}", orderMessage.getTransactionId());
                return LocalTransactionState.COMMIT_MESSAGE;
            } else {
                // 本地事务失败，回滚消息（消费者不会收到此消息）
                // SeckillService.updateTransactionFailed 会回补库存
                seckillService.updateTransactionFailed(orderMessage.getTransactionId());
                log.warn("本地事务失败，回滚消息并回补库存: transactionId={}", orderMessage.getTransactionId());
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }

        } catch (Exception e) {
            log.error("执行本地事务异常: error={}", e.getMessage(), e);
            // 返回UNKNOW，等待Broker回查
            return LocalTransactionState.UNKNOW;
        }
    }

    /**
     * ============================================================================
     * 检查本地事务状态（事务回查）
     * ============================================================================
     * 
     * 当Broker超时未收到事务确认时，会调用此方法
     * 默认60秒后回查
     * 
     * @param msg 消息内容
     * @return 本地事务状态
     */
    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        try {
            // 从消息中获取事务ID
            String transactionId = msg.getKeys();
            log.info("事务回查: transactionId={}", transactionId);

            // 查询事务日志表
            TransactionStatus status = seckillService.checkLocalTransaction(transactionId);

            switch (status) {
                case SUCCESS:
                    log.info("事务回查结果: 成功，提交消息");
                    return LocalTransactionState.COMMIT_MESSAGE;

                case FAILED:
                    log.info("事务回查结果: 失败，回滚消息");
                    return LocalTransactionState.ROLLBACK_MESSAGE;

                case PROCESSING:
                    // 处理中，继续等待
                    log.info("事务回查结果: 处理中，等待下次回查");
                    return LocalTransactionState.UNKNOW;

                default:
                    log.warn("事务回查结果: 未知状态，回滚消息");
                    return LocalTransactionState.ROLLBACK_MESSAGE;
            }

        } catch (Exception e) {
            log.error("事务回查异常: error={}", e.getMessage(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    /**
     * ============================================================================
     * 反序列化OrderMessage
     * ============================================================================
     */
    private OrderMessage deserializeOrderMessage(byte[] body) {
        try {
            return objectMapper.readValue(body, OrderMessage.class);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }
}