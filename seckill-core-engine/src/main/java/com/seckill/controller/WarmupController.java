package com.seckill.controller;

import com.seckill.annotation.InternalApi;
import com.seckill.service.WarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 库存预热 Controller
 * ============================================================================
 *
 * 接口:
 * 1. POST /warmup/manual - 手动预热
 * 2. GET /warmup/status - 查询预热状态
 * 3. GET /warmup/verify - 验证预热结果
 * 4. POST /warmup/cleanup - 清理预热数据
 *
 * 【安全防护】：
 * - 所有接口标记 @InternalApi，仅允许白名单 IP 访问
 * - 生产环境必须配置 seckill.internal-api.enabled=true
 * - 防止恶意调用预热接口导致库存异常
 */
@Slf4j
@RestController
@RequestMapping("/warmup")
@RequiredArgsConstructor
public class WarmupController {

    private final WarmupService warmupService;

    /**
     * 手动预热
     * 【内部接口】仅允许白名单 IP 调用
     */
    @InternalApi(description = "手动预热库存数据")
    @PostMapping("/manual")
    public WarmupResponse manualWarmup(@RequestParam Long seckillId) {
        boolean success = warmupService.manualWarmup(seckillId);

        WarmupResponse response = new WarmupResponse();
        response.setSuccess(success);
        response.setMessage(success ? "预热成功" : "预热失败");
        return response;
    }

    /**
     * 查询预热状态
     * 【内部接口】仅允许白名单 IP 调用
     */
    @InternalApi(description = "查询预热状态")
    @GetMapping("/status")
    public WarmupStatusResponse getWarmupStatus(@RequestParam Long seckillId) {
        boolean warmedUp = warmupService.isWarmedUp(seckillId);

        WarmupStatusResponse response = new WarmupStatusResponse();
        response.setSeckillId(seckillId);
        response.setWarmedUp(warmedUp);
        return response;
    }

    /**
     * 验证预热结果
     * 【内部接口】仅允许白名单 IP 调用
     */
    @InternalApi(description = "验证预热结果")
    @GetMapping("/verify")
    public WarmupService.WarmupResult verifyWarmup(@RequestParam Long seckillId) {
        return warmupService.verifyWarmup(seckillId);
    }

    /**
     * 清理预热数据
     * 【内部接口】仅允许白名单 IP 调用
     */
    @InternalApi(description = "清理预热数据")
    @PostMapping("/cleanup")
    public WarmupResponse cleanupWarmup(@RequestParam Long seckillId) {
        warmupService.cleanupWarmup(seckillId);

        WarmupResponse response = new WarmupResponse();
        response.setSuccess(true);
        response.setMessage("清理成功");
        return response;
    }

    /**
     * 预热响应
     */
    public static class WarmupResponse {
        private boolean success;
        private String message;

        public boolean getSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /**
     * 预热状态响应
     */
    public static class WarmupStatusResponse {
        private Long seckillId;
        private boolean warmedUp;

        public Long getSeckillId() { return seckillId; }
        public void setSeckillId(Long seckillId) { this.seckillId = seckillId; }
        public boolean isWarmedUp() { return warmedUp; }
        public void setWarmedUp(boolean warmedUp) { this.warmedUp = warmedUp; }
    }
}