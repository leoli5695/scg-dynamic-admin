package com.leoli.gateway.trace.autoconfig;

import com.leoli.gateway.trace.aspect.DBTraceAspect;
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
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
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
@Import(TraceThreadPoolConfig.class)
public class GatewayTraceAutoConfiguration implements WebMvcConfigurer {

    private final Environment environment;
    private final GatewayTraceProperties properties;

    /**
     * Async Trace reporter
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncTraceReporter asyncTraceReporter() {
        return new AsyncTraceReporter(properties);
    }

    /**
     * Middleware metadata reporter
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.trace", name = "report-middleware", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public MiddlewareMetadataReporter middlewareMetadataReporter() {
        return new MiddlewareMetadataReporter(properties, environment);
    }

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
     * Trace report interceptor: reports on request completion
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceReportInterceptor traceReportInterceptor(AsyncTraceReporter reporter) {
        return new TraceReportInterceptor(reporter);
    }

    /**
     * Register interceptors
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // TraceWebInterceptor executes first (extracts TraceId)
        registry.addInterceptor(traceWebInterceptor())
                .addPathPatterns("/**")
                .order(-100);  // Highest priority

        // TraceReportInterceptor executes last (reports data)
        registry.addInterceptor(traceReportInterceptor(asyncTraceReporter()))
                .addPathPatterns("/**")
                .order(100);  // Executes last
    }

    // ==================== AOP Aspects ====================

    /**
     * Service method tracing aspect
     */
    @Bean
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnMissingBean
    public ServiceTraceAspect serviceTraceAspect() {
        return new ServiceTraceAspect();
    }

    /**
     * Redis operation tracing aspect
     */
    @Bean
    @ConditionalOnClass(RedisTemplate.class)
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
    public MQTraceAspect kafkaTraceAspect() {
        return new MQTraceAspect(properties);
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

    /**
     * OpenFeign TraceId propagation interceptor
     * <p>
     * Automatically propagates TraceId to HTTP Header when calling downstream services via Feign
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
    @ConditionalOnMissingBean
    public FeignTraceInterceptor feignTraceInterceptor() {
        return new FeignTraceInterceptor();
    }
}