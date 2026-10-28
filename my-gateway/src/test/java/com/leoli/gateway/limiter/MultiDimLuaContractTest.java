package com.leoli.gateway.limiter;

import com.leoli.gateway.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the real generated Lua with a deterministic implementation of the Redis commands it uses. */
class MultiDimLuaContractTest {
    private final Map<String, List<Double>> scores = new HashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private long now = 10000;
    private static LuaTable table(LuaValue... values) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < values.length; i++) table.set(i + 1, values[i]);
        return table;
    }
    private LuaValue evaluate(boolean firstHit, int firstLimit) {
        var limiter = new DistributedRateLimiter();
        ReflectionTestUtils.setField(limiter, "rateLimitScript", new RedisConfig().rateLimitScript());
        DefaultRedisScript<?> script = ReflectionTestUtils.invokeMethod(limiter, "batchScript");
        Globals lua = JsePlatform.standardGlobals();
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String command = args.arg(1).tojstring(), key = args.arg(2).tojstring();
                switch (command) {
                    case "TIME": return table(new LuaValue[]{LuaValue.valueOf(now / 1000), LuaValue.valueOf((now % 1000) * 1000)});
                    case "ZREMRANGEBYSCORE":
                        scores.computeIfAbsent(key, k -> new ArrayList<>()).removeIf(score -> score <= args.arg(4).todouble());
                        return LuaValue.ZERO;
                    case "ZCARD": return LuaValue.valueOf(scores.computeIfAbsent(key, k -> new ArrayList<>()).size());
                    case "INCR": return LuaValue.valueOf(sequences.merge(key, 1L, Long::sum));
                    case "ZADD": scores.computeIfAbsent(key, k -> new ArrayList<>()).add(args.arg(3).todouble()); return LuaValue.ONE;
                    case "EXPIRE": return LuaValue.ONE;
                    default: throw new AssertionError(command);
                }
            }
        });
        lua.set("redis", redis);
        lua.set("KEYS", table(new LuaValue[]{LuaValue.valueOf("global"), LuaValue.valueOf("global:seq"), LuaValue.valueOf("user"), LuaValue.valueOf("user:seq")}));
        lua.set("ARGV", table(new LuaValue[]{LuaValue.valueOf(firstHit ? "1" : "0"), LuaValue.valueOf("1000"), LuaValue.valueOf(firstLimit), LuaValue.ZERO,
                LuaValue.valueOf("1000"), LuaValue.valueOf("10"), LuaValue.ZERO}));
        return lua.load(script.getScriptAsString()).call();
    }
    @Test void firstHitDoesNotChargeLaterDimensionsWhenEarlierDimensionDenies() {
        var result = evaluate(true, 0);
        assertEquals(1, result.length()); assertEquals(0, result.get(1).toint()); assertFalse(scores.containsKey("user"));
    }
    @Test void allHitStillChargesAllowedLaterDimensionAfterEarlierDenial() {
        var result = evaluate(false, 0);
        assertEquals(2, result.length()); assertEquals(0, result.get(1).toint()); assertEquals(1, result.get(2).toint());
        assertEquals(1, scores.get("user").size());
    }
    @Test void windowExpiryAndSequenceKeysMatchSingleDimensionSemantics() {
        assertEquals(1, evaluate(true, 1).get(1).toint());
        assertEquals(0, evaluate(true, 1).get(1).toint());
        now += 1100;
        assertEquals(1, evaluate(true, 1).get(1).toint());
        assertEquals(1, scores.get("global").size()); assertEquals(2L, sequences.get("global:seq"));
    }
    @Test void clusterNeverEnablesMultiKeyBatch() {
        var limiter = new DistributedRateLimiter();
        ReflectionTestUtils.setField(limiter, "redisMode", "cluster");
        assertFalse(limiter.supportsBatch());
    }
}
