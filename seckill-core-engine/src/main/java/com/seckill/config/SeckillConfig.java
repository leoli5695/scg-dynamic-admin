package com.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================================
 * 秒杀引擎核心配置
 * ============================================================================
 */
@Configuration
@ConfigurationProperties(prefix = "seckill")
@Data
public class SeckillConfig {

    private int shardCount = 8;

    // Keep v1 until all historical rows have been migrated during a maintenance window.
    private int orderRoutingVersion = 1;
    // Enable only after migrating ALL stock/bought/user_shard keys while traffic is stopped.
    private boolean stockHashTagEnabled = false;

    /**
     * Maximum purchase count per user per activity.
     * This is the actual purchase limit, NOT the shard count.
     */
    private int maxBuyCount = 1;  // Default: 1 item per user per activity

    private WarmupConfig warmup = new WarmupConfig();
    private DelayConfig delay = new DelayConfig();
    private ConsumerConfig consumer = new ConsumerConfig();
    private SnowflakeConfig snowflake = new SnowflakeConfig();
    private ReconciliationConfig reconciliation = new ReconciliationConfig();
    private InternalApiConfig internalApi = new InternalApiConfig();
    private BatchConfig batch = new BatchConfig();  // OPTIMIZATION (P2): 攒批配置
    private IdentityConfig identity = new IdentityConfig();

    @Data
    public static class IdentityConfig {
        private String issuer = "";
        private String audience = "";
        private String jwkSetUri = "";
        private String publicKeyPem = "";
        private String userIdClaim = "sub";
    }

    @Data
    public static class WarmupConfig {
        private boolean enabled = false;
        private int preWarmupMinutes = 5;
        private int stockExpireSeconds = 3600;
    }

    @Data
    public static class DelayConfig {
        private int delayLevel = 17;  // RocketMQ延迟级别17 = 20分钟
        private int unpaidTimeoutSeconds = 1200;  // 20分钟超时
    }

    @Data
    public static class ConsumerConfig {
        private int orderCreateRate = 1000;
        private int batchSize = 50;
    }

    @Data
    public static class SnowflakeConfig {
        private long datacenterId = 1;
        private long workerId = 1;
    }

    @Data
    public static class ReconciliationConfig {
        private boolean enabled = true;
        private int checkIntervalMinutes = 5;
        private int diffAlertThreshold = 10;
    }

    /**
     * 内部 API 安全配置
     * 用于保护预热接口、管理接口等内部调用
     */
    @Data
    public static class InternalApiConfig {
        /**
         * 是否启用 IP 白名单校验
         * 生产环境必须开启，开发环境可关闭
         */
        private boolean enabled = false;

        /**
         * IP 白名单（逗号分隔）
         * 支持格式：
         * - 单个 IP：127.0.0.1
         * - CIDR 网段：10.0.0.0/8（内网）
         * - 多个：127.0.0.1,10.0.0.0/8,192.168.0.0/16
         */
        private String whitelist = "127.0.0.1,10.0.0.0/8,192.168.0.0/16";

        /**
         * 是否信任 X-Forwarded-For / X-Real-IP 头来解析调用方 IP
         * <p>
         * 默认 false：这些头由客户端自行发送，无条件信任等于白名单形同虚设
         * （任何人带上 {@code X-Forwarded-For: 127.0.0.1} 即可通过校验）。
         * 只有当本服务确实部署在会覆写这些头的可信反向代理之后时才可开启。
         */
        private boolean trustProxyHeaders = false;
    }

    /**
     * ============================================================================
     * OPTIMIZATION (P2): 攒批队列动态配置
     * ============================================================================
     * <p>
     * 允许根据流量动态调整攒批参数，优化性能和内存使用
     * <p>
     * 配置说明：
     * - queueCapacity: 队列容量上限，防止内存膨胀
     * - threshold: 批量写入阈值，达到后触发批量写入
     * - maxWaitMs: 最大攒批时间，超过后强制刷新
     * - dynamicAdjustEnabled: 动态调整开关
     */
    @Data
    public static class BatchConfig {
        private long acknowledgementTimeoutMs = 5000;
        /**
         * 队列容量上限（防止内存膨胀）
         * 默认200，生产环境可根据流量调整
         */
        private int queueCapacity = 200;

        /**
         * 批量写入阈值（达到此数量触发批量写入）
         * 默认50，平衡性能和风险
         */
        private int threshold = 50;

        /**
         * 最大攒批时间（超过此时间强制刷新）
         * 默认100ms，防止积压太久
         */
        private long maxWaitMs = 100;

        /**
         * 动态调整开关（根据系统负载自动调整阈值）
         * 默认false，生产环境可开启
         */
        private boolean dynamicAdjustEnabled = false;
    }
}
