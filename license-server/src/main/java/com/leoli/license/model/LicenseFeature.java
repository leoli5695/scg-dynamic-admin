package com.leoli.license.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * License Feature Enumeration
 * <p>
 * Defines all features available in different license editions.
 * Features are organized by edition level:
 * - BASIC: Free tier, limited features
 * - ADVANCED: Paid tier (¥9,999/year)
 * - ENTERPRISE: Enterprise tier (¥49,999 permanent)
 */
@Getter
@AllArgsConstructor
public enum LicenseFeature {

    // ============================================================================
    // 基础版（免费）
    // ============================================================================
    BASIC_ROUTING("单服务路由", "BASIC", "基础路由转发，支持单后端服务"),
    LOCAL_RATE_LIMIT("本地限流(100 QPS)", "BASIC", "单机限流，最大100 QPS"),
    BASIC_AUTH("基础认证", "BASIC", "API Key 和 Basic Auth 认证"),
    DOCKER_DEPLOY("Docker部署", "BASIC", "支持 Docker 容器部署"),

    // ============================================================================
    // 高级版（¥9,999/年）
    // ============================================================================
    WEIGHTED_ROUTING("多服务加权路由", "ADVANCED", "支持多后端服务加权流量分配"),
    DISTRIBUTED_RATE_LIMIT("Redis分布式限流", "ADVANCED", "Redis 分布式限流，支持高并发"),
    CIRCUIT_BREAKER("熔断器", "ADVANCED", "Resilience4j 熔断保护，防止级联故障"),
    PROMETHEUS_MONITORING("Prometheus监控", "ADVANCED", "Prometheus 指标暴露和监控集成"),
    K8S_DEPLOY("K8s部署", "ADVANCED", "Kubernetes 一键部署和管理"),
    JWT_AUTH("JWT认证", "ADVANCED", "JWT Token 认证支持"),
    HMAC_AUTH("HMAC签名认证", "ADVANCED", "HMAC 签名认证，API安全调用"),
    GRAY_RELEASE_BASIC("基础灰度发布", "ADVANCED", "Weight 权重灰度发布"),
    INSTANCE_QUOTA_3("3实例配额", "ADVANCED", "最多支持3个Gateway实例"),

    // ============================================================================
    // 企业版（¥49,999永久）
    // ============================================================================
    FULL_GRAY_RELEASE("全维度灰度", "ENTERPRISE", "Header/Cookie/Query/Weight 全维度灰度"),
    AI_COPilot("AI Copilot", "ENTERPRISE", "AI 智能运维助手，35+工具"),
    FULL_CHAIN_TRACE("全链路追踪", "ENTERPRISE", "完整链路追踪和性能分析"),
    MULTI_TENANT("多租户隔离", "ENTERPRISE", "命名空间级别的多租户隔离"),
    OAUTH2_AUTH("OAuth2认证", "ENTERPRISE", "OAuth2 第三方认证集成"),
    SSL_MANAGEMENT("SSL证书管理", "ENTERPRISE", "动态SSL证书加载和管理"),
    AUDIT_LOG("审计日志", "ENTERPRISE", "完整操作审计日志"),
    STRESS_TEST("压测功能", "ENTERPRISE", "内置压力测试工具"),
    REQUEST_REPLAY("请求回放", "ENTERPRISE", "请求回放和调试功能"),
    TOPOLOGY_VIEW("流量拓扑图", "ENTERPRISE", "服务流量拓扑可视化"),
    KUBERNETES_MANAGER("K8s集群管理", "ENTERPRISE", "多集群管理和部署"),
    INSTANCE_UNLIMITED("无限制实例", "ENTERPRISE", "无实例数量限制"),
    ENTERPRISE_SUPPORT("企业级支持", "ENTERPRISE", "专属技术支持和SLA保障");

    /**
     * Feature display name (Chinese)
     */
    private final String displayName;

    /**
     * Minimum required edition
     */
    private final String requiredEdition;

    /**
     * Feature description
     */
    private final String description;

    /**
     * Check if this feature is available in the given edition
     */
    public boolean isAvailableIn(String edition) {
        return switch (edition) {
            case "ENTERPRISE" -> true; // Enterprise has all features
            case "ADVANCED" -> this.requiredEdition.equals("BASIC") || this.requiredEdition.equals("ADVANCED");
            case "BASIC" -> this.requiredEdition.equals("BASIC");
            default -> false;
        };
    }

    /**
     * Get upgrade hint message
     */
    public String getUpgradeHint(String currentEdition) {
        if (isAvailableIn(currentEdition)) {
            return null;
        }
        return "功能 [" + displayName + "] 需要 " + requiredEdition + " 版 License。当前版本: " + currentEdition;
    }
}