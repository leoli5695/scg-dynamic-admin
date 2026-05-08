package com.seckill.redis.lua;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ============================================================================
 * Lua脚本SHA缓存服务
 * ============================================================================
 *
 * 功能:
 * 1. 启动时预加载Lua脚本到Redis，获取SHA1摘要
 * 2. 后续执行使用EVALSHA，减少网络传输（脚本内容可能几KB）
 * 3. 处理NOSCRIPT错误，自动重新加载脚本
 *
 * 性能优化:
 * - 原方式: 每次执行传输完整脚本（约5KB），高QPS下网络开销大
 * - 优化后: 每次执行只传输40字节SHA1摘要，网络开销降低99%
 *
 * 适用场景:
 * - 秒杀高并发场景（10w+ QPS）
 * - Lua脚本较长的情况
 */
@Slf4j
@Service
public class LuaScriptService {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    // 脚本SHA缓存
    private final AtomicReference<String> deductScriptSha = new AtomicReference<>();
    private final AtomicReference<String> rollbackScriptSha = new AtomicReference<>();

    // 脚本内容（从文件加载）
    private String deductScriptContent;
    private String rollbackScriptContent;

    // 监控指标（延迟初始化）
    private Counter scriptLoadCounter;
    private Counter scriptShaHitCounter;
    private Counter scriptShaMissCounter;
    private Timer scriptLoadTimer;

