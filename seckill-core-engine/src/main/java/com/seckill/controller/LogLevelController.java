package com.seckill.controller;

import com.seckill.annotation.InternalApi;
import com.seckill.dto.LogLevelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================================
 * 日志级别动态调整 Controller
 * ============================================================================
 * <p>
 * OPTIMIZATION (P2): 实现动态日志级别调整
 * <p>
 * 功能:
 * 1. 查询指定包/类的日志级别
 * 2. 动态修改日志级别（无需重启）
 * 3. 重置为默认日志级别
 * <p>
 * 使用场景:
 * - 生产环境故障排查时临时开启DEBUG日志
 * - 排查完成后恢复INFO日志
 * - 无需重启服务，避免影响业务
 * <p>
 * 安全防护:
 * - 所有接口标记 @InternalApi，仅允许白名单 IP 访问
 * - 防止恶意调用导致日志刷屏、磁盘满
 * <p>
 * 注意:
 * - 动态修改的日志级别在服务重启后会恢复为配置文件中的默认值
 * - 如需永久修改，请更新 application.yml 配置文件
 */
@Slf4j
@RestController
@RequestMapping("/log")
@RequiredArgsConstructor
@Tag(name = "log", description = "日志管理接口 - 动态调整日志级别")
public class LogLevelController {

    private final LoggingSystem loggingSystem;

    /**
     * 预定义的常用包名（便于快速操作）
     */
    private static final Map<String, String> PREDEFINED_PACKAGES = new HashMap<>();
    static {
        PREDEFINED_PACKAGES.put("seckill", "com.seckill");
        PREDEFINED_PACKAGES.put("rocketmq", "org.apache.rocketmq");
        PREDEFINED_PACKAGES.put("sharding", "org.apache.shardingsphere");
        PREDEFINED_PACKAGES.put("mybatis", "com.baomidou.mybatisplus");
        PREDEFINED_PACKAGES.put("redis", "org.springframework.data.redis");
        PREDEFINED_PACKAGES.put("root", "ROOT");
    }

    /**
     * ============================================================================
     * 查询日志级别
     * ============================================================================
     * <p>
     * 支持预定义别名和完整包名
     */
    @Operation(
            summary = "查询日志级别",
            description = "查询指定包或类的当前日志级别"
    )
    @InternalApi(description = "查询日志级别")
    @GetMapping("/level")
    public LogLevelResponse getLogLevel(
            @Parameter(description = "包名或别名（seckill/rocketmq/sharding/mybatis/redis/root）", example = "seckill")
            @RequestParam String packageName) {

        String resolvedPackage = resolvePackageName(packageName);
        LogLevel level = loggingSystem.getLoggerConfiguration(resolvedPackage).getEffectiveLevel();

        LogLevelResponse response = new LogLevelResponse();
        response.setPackageName(resolvedPackage);
        response.setLevel(level != null ? level.name() : "OFF");
        response.setMessage("查询成功");

        log.info("查询日志级别: packageName={}, level={}", resolvedPackage, level);

        return response;
    }

    /**
     * ============================================================================
     * 设置日志级别
     * ============================================================================
     * <p>
     * 动态修改日志级别，无需重启服务
     */
    @Operation(
            summary = "设置日志级别",
            description = "动态修改指定包或类的日志级别（无需重启）"
    )
    @InternalApi(description = "设置日志级别")
    @PostMapping("/level")
    public LogLevelResponse setLogLevel(
            @Parameter(description = "包名或别名", example = "seckill")
            @RequestParam String packageName,
            @Parameter(description = "日志级别（TRACE/DEBUG/INFO/WARN/ERROR/FATAL/OFF）", example = "DEBUG")
            @RequestParam String level) {

        String resolvedPackage = resolvePackageName(packageName);
        LogLevel logLevel = parseLogLevel(level);

        if (logLevel == null) {
            LogLevelResponse response = new LogLevelResponse();
            response.setPackageName(resolvedPackage);
            response.setLevel(level);
            response.setMessage("无效的日志级别: " + level);
            return response;
        }

        // 设置日志级别
        loggingSystem.setLogLevel(resolvedPackage, logLevel);

        LogLevelResponse response = new LogLevelResponse();
        response.setPackageName(resolvedPackage);
        response.setLevel(logLevel.name());
        response.setMessage("日志级别已修改");

        log.warn("动态修改日志级别: packageName={}, level={}, 操作人应确保排查后恢复原级别",
                resolvedPackage, logLevel);

        return response;
    }

    /**
     * ============================================================================
     * 重置日志级别为INFO（常用操作）
     * ============================================================================
     * <p>
     * 排查完成后快速恢复INFO级别
     */
    @Operation(
            summary = "重置为INFO级别",
            description = "将指定包的日志级别重置为INFO（排查完成后恢复）"
    )
    @InternalApi(description = "重置日志级别为INFO")
    @PostMapping("/reset")
    public LogLevelResponse resetToInfo(
            @Parameter(description = "包名或别名", example = "seckill")
            @RequestParam String packageName) {

        String resolvedPackage = resolvePackageName(packageName);
        loggingSystem.setLogLevel(resolvedPackage, LogLevel.INFO);

        LogLevelResponse response = new LogLevelResponse();
        response.setPackageName(resolvedPackage);
        response.setLevel("INFO");
        response.setMessage("日志级别已重置为INFO");

        log.info("日志级别重置为INFO: packageName={}", resolvedPackage);

        return response;
    }

    /**
     * ============================================================================
     * 获取所有预定义包别名
     * ============================================================================
     */
    @Operation(
            summary = "获取预定义包别名",
            description = "获取支持的预定义包名别名列表"
    )
    @InternalApi(description = "获取预定义包别名")
    @GetMapping("/aliases")
    public Map<String, String> getAliases() {
        return PREDEFINED_PACKAGES;
    }

    /**
     * 解析包名（支持别名）
     */
    private String resolvePackageName(String packageName) {
        if (PREDEFINED_PACKAGES.containsKey(packageName.toLowerCase())) {
            return PREDEFINED_PACKAGES.get(packageName.toLowerCase());
        }
        return packageName;
    }

    /**
     * 解析日志级别
     */
    private LogLevel parseLogLevel(String level) {
        try {
            return LogLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}