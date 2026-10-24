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
        // 拦截所有路径：是否校验由方法上的 @InternalApi 注解决定（无注解立即放行）。
        // 之前这里是显式路径列表，任何新增的 @InternalApi 接口只要忘记加进列表就完全
        // 不受保护 —— /payment/callback 就是这样漏掉的，与注解本身的语义相悖。
        registry.addInterceptor(new InternalApiInterceptor(seckillConfig, environment))
                .addPathPatterns("/**")
                .order(1);  // 优先级最高

        WebMvcConfigurer.super.addInterceptors(registry);
    }
}