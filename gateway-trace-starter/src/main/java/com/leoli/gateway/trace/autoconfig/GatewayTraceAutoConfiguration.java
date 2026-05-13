package com.leoli.gateway.trace.autoconfig;

import com.leoli.gateway.trace.aspect.DBTraceAspect;
import com.leoli.gateway.trace.aspect.KafkaMQTraceAspect;
import com.leoli.gateway.trace.aspect.MQTraceAspect;
import com.leoli.gateway.trace.aspect.RedisTraceAspect;
import com.leoli.gateway.trace.aspect.ServiceTraceAspect;
import com.leoli.gateway.trace.config.TraceThreadPoolConfig;
import com.leoli.gateway.trace.feign.FeignTraceInterceptor;
import com.leoli.gateway.trace.interceptor.TraceReportInterceptor;
import com.leoli.gateway.trace.interceptor.TraceWebInterceptor;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.leoli.gateway.trace.reporter.AsyncTraceReporter;
import com.leoli.gateway.trace.reporter.MiddlewareMetadataReporter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gateway Trace Starter auto-configuration
 * <p>
 * Automatically configures the following features:
 * 1. Trace Web interceptor: extracts TraceId, initializes context
 * 2. Trace report interceptor: reports data on request completion
 * 3. Async Trace reporter: batch async reporting
 * 4. Middleware metadata reporting: reports middleware dependencies at startup
 * 5. Service method tracing: automatically traces all Service methods
 * 6. Redis operation tracing: automatically traces Redis operations
 * 7. MQ operation tracing: automatically traces RocketMQ/Kafka operations
 * 8. DB operation tracing: automatically traces MyBatis/JDBC operations
 * <p>
 * Users only need to configure gateway.trace.admin-url to enable all features.
 *
 * @author leoli
 */
@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "gateway.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GatewayTraceProperties.class)
@Import({TraceThreadPoolConfig.class, WebClientTraceConfiguration.class, FeignTraceConfiguration.class})
public class GatewayTraceAutoConfiguration implements WebMvcConfigurer {

    private final Environment environment;
    private final GatewayTraceProperties properties;
    private final ObjectProvider<TraceWebInterceptor> webInterceptorProvider;
    private final ObjectProvider<TraceReportInterceptor> reportInterceptorProvider;

    // ==================== Web Interceptors ====================

