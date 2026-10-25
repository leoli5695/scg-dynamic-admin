package com.seckill.service;

import com.seckill.mapper.ActivityMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ============================================================================
 * StockReconciliationService 的 Spring 装配测试
 * ============================================================================
 *
 * <p>这个类以前同时挂着 {@code @RequiredArgsConstructor} 和一个手写构造器：Lombok 照样生成
 * 了一个 7 参构造器（3 个 Counter 也是 final 字段），于是类上有两个 public 构造器，
 * 谁都没标 {@code @Autowired}。Spring 在多构造器且没有标注的情况下会退回去找无参构造器，
 * 找不到就直接 BeanInstantiationException —— 整个 seckill 应用起不来。
 *
 * <p>现有测试一个都没发现，因为 seckill 里唯一的 {@code @SpringBootTest} 用的是过滤过的
 * TestApp，压根不扫 {@code com.seckill.service}。所以这里用一个最小的真实容器把它钉住：
 * 依赖全给 mock，但 bean 本身交给 Spring 按类型解析构造器。
 *
 * @author leoli
 */
class StockReconciliationServiceWiringTest {

    @Test
    @DisplayName("Spring 必须能自己构造出这个 bean（只有一个构造器）")
    void springCanConstructTheBean() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestConfig.class, StockReconciliationService.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(StockReconciliationService.class));
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        ActivityMapper activityMapper() {
            return Mockito.mock(ActivityMapper.class);
        }

        @Bean
        SeckillDeductLua seckillDeductLua() {
            return Mockito.mock(SeckillDeductLua.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AlertService alertService() {
            return Mockito.mock(AlertService.class);
        }

        @Bean
        RedisDegradeService redisDegradeService() {
            return Mockito.mock(RedisDegradeService.class);
        }
    }
}
