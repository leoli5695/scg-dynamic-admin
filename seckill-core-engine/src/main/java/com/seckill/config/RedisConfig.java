package com.seckill.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * ============================================================================
 * Redis 配置类
 * ============================================================================
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    @Bean
    public RedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(loadLuaScript("seckill_deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> stockRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(loadLuaScript("stock_rollback.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private String loadLuaScript(String fileName) {
        try {
            var resource = getClass().getClassLoader().getResource("lua/" + fileName);
            if (resource != null) {
                return Files.readString(Paths.get(resource.toURI()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载Lua脚本失败: " + fileName, e);
        }
        throw new IllegalStateException("Lua脚本文件不存在: lua/" + fileName
                + "。请确认 src/main/resources/lua/ 下有该文件且已被 Maven 打包到 classpath");
    }

    @Bean
    public Counter seckillRequestCounter() {
        return Counter.builder("seckill.request")
                .description("秒杀请求总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter seckillSuccessCounter() {
        return Counter.builder("seckill.success")
                .description("秒杀成功总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter seckillStockInsufficientCounter() {
        return Counter.builder("seckill.stock_insufficient")
                .description("库存不足总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter seckillAlreadyBoughtCounter() {
        return Counter.builder("seckill.already_bought")
                .description("重复购买总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter seckillNotWarmedCounter() {
        return Counter.builder("seckill.stock_not_warmed")
                .description("库存未预热总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter seckillDegradeCounter() {
        return Counter.builder("seckill.degrade")
                .description("Redis降级模式命中次数")
                .register(meterRegistry);
    }

    @Bean
    public Timer luaScriptTimer() {
        return Timer.builder("seckill.lua")
                .description("Lua脚本执行耗时")
                .register(meterRegistry);
    }
}