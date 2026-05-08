package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * RocketMQ 故障降级服务
 * ============================================================================
 *
 * 功能:
 * 1. MQ 健康检查（定时检测）
 * 2. 降级开关控制（手动/自动）
 * 3. 故障告警（与告警服务联动）
 * 4. 自动恢复检测
 *
 * 降级策略:
 * - MQ 故障时，启用本地事务直接处理模式
 * - 直接写入事务日志表，然后创建订单
 * - 使用补偿服务处理超时订单
 * - 降低异步处理能力，但保证业务可用性
 *
 * 监控指标:
 * - seckill.mq.status: MQ 状态（0=正常，1=降级，2=故障）
 * - seckill.mq.health_check: 健康检查次数
 * - seckill.mq.degrade_count: 降级次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MQDegradeService {

    private final AlertService alertService;
    private final TransactionMQProducer transactionMQProducer;

    /**
     * MQ 状态
     * 0: 正常
     * 1: 降级（MQ响应慢，但可用）
     * 2: 故障（MQ不可用）
     */
    private volatile int mqStatus = 0;

    /**
     * 降级开关（原子操作）
     */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /**
     * 连续失败次数
     */
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    /**
     * 连续成功次数（用于自动恢复）
     */
    private final AtomicLong consecutiveSuccesses = new AtomicLong(0);

    /**
     * 降级阈值（连续失败次数达到此值触发降级）
     */
    private static final int DEGRADE_THRESHOLD = 3;

    /**
     * 自动恢复阈值（连续成功次数达到此值自动恢复）
     */
    private static final int RECOVERY_THRESHOLD = 5;

    /**
     * 是否启用 MQ（配置项）
     */
    @Value("${rocketmq.enabled:true}")
    private boolean mqEnabled;

    /**
     * ============================================================================
     * 检查 MQ 是否可用
     * ============================================================================
     *
     * @return true: 可用, false: 不可用
     */
    public boolean isMQAvailable() {
        if (!mqEnabled) {
            // 配置禁用 MQ
            return false;
        }

        if (degraded.get()) {
            return false;
        }

        if (transactionMQProducer == null) {
            log.warn("MQ生产者未初始化");
            return false;
        }

        try {
            // 检查 Producer 状态
            // RocketMQ 没有直接的 ping 方法，使用运行状态判断
            return transactionMQProducer.getDefaultMQProducerImpl().getServiceState().name().equals("RUNNING");
        } catch (Exception e) {
            log.warn("MQ健康检查失败: {}", e.getMessage());
            recordFailure();
            return false;
        }
    }

    /**
     * ============================================================================
     * 获取当前 MQ 状态
     * ============================================================================
     */
    public int getMQStatus() {
        return mqStatus;
    }

    /**
     * ============================================================================
     * 是否处于降级模式
     * ============================================================================
     */
    public boolean isDegraded() {
        return degraded.get() || !mqEnabled;
    }

    /**
     * ============================================================================
     * 手动启用降级模式
     * ============================================================================
     */
    public void manualDegrade() {
        degraded.set(true);
        mqStatus = 2;
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);

        log.warn("MQ手动降级启用");
        alertService.sendAlert("MQ手动降级启用", "运维手动触发RocketMQ降级模式");
    }

    /**
     * ============================================================================
     * 手动恢复（取消降级）
     * ============================================================================
     */
    public void manualRecover() {
        degraded.set(false);
        mqStatus = 0;
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);

        log.info("MQ手动恢复，取消降级模式");
    }

    /**
     * ============================================================================
     * 记录成功（用于自动恢复）
     * ============================================================================
     */
    public void recordSuccess() {
        long successes = consecutiveSuccesses.incrementAndGet();
        consecutiveFailures.set(0);

        // 检查是否可以自动恢复
        if (degraded.get() && successes >= RECOVERY_THRESHOLD) {
            attemptRecover();
        }
    }

    /**
     * ============================================================================
     * 记录失败（触发自动降级）
     * ============================================================================
     */
    public void recordFailure() {
        long failures = consecutiveFailures.incrementAndGet();
        consecutiveSuccesses.set(0);

        // 检查是否需要自动降级
        if (!degraded.get() && failures >= DEGRADE_THRESHOLD) {
            autoDegrade();
        }
    }

    /**
     * ============================================================================
     * 自动降级
     * ============================================================================
     */
    private void autoDegrade() {
        degraded.set(true);
        mqStatus = 2;

        log.warn("MQ自动降级触发: 连续失败次数={}", DEGRADE_THRESHOLD);

        alertService.sendAlert("MQ自动降级",
                "RocketMQ连续健康检查失败" + DEGRADE_THRESHOLD + "次，已自动启用降级模式");
    }

    /**
     * ============================================================================
     * 尝试自动恢复
     * ============================================================================
     */
    private void attemptRecover() {
        // 先检查是否真的恢复了
        if (isMQAvailable()) {
            degraded.set(false);
            mqStatus = 0;

            log.info("MQ自动恢复成功: 连续成功次数={}", RECOVERY_THRESHOLD);

            alertService.sendAlert("MQ自动恢复",
                    "RocketMQ连续健康检查成功" + RECOVERY_THRESHOLD + "次，已自动恢复正常模式");
        } else {
            consecutiveSuccesses.set(0);
            log.warn("MQ自动恢复失败，继续保持降级模式");
        }
    }

    /**
     * ============================================================================
     * 定时健康检查（每60秒）
     * ============================================================================
     */
    @Scheduled(fixedRate = 60000)
    public void healthCheck() {
        if (!mqEnabled) {
            return;
        }

        boolean available = isMQAvailable();

        if (available) {
            if (degraded.get()) {
                recordSuccess();
            }
            log.debug("MQ健康检查成功");
        } else {
            log.warn("MQ健康检查失败");
        }
    }

    /**
     * ============================================================================
     * MQ 操作包装器（带降级处理）
     * ============================================================================
     *
     * @param operation MQ 操作
     * @param fallback 降级操作
     * @param <T> 返回类型
     * @return 操作结果
     */
    public <T> T executeWithFallback(MQOperation<T> operation, FallbackOperation<T> fallback) {
        if (degraded.get() || !mqEnabled) {
            log.debug("MQ降级模式，执行fallback操作");
            return fallback.execute();
        }

        try {
            T result = operation.execute();
            recordSuccess();
            return result;
        } catch (Exception e) {
            log.warn("MQ操作失败: {}", e.getMessage());
            recordFailure();

            // 降级执行
            return fallback.execute();
        }
    }

    /**
     * ============================================================================
     * MQ 操作接口
     * ============================================================================
     */
    public interface MQOperation<T> {
        T execute();
    }

    /**
     * ============================================================================
     * 降级操作接口
     * ============================================================================
     */
    public interface FallbackOperation<T> {
        T execute();
    }
}