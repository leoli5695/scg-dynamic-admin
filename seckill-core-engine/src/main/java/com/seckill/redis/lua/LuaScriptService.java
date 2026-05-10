package com.seckill.redis.lua;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================================================
 * Lua 脚本执行服务
 * ============================================================================
 *
 * <p>薄封装：委托给 Spring Data Redis 的 {@link StringRedisTemplate#execute(RedisScript, List, Object...)}。
 *
 * <p><b>为什么不自己做 SHA 缓存</b>：Spring 的 {@code DefaultScriptExecutor} 已经实现了：
 * <ul>
 *   <li>首次用 EVALSHA（{@link RedisScript#getSha1()} 启动时即算好）</li>
 *   <li>Redis 返回 NOSCRIPT 时自动 fallback 到 EVAL 重载脚本并重试</li>
 *   <li>跨连接实现（Lettuce / Redisson / Jedis）统一走 {@code scriptingCommands()} 接口</li>
 * </ul>
 *
 * <p><b>历史坑</b>：之前这里自己拼 {@code connection.execute("EVALSHA", byte[][])} 原始命令，
 * 在 Redisson 的 Spring Data Redis 适配层下抛 {@code UnsupportedOperationException}——
 * 秒杀扣库存路径在任何用 Redisson 作为连接工厂的环境下根本跑不通。用 Spring 的 API 解决此问题。
 */
@Slf4j
@Service
public class LuaScriptService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> seckillDeductScript;
    private final RedisScript<Long> stockRollbackScript;

    private final Counter luaSuccessCounter;
    private final Counter luaFailureCounter;
    private final Timer luaLatencyTimer;

    public LuaScriptService(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> seckillDeductScript,
            RedisScript<Long> stockRollbackScript,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.seckillDeductScript = seckillDeductScript;
        this.stockRollbackScript = stockRollbackScript;

        this.luaSuccessCounter = Counter.builder("seckill.lua.exec")
                .tag("outcome", "success")
                .description("Lua 脚本执行次数（按结果）")
                .register(meterRegistry);
        this.luaFailureCounter = Counter.builder("seckill.lua.exec")
                .tag("outcome", "failure")
                .description("Lua 脚本执行次数（按结果）")
                .register(meterRegistry);
        this.luaLatencyTimer = Timer.builder("seckill.lua.latency")
                .description("Lua 脚本执行耗时")
                .register(meterRegistry);

        log.info("LuaScriptService 初始化完成: deductSha={}, rollbackSha={}",
                seckillDeductScript.getSha1(), stockRollbackScript.getSha1());
    }

    public Long executeDeductScript(List<String> keys, String... args) {
        return execute(seckillDeductScript, keys, args);
    }

    public Long executeRollbackScript(List<String> keys, String... args) {
        return execute(stockRollbackScript, keys, args);
    }

    private Long execute(RedisScript<Long> script, List<String> keys, String... args) {
        Timer.Sample sample = Timer.start();
        try {
            // StringRedisTemplate 的 DefaultScriptExecutor 用 StringRedisSerializer 处理
            // keys 和 args，EVALSHA 优先、NOSCRIPT 自动 fallback 到 EVAL。
            Long result = redisTemplate.execute(script, keys, (Object[]) args);
            luaSuccessCounter.increment();
            return result;
        } catch (RuntimeException e) {
            luaFailureCounter.increment();
            log.error("Lua 脚本执行失败: sha={}, keys={}, err={}",
                    script.getSha1(), keys, e.getMessage());
            throw e;
        } finally {
            sample.stop(luaLatencyTimer);
        }
    }
}
