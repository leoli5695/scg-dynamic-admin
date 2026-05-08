package com.seckill.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ============================================================================
 * 内部 API 注解
 * ============================================================================
 *
 * 用于标记仅允许内部调用的接口（如预热、压测、管理接口）。
 * 拦截器会校验调用方 IP 是否在白名单中。
 *
 * 使用示例：
 * @InternalApi
 * @PostMapping("/warmup/manual")
 * public WarmupResponse manualWarmup(...) { ... }
 *
 * 配置方式（application.yml）：
 * seckill:
 *   internal-api:
 *     enabled: true
 *     whitelist: 127.0.0.1,10.0.0.0/8,192.168.0.0/16
 *
 * 安全策略：
 * - enabled=false 时不做校验（默认关闭，生产必须开启）
 * - 白名单支持 IP 和 CIDR 格式
 * - 非白名单 IP 访问返回 403 Forbidden
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalApi {

    /**
     * 接口描述（用于日志和告警）
     */
    String description() default "";

    /**
     * 是否允许本地开发环境绕过校验
     */
    boolean allowDevBypass() default true;
}