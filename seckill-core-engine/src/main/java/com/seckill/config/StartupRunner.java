package com.seckill.config;

import com.seckill.service.WarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * 系统启动执行器
 * ============================================================================
 * <p>
 * 功能:
 * 1. 系统启动时预热库存
 * 2. 初始化监控指标
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRunner implements ApplicationRunner {

    private final WarmupService warmupService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("========== 秒杀核心引擎启动完成 ==========");

        // 启动时预热库存
        warmupService.warmupOnStartup();

        log.info("========== 系统就绪，等待秒杀请求 ==========");
    }
}