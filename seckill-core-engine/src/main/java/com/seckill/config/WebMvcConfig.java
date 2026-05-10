package com.seckill.config;

import com.seckill.interceptor.InternalApiInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ============================================================================
 * Web MVC 配置
 * ============================================================================
 * <p>
 * 功能：
 * 1. 注册 InternalApiInterceptor 拦截器
 * 2. 配置拦截路径（/warmup/**、/admin/** 等内部接口）
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final SeckillConfig seckillConfig;
    private final Environment environment;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册内部 API 拦截器
        // 拦截所有路径，由注解决定是否校验
        registry.addInterceptor(new InternalApiInterceptor(seckillConfig, environment))
                .addPathPatterns("/warmup/**", "/admin/**", "/internal/**")
                .order(1);  // 优先级最高

        WebMvcConfigurer.super.addInterceptors(registry);
    }
}