    public LuaScriptService(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * ============================================================================
     * 启动时预加载脚本
     * ============================================================================
     */
    @PostConstruct
    public void init() {
        // 初始化监控指标
        scriptLoadCounter = Counter.builder("seckill.lua.script_load")
                .description("Lua脚本加载次数")
                .register(meterRegistry);
        scriptShaHitCounter = Counter.builder("seckill.lua.sha_hit")
                .description("SHA缓存命中次数")
                .register(meterRegistry);
        scriptShaMissCounter = Counter.builder("seckill.lua.sha_miss")
                .description("SHA缓存未命中次数")
                .register(meterRegistry);
        scriptLoadTimer = Timer.builder("seckill.lua.script_load_time")
                .description("脚本加载耗时")
                .register(meterRegistry);

        Timer.Sample sample = Timer.start();

        try {
            // 加载脚本内容
            deductScriptContent = loadScriptContent("seckill_deduct.lua");
            rollbackScriptContent = loadScriptContent("stock_rollback.lua");

            // 预加载到Redis
            loadScriptsToRedis();

            log.info("Lua脚本SHA缓存初始化完成: deductSha={}, rollbackSha={}",
                    deductScriptSha.get(), rollbackScriptSha.get());

        } catch (Exception e) {
            log.error("Lua脚本预加载失败，将在首次执行时加载: {}", e.getMessage());
            // 预加载失败不影响启动，首次执行时会自动加载
        } finally {
            sample.stop(scriptLoadTimer);
        }
    }

    /**
     * ============================================================================
     * 加载脚本到Redis并缓存SHA
     * ============================================================================
     */
    public void loadScriptsToRedis() {
        try {
            // 加载扣减脚本
            String sha1 = loadScriptToRedis(deductScriptContent);
            deductScriptSha.set(sha1);
            scriptLoadCounter.increment();
            log.info("库存扣减脚本加载成功: sha={}", sha1);

            // 加载回补脚本
            sha1 = loadScriptToRedis(rollbackScriptContent);
            rollbackScriptSha.set(sha1);
            scriptLoadCounter.increment();
            log.info("库存回补脚本加载成功: sha={}", sha1);

        } catch (Exception e) {
            log.error("脚本加载失败: {}", e.getMessage());
            throw new RuntimeException("Lua脚本加载失败", e);
        }
    }

    /**
     * ============================================================================
     * 使用底层连接加载脚本到Redis
     * ============================================================================
     */
    private String loadScriptToRedis(String scriptContent) {
        return redisTemplate.execute((connection) -> {
            return connection.scriptLoad(
                    scriptContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        }, true);
    }

    /**
     * ============================================================================
     * 执行扣减脚本（使用SHA）
     * ============================================================================
     *
     * 如果Redis返回NOSCRIPT错误，自动重新加载脚本并重试
     */
    public Long executeDeductScript(java.util.List<String> keys, String... args) {
        String sha = deductScriptSha.get();

        if (sha == null) {
            // SHA未初始化，重新加载
            reloadDeductScript();
            sha = deductScriptSha.get();
        }

        try {
            // 尝试使用SHA执行
            Long result = executeSha(sha, keys, args);
            scriptShaHitCounter.increment();
            return result;

        } catch (Exception e) {
            // 可能是NOSCRIPT错误，重新加载并重试
            if (isNoScriptError(e)) {
                log.warn("Redis脚本缓存丢失，重新加载: sha={}", sha);
                reloadDeductScript();
                scriptShaMissCounter.increment();

                // 重试执行
                return executeSha(deductScriptSha.get(), keys, args);
            }
            throw e;
        }
    }

    /**
     * ============================================================================
     * 执行回补脚本（使用SHA）
     * ============================================================================
     */
    public Long executeRollbackScript(java.util.List<String> keys, String... args) {
        String sha = rollbackScriptSha.get();

        if (sha == null) {
            reloadRollbackScript();
            sha = rollbackScriptSha.get();
        }

        try {
            Long result = executeSha(sha, keys, args);
            scriptShaHitCounter.increment();
            return result;

        } catch (Exception e) {
            if (isNoScriptError(e)) {
                log.warn("Redis脚本缓存丢失，重新加载: sha={}", sha);
                reloadRollbackScript();
                scriptShaMissCounter.increment();
                return executeSha(rollbackScriptSha.get(), keys, args);
            }
            throw e;
        }
    }

    /**
     * ============================================================================
     * 使用SHA执行脚本
     * ============================================================================
     */
    private Long executeSha(String sha, java.util.List<String> keys, String... args) {
        // 构建EVALSHA命令参数
        // evalsha sha1 numkeys key1 key2 ... arg1 arg2 ...
        long numKeys = keys.size();

        java.util.List<String> params = new java.util.ArrayList<>();
        params.add(sha);
        params.add(String.valueOf(numKeys));
        params.addAll(keys);
        params.addAll(java.util.Arrays.asList(args));

        // 使用execute执行自定义命令
        return redisTemplate.execute(
                (connection) -> {
                    Object result = connection.execute("EVALSHA",
                            params.stream()
                                    .map(p -> p.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .toArray(byte[][]::new));
                    if (result instanceof Long) {
                        return (Long) result;
                    } else if (result instanceof Integer) {
                        return ((Integer) result).longValue();
                    } else if (result instanceof byte[]) {
                        String str = new String((byte[]) result, java.nio.charset.StandardCharsets.UTF_8);
                        return Long.parseLong(str);
                    }
                    return null;
                },
                true
        );
    }

    /**
     * ============================================================================
     * 重新加载扣减脚本
     * ============================================================================
     */
    private void reloadDeductScript() {
        try {
            String sha = loadScriptToRedis(deductScriptContent);
            deductScriptSha.set(sha);
            log.info("库存扣减脚本重新加载成功: sha={}", sha);
        } catch (Exception e) {
            log.error("重新加载扣减脚本失败: {}", e.getMessage());
            throw new RuntimeException("脚本重新加载失败", e);
        }
    }

    /**
     * ============================================================================
     * 重新加载回补脚本
     * ============================================================================
     */
    private void reloadRollbackScript() {
        try {
            String sha = loadScriptToRedis(rollbackScriptContent);
            rollbackScriptSha.set(sha);
            log.info("库存回补脚本重新加载成功: sha={}", sha);
        } catch (Exception e) {
            log.error("重新加载回补脚本失败: {}", e.getMessage());
            throw new RuntimeException("脚本重新加载失败", e);
        }
    }

    /**
     * ============================================================================
     * 判断是否为NOSCRIPT错误
     * ============================================================================
     */
    private boolean isNoScriptError(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("NOSCRIPT");
    }

    /**
     * ============================================================================
     * 加载脚本文件内容
     * ============================================================================
     */
    private String loadScriptContent(String fileName) {
        try {
            var resource = getClass().getClassLoader().getResource("lua/" + fileName);
            if (resource != null) {
                return java.nio.file.Files.readString(java.nio.file.Paths.get(resource.toURI()));
            }
        } catch (Exception e) {
            log.warn("加载Lua脚本文件失败: {}", e.getMessage());
        }
        // 返回默认脚本（开发模式）
        if (fileName.equals("seckill_deduct.lua")) {
            return "return 1";
        }
        return "return 0";
    }

    /**
     * ============================================================================
     * 获取当前SHA摘要（用于监控）
     * ============================================================================
     */
    public String getDeductScriptSha() {
        return deductScriptSha.get();
    }

    public String getRollbackScriptSha() {
        return rollbackScriptSha.get();
    }
}