    /**
     * Trace Web interceptor: extracts TraceId
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceWebInterceptor traceWebInterceptor() {
        String serviceName = properties.getServiceName(
                environment.getProperty("spring.application.name")
        );
        return new TraceWebInterceptor(properties, serviceName);
    }

    /**
     * Register interceptors.
     *
     * <p>注意：必须使用 Spring 容器管理的 bean（通过参数注入），
     * 不能调用 traceWebInterceptor() 方法，
     * 那样会创建新实例绕过 Spring 代理，失去 @ConditionalOnMissingBean 等语义。
     * <p>
     * TraceReportInterceptor 由 WebClientTraceConfiguration 内部配置类创建，
     * 使用 ObjectProvider 延迟获取，避免在没有 reactor-netty 时启动失败。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        TraceWebInterceptor webInterceptor = webInterceptorProvider.getIfAvailable();
        if (webInterceptor != null) {
            registry.addInterceptor(webInterceptor)
                    .addPathPatterns("/**")
                    .order(-100);  // Highest priority (preHandle first, afterCompletion last)
        }
        // TraceReportInterceptor 由 WebClientTraceConfiguration 提供（可选）
        TraceReportInterceptor reportInterceptor = reportInterceptorProvider.getIfAvailable();
        if (reportInterceptor != null) {
            registry.addInterceptor(reportInterceptor)
                    .addPathPatterns("/**")
                    .order(100);  // Lowest priority (preHandle last, afterCompletion first)
        }
    }

    // ==================== AOP Aspects ====================

    /**
     * Service method tracing aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceTraceAspect serviceTraceAspect() {
        return new ServiceTraceAspect(properties);
    }

    /**
     * Redis operation tracing aspect
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
    @ConditionalOnProperty(prefix = "gateway.trace", name = "trace-redis", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public RedisTraceAspect redisTraceAspect() {
        return new RedisTraceAspect(properties);
    }

    /**
     * MQ operation tracing aspect (RocketMQ)
     */
    @Bean
    @ConditionalOnClass(name = "org.apache.rocketmq.spring.core.RocketMQTemplate")
    @ConditionalOnProperty(prefix = "gateway.trace", name = "trace-mq", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public MQTraceAspect rocketMQTraceAspect() {
        return new MQTraceAspect(properties);
    }

    /**
     * MQ操作追踪切面（Kafka）
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @ConditionalOnProperty(prefix = "gateway.trace", name = "trace-mq", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public KafkaMQTraceAspect kafkaTraceAspect() {
        return new KafkaMQTraceAspect(properties);
    }

    /**
     * Database operation tracing aspect
     */
    @Bean
    @ConditionalOnClass(name = {"org.springframework.jdbc.core.JdbcTemplate", "org.apache.ibatis.annotations.Mapper"})
    @ConditionalOnProperty(prefix = "gateway.trace", name = "trace-db", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public DBTraceAspect dbTraceAspect() {
        return new DBTraceAspect(properties);
    }

    // ==================== OpenFeign Propagation ====================
    // Feign 相关配置已移至 FeignTraceConfiguration 内部配置类
    // 使用 @ConditionalOnClass 控制整个配置类的加载，避免类加载失败
}

/**
 * WebClient Trace Reporter 配置（内部配置类）
 * <p>
 * 使用独立的配置类配合 @ConditionalOnClass，确保只有当 reactor-netty 存在时才加载此配置，
 * 避免 AsyncTraceReporter 类加载时触发 NoClassDefFoundError。
 * <p>
 * FIX #4: 移除对 async-trace-enabled 的依赖。Trace 上报功能应在 enabled=true 时始终可用，
 * 不应与 @Async 线程池传播特性耦合。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "reactor.netty.resources.ConnectionProvider")
class WebClientTraceConfiguration {

    /**
     * Middleware metadata reporter (需要 WebClient)
     * <p>
     * 配置驱动方式：只要用户配置了 exporter URL 就会上报
     * 不再依赖 report-middleware 属性
     */
    @Bean
    @ConditionalOnMissingBean
    public MiddlewareMetadataReporter middlewareMetadataReporter(
            GatewayTraceProperties properties, Environment environment) {
        return new com.leoli.gateway.trace.reporter.MiddlewareMetadataReporter(properties, environment);
    }

    /**
     * Async Trace reporter (需要 reactor-netty 的 WebClient)
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncTraceReporter asyncTraceReporter(GatewayTraceProperties properties) {
        return new com.leoli.gateway.trace.reporter.AsyncTraceReporter(properties);
    }

    /**
     * Trace report interceptor: reports on request completion
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceReportInterceptor traceReportInterceptor(AsyncTraceReporter reporter, GatewayTraceProperties properties) {
        return new TraceReportInterceptor(reporter, properties);
    }
}

/**
 * Feign TraceId 传播配置（内部配置类）
 * <p>
 * 使用独立的配置类配合 @ConditionalOnClass，确保只有当 Feign 类存在时才加载此配置，
 * 避免 FeignTraceInterceptor 类加载时触发 NoClassDefFoundError。
 * <p>
 * 这是 Spring Boot 处理 optional 依赖的标准模式。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "feign.RequestInterceptor")
@ConditionalOnProperty(prefix = "gateway.trace", name = "trace-feign", havingValue = "true", matchIfMissing = true)
class FeignTraceConfiguration {

    /**
     * OpenFeign TraceId propagation interceptor
     * <p>
     * Automatically propagates TraceId to HTTP Header when calling downstream services via Feign
     */
    @Bean
    @ConditionalOnMissingBean
    public FeignTraceInterceptor feignTraceInterceptor(GatewayTraceProperties properties) {
        return new FeignTraceInterceptor(properties);
    }